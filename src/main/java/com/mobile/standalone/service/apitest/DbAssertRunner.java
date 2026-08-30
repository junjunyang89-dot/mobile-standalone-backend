package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestEnv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
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

    /** 这个环境配了数据库断言用的库没有 */
    public boolean configured(ApiTestEnv env) {
        return env != null && notBlank(env.getDbHost()) && notBlank(env.getDbName());
    }

    /**
     * 执行一条只读查询。
     * <p>
     * 连接不缓存：断言查询一次执行最多几条，连接池省下的那点开销
     * 换不来「测试库连接被一直占着」的麻烦。
     */
    public Result query(ApiTestEnv env, String rawSql) {
        String sql;
        try {
            sql = sanitizeSelect(rawSql);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        if (!configured(env)) {
            return Result.fail("这个环境还没配数据库断言用的库，去「环境变量」里填一个只读账号");
        }

        String url = "jdbc:mysql://" + env.getDbHost() + ":"
                + (env.getDbPort() == null ? 3306 : env.getDbPort()) + "/" + env.getDbName()
                // allowMultiQueries 默认就是 false，这里显式写死：
                // 即使上面的校验哪天被绕过，驱动这一层也执行不了第二条
                + "?useSSL=false&allowMultiQueries=false&connectTimeout=8000"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        Properties props = new Properties();
        props.setProperty("user", env.getDbUser() == null ? "" : env.getDbUser());
        props.setProperty("password", env.getDbPassword() == null ? "" : env.getDbPassword());

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
