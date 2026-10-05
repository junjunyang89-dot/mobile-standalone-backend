package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiDbConnection;
import com.mobile.standalone.entity.ApiTestEnv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 数据库断言：调完接口去库里确认数据真的落了。
 * <p>
 * 「接口返回 200」不等于「数据对了」——最常见的漏测就是接口报成功但没写库、
 * 或者状态没流转。这类只能查库才能验证。
 * <p>
 * <b>安全边界</b>（这是给测试同学用的，不能变成执行任意 SQL 的口子）：
 * 只允许单条 SELECT；强制补 LIMIT；连接用环境上绑定的只读账号；
 * 分号、注释、多语句、写操作一律拒绝。这些不是「防黑客」——
 * 是防一条手滑写成 UPDATE 的断言把测试库改掉，而那种事故查起来毫无线索。
 */
@Slf4j
@Service
public class DbAssertRunner {

    /** 出现任何一个就拒绝执行 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|replace|call"
                    + "|load_file|outfile|dumpfile|into\\s+outfile|set\\s+@"
                    + "|information_schema\\.user|mysql\\.)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HAS_LIMIT = Pattern.compile("\\blimit\\s+\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("--|/\\*|#");
    /** SHOW GRANTS 里出现这些就不是只读账号 */
    private static final Pattern WRITE_GRANT = Pattern.compile(
            "\\b(ALL PRIVILEGES|INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|REPLACE|TRUNCATE|GRANT OPTION)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_LIMIT = 100;
    private static final int TIMEOUT_SECONDS = 8;

    /** 查询结果。{@code value} 是给断言比的那个标量 */
    public record Result(boolean ok, Object value, List<Map<String, Object>> rows,
                         int rowCount, String sql, String error) {

        static Result fail(String error) {
            return new Result(false, null, List.of(), 0, null, error);
        }
    }

    /**
     * SQL 安全校验。
     *
     * @return 通过时返回补好 LIMIT 的 SQL；不通过时抛 {@link IllegalArgumentException}，
     *         消息就是给人看的原因
     */
    public String sanitizeSelect(String raw) {
        String text = raw == null ? "" : raw.trim().replaceAll(";+\\s*$", "");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        // 多语句：结尾的分号上面已经去掉，中间还出现说明想执行多条
        if (text.contains(";")) {
            throw new IllegalArgumentException("不支持多条 SQL，请只写一条 SELECT");
        }
        // 注释常被用来绕过关键字检查
        if (COMMENT.matcher(text).find()) {
            throw new IllegalArgumentException("SQL 里不能包含注释");
        }
        if (!text.toLowerCase(Locale.ROOT).startsWith("select ")) {
            throw new IllegalArgumentException("只允许 SELECT 查询");
        }
        if (FORBIDDEN.matcher(text).find()) {
            throw new IllegalArgumentException("只允许只读查询，不能包含写操作或系统表");
        }
        // 没写 LIMIT 就补一个：断言只需要少量行，别让一条误写的全表扫描拖垮测试库
        return HAS_LIMIT.matcher(text).find() ? text : text + " LIMIT " + DEFAULT_LIMIT;
    }

    /** 连到哪个库 */
    public record Target(String host, Integer port, String db, String user, String password) {
        public static Target of(ApiDbConnection c) {
            return c == null ? null
                    : new Target(c.getDbHost(), c.getDbPort(), c.getDbName(), c.getDbUser(), c.getDbPassword());
        }

        public static Target of(ApiTestEnv e) {
            return e == null ? null
                    : new Target(e.getDbHost(), e.getDbPort(), e.getDbName(), e.getDbUser(), e.getDbPassword());
        }
    }

    public boolean configured(Target t) {
        return t != null && notBlank(t.host()) && notBlank(t.db());
    }

    /**
     * 配库时的连通性探测：连上、看版本、看账号是不是只有读权限。
     * 不走 {@link #sanitizeSelect}——这里要跑 {@code SHOW GRANTS}，那不是断言 SQL。
     */
    public Probe probe(Target t) {
        if (!configured(t)) {
            return Probe.fail(0, "地址和库名必填");
        }
        String url = jdbcUrl(t);
        Properties props = new Properties();
        props.setProperty("user", t.user() == null ? "" : t.user());
        props.setProperty("password", t.password() == null ? "" : t.password());
        long start = System.nanoTime();
        try (Connection conn = DriverManager.getConnection(url, props);
             Statement st = conn.createStatement()) {
            conn.setReadOnly(true);
            st.setQueryTimeout(TIMEOUT_SECONDS);
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName();
            String version = md.getDatabaseProductVersion();
            List<String> grants = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                while (rs.next()) {
                    grants.add(rs.getString(1));
                }
            } catch (Exception ignored) {
                // 有的账号不给看 GRANT，连上了就算测通，权限那栏空着
            }
            long ms = elapsedMs(start);
            Boolean readOnly = grants.isEmpty() ? null : grantsLookReadOnly(grants);
            return Probe.ok(ms, product, version, readOnly);
        } catch (Exception e) {
            return Probe.fail(elapsedMs(start), e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    public record Probe(boolean ok, long elapsedMs, String product, String version, Boolean readOnly, String error) {
        static Probe ok(long ms, String product, String version, Boolean readOnly) {
            return new Probe(true, ms, product, version, readOnly, null);
        }

        static Probe fail(long ms, String error) {
            return new Probe(false, ms, null, null, null, error);
        }

        public String message() {
            if (!ok) {
                return error == null || error.isBlank() ? "连不上" : error;
            }
            StringBuilder sb = new StringBuilder("连接成功 ").append(elapsedMs).append("ms");
            String ver = formatVersion(product, version);
            if (ver != null) {
                sb.append(" —— ").append(ver);
            }
            if (Boolean.TRUE.equals(readOnly)) {
                sb.append("，账号权限：只有读权限（符合要求）");
            } else if (Boolean.FALSE.equals(readOnly)) {
                sb.append("，账号权限：有写权限（请换成只读账号）");
            }
            return sb.toString();
        }
    }

    static boolean grantsLookReadOnly(List<String> grants) {
        if (grants == null || grants.isEmpty()) {
            return true;
        }
        for (String g : grants) {
            if (g != null && WRITE_GRANT.matcher(g).find()) {
                return false;
            }
        }
        return true;
    }

    static String formatVersion(String product, String version) {
        if (!notBlank(version)) {
            return notBlank(product) ? product : null;
        }
        String v = version.trim();
        // 驱动有时给 "8.0.34"，有时给 "8.0.34-log"
        int dash = v.indexOf('-');
        if (dash > 0) {
            v = v.substring(0, dash);
        }
        String p = notBlank(product) ? product.trim() : "MySQL";
        if (v.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))) {
            return v;
        }
        return p + " " + v;
    }

    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    /** 一个库里查到的建表语句（表名小写 → DDL）；连不上时 {@code error} 非空 */
    public record DdlLookup(Map<String, String> ddl, String error) {
    }

    /**
     * 接口对比分析用：去库里现读这几张表的建表语句。
     * 表名先过白名单再拼进 SQL——它来自代码扫描，不是人输入的，但拼 SQL 的地方不该指望上游干净。
     * 库里没有的表跳过（多半是别的库的表），不算错。
     */
    public DdlLookup showCreateTables(Target t, java.util.Collection<String> tables) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!configured(t)) {
            return new DdlLookup(out, "地址和库名没配全");
        }
        List<String> names = new ArrayList<>();
        if (tables != null) {
            for (String n : tables) {
                String s = n == null ? "" : n.trim();
                int dot = s.lastIndexOf('.');
                if (dot >= 0) {
                    s = s.substring(dot + 1);
                }
                s = s.replace("`", "");
                if (TABLE_NAME.matcher(s).matches() && !names.contains(s)) {
                    names.add(s);
                }
            }
        }
        if (names.isEmpty()) {
            return new DdlLookup(out, null);
        }
        Properties props = new Properties();
        props.setProperty("user", t.user() == null ? "" : t.user());
        props.setProperty("password", t.password() == null ? "" : t.password());
        try (Connection conn = DriverManager.getConnection(jdbcUrl(t), props);
             Statement st = conn.createStatement()) {
            conn.setReadOnly(true);
            st.setQueryTimeout(TIMEOUT_SECONDS);
            for (String n : names) {
                try (ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + n + "`")) {
                    if (rs.next()) {
                        out.put(n.toLowerCase(Locale.ROOT), rs.getString(2));
                    }
                } catch (java.sql.SQLException e) {
                    // 1146 = 表不存在：这张表在别的库，换下一个库找
                    if (e.getErrorCode() != 1146) {
                        log.debug("[db-ddl] SHOW CREATE TABLE {} 失败: {}", n, e.getMessage());
                    }
                }
            }
            return new DdlLookup(out, null);
        } catch (Exception e) {
            return new DdlLookup(out, e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String jdbcUrl(Target t) {
        return "jdbc:mysql://" + t.host() + ":"
                + (t.port() == null ? 3306 : t.port()) + "/" + t.db()
                + "?useSSL=false&allowMultiQueries=false&connectTimeout=8000"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    public Result query(ApiTestEnv env, String rawSql) {
        return query(Target.of(env), rawSql);
    }

    /**
     * 执行一条只读查询。
     * <p>
     * 连接不缓存：断言查询一次执行最多几条，连接池省下的那点开销
     * 换不来「测试库连接被一直占着」的麻烦。
     */
    public Result query(Target t, String rawSql) {
        String sql;
        try {
            sql = sanitizeSelect(rawSql);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        if (!configured(t)) {
            return Result.fail("还没配数据库断言用的库，去「项目管理」→「数据库」加一条只读账号");
        }

        String url = jdbcUrl(t);
        Properties props = new Properties();
        props.setProperty("user", t.user() == null ? "" : t.user());
        props.setProperty("password", t.password() == null ? "" : t.password());

        try (Connection conn = DriverManager.getConnection(url, props);
             Statement st = conn.createStatement()) {
            conn.setReadOnly(true);
            st.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                int count = 0;
                while (rs.next()) {
                    count++;
                    // 只回带前 20 行给人看，剩下的只计数——断言要的是结论不是数据
                    if (rows.size() < 20) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
                // 单行单列直接给标量——断言里写 count(*) 是最常见的写法
                Object value = (count == 1 && cols == 1 && !rows.isEmpty())
                        ? rows.get(0).values().iterator().next()
                        : count;
                return new Result(true, value, rows, count, sql, null);
            }
        } catch (Exception e) {
            return Result.fail(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
