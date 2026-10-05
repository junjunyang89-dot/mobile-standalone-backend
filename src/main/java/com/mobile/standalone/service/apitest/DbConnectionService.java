package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiDbConnection;
import com.mobile.standalone.service.EnvVariableService;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据库断言的连接：按环境 × 项目管理。
 * <p>
 * 原来一个环境只有一个库，所有项目的断言都查它；实际上每个后端各有各的库，
 * 还有几个项目共用的（比如 admin 库）。查错库不会报错，只会「查不到」——
 * 于是「数据应该落库」的断言失败、「删除后应该查不到」的断言反而通过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("apiTestCaseService")
public class DbConnectionService {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,32}$");

    private final JdbcTemplate jdbcTemplate;
    private final DbAssertRunner dbAssert;

    /** 选库结果：要么给连接，要么给一句人看得懂的原因 */
    public record Pick(ApiDbConnection conn, String error) {
        static Pick ok(ApiDbConnection c) {
            return new Pick(c, null);
        }

        static Pick fail(String error) {
            return new Pick(null, error);
        }
    }

    @PostConstruct
    void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_db_connections (
                        id VARCHAR(32) PRIMARY KEY,
                        environment VARCHAR(16) NOT NULL,
                        name VARCHAR(64) NOT NULL,
                        db_host VARCHAR(255) NOT NULL,
                        db_port INT NULL,
                        db_name VARCHAR(128) NOT NULL,
                        db_user VARCHAR(128) NULL,
                        db_password VARCHAR(255) NULL,
                        projects VARCHAR(1000) NULL COMMENT '逗号分隔的项目编码；空=通用',
                        remark VARCHAR(255) NULL,
                        updated_at DATETIME NULL,
                        UNIQUE KEY uk_env_name (environment, name)
                    ) DEFAULT CHARSET=utf8mb4 COMMENT='数据库断言连接'
                    """);
            migrateLegacy();
        } catch (Exception e) {
            log.warn("[db-conn] 建表/迁移失败: {}", e.getMessage());
        }
    }

    /**
     * 老配置（环境上的一组 db_*）搬成一条通用连接，搬完把老字段清掉——
     * 不清的话，人把这条连接删了，下次重启它又会被搬回来。
     */
    private void migrateLegacy() {
        List<Map<String, Object>> legacy = jdbcTemplate.queryForList(
                "SELECT environment, db_host, db_port, db_name, db_user, db_password FROM api_test_envs "
                        + "WHERE db_host IS NOT NULL AND db_host <> '' AND db_name IS NOT NULL AND db_name <> ''");
        for (Map<String, Object> row : legacy) {
            String env = TestEnvironment.normalize(String.valueOf(row.get("environment")));
            String dbName = String.valueOf(row.get("db_name"));
            String name = NAME.matcher(dbName).matches() ? dbName : "default";
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM api_db_connections WHERE environment = ? AND name = ?",
                    Integer.class, env, name);
            if (exists == null || exists == 0) {
                jdbcTemplate.update("INSERT INTO api_db_connections "
                                + "(id, environment, name, db_host, db_port, db_name, db_user, db_password, projects, "
                                + "remark, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        newId(), env, name, row.get("db_host"), row.get("db_port"), dbName,
                        row.get("db_user"), row.get("db_password"), "", "从环境配置迁移过来，所有项目通用",
                        Timestamp.valueOf(LocalDateTime.now()));
            }
            jdbcTemplate.update("UPDATE api_test_envs SET db_host = NULL, db_port = NULL, db_name = NULL, "
                    + "db_user = NULL, db_password = NULL WHERE environment = ?", row.get("environment"));
            log.info("[db-conn] {} 的数据库配置已迁成连接 {}", env, name);
        }
    }

    /** 列表给前端：密码打码 */
    public List<ApiDbConnection> list(String environment) {
        List<ApiDbConnection> out = environment == null
                ? jdbcTemplate.query("SELECT * FROM api_db_connections ORDER BY environment, name", this::map)
                : findByEnv(TestEnvironment.normalize(environment));
        out.forEach(c -> {
            if (c.getDbPassword() != null && !c.getDbPassword().isBlank()) {
                c.setDbPassword(EnvVariableService.MASK);
            }
        });
        return out;
    }

    public ApiDbConnection get(String id) {
        List<ApiDbConnection> l = jdbcTemplate.query("SELECT * FROM api_db_connections WHERE id = ?", this::map, id);
        return l.isEmpty() ? null : l.get(0);
    }

    public ApiDbConnection save(String environment, ApiDbConnection body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String env = TestEnvironment.normalize(environment);
        String name = trim(body.getName());
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("名称必填，32 个字以内，只能是字母、数字、下划线、横线或中文（断言里按它引用）");
        }
        if (trim(body.getDbHost()) == null || trim(body.getDbName()) == null) {
            throw new IllegalArgumentException("库地址和库名必填");
        }
        ApiDbConnection old = body.getId() == null || body.getId().isBlank() ? null : get(body.getId());
        if (old != null && !env.equals(old.getEnvironment())) {
            throw new IllegalArgumentException("连接不属于这个环境");
        }
        for (ApiDbConnection c : findByEnv(env)) {
            if (c.getName().equalsIgnoreCase(name) && (old == null || !c.getId().equals(old.getId()))) {
                throw new IllegalArgumentException("这个环境已经有叫 " + name + " 的连接了");
            }
        }
        String password = body.getDbPassword();
        if (old != null && (password == null || EnvVariableService.MASK.equals(password))) {
            password = old.getDbPassword();
        }
        String projects;
        if (old == null) {
            projects = String.join(",", normalizeProjects(body.getProjects()));
        } else if (body.getProjects() == null) {
            projects = String.join(",", normalizeProjects(old.getProjects()));
        } else {
            projects = String.join(",", normalizeProjects(body.getProjects()));
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (old == null) {
            String id = newId();
            jdbcTemplate.update("INSERT INTO api_db_connections "
                            + "(id, environment, name, db_host, db_port, db_name, db_user, db_password, projects, "
                            + "remark, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, env, name, trim(body.getDbHost()), body.getDbPort(), trim(body.getDbName()),
                    trim(body.getDbUser()), password, projects, trim(body.getRemark()), now);
            return get(id);
        }
        jdbcTemplate.update("UPDATE api_db_connections SET name = ?, db_host = ?, db_port = ?, db_name = ?, "
                        + "db_user = ?, db_password = ?, projects = ?, remark = ?, updated_at = ? WHERE id = ?",
                name, trim(body.getDbHost()), body.getDbPort(), trim(body.getDbName()), trim(body.getDbUser()),
                password, projects, trim(body.getRemark()), now, old.getId());
        return get(old.getId());
    }

    public boolean delete(String environment, String id) {
        return jdbcTemplate.update("DELETE FROM api_db_connections WHERE id = ? AND environment = ?",
                id, TestEnvironment.normalize(environment)) > 0;
    }

    /**
     * 给一个项目勾选这个环境下要用的库。没勾上的从它的归属里拿掉；没勾任何库时回落到通用连接。
     */
    public List<ApiDbConnection> bindProject(String project, String environment, List<String> connectionIds) {
        String pj = trim(project);
        if (pj == null) {
            throw new IllegalArgumentException("项目编码必填");
        }
        String env = TestEnvironment.normalize(environment);
        LinkedHashSet<String> want = new LinkedHashSet<>();
        if (connectionIds != null) {
            for (String id : connectionIds) {
                String s = trim(id);
                if (s != null) {
                    want.add(s);
                }
            }
        }
        List<ApiDbConnection> inEnv = findByEnv(env);
        for (String id : want) {
            if (inEnv.stream().noneMatch(c -> id.equals(c.getId()))) {
                throw new IllegalArgumentException("连接不存在或不属于" + TestEnvironment.label(env) + "：" + id);
            }
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (ApiDbConnection c : inEnv) {
            List<String> next = toggleProject(c.getProjects(), pj, want.contains(c.getId()));
            if (!next.equals(normalizeProjects(c.getProjects()))) {
                jdbcTemplate.update(
                        "UPDATE api_db_connections SET projects = ?, updated_at = ? WHERE id = ?",
                        String.join(",", next), now, c.getId());
            }
        }
        return list(env);
    }

    static List<String> toggleProject(List<String> current, String project, boolean bind) {
        List<String> next = new ArrayList<>(normalizeProjects(current));
        boolean has = next.stream().anyMatch(p -> p.equalsIgnoreCase(project));
        if (bind && !has) {
            next.add(project);
        } else if (!bind && has) {
            next.removeIf(p -> p.equalsIgnoreCase(project));
        }
        return next;
    }

    public DbAssertRunner.Result check(String environment, String id) {
        ApiDbConnection c = get(id);
        if (c == null || !TestEnvironment.normalize(environment).equals(c.getEnvironment())) {
            return new DbAssertRunner.Result(false, null, List.of(), 0, null, "连接不存在");
        }
        return dbAssert.query(DbAssertRunner.Target.of(c), "SELECT 1");
    }

    /** 弹窗里未保存也能测；编辑时密码是掩码则用库里那份 */
    public DbAssertRunner.Probe probe(String environment, ApiDbConnection body) {
        if (body == null) {
            return DbAssertRunner.Probe.fail(0, "请求体为空");
        }
        TestEnvironment.normalize(environment);
        String password = body.getDbPassword();
        if (body.getId() != null && !body.getId().isBlank()
                && (password == null || EnvVariableService.MASK.equals(password))) {
            ApiDbConnection old = get(body.getId());
            if (old != null) {
                password = old.getDbPassword();
            }
        }
        return dbAssert.probe(new DbAssertRunner.Target(
                trim(body.getDbHost()), body.getDbPort(), trim(body.getDbName()),
                trim(body.getDbUser()), password));
    }

    public Map<String, Object> probeView(DbAssertRunner.Probe p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ok", p.ok());
        m.put("elapsedMs", p.elapsedMs());
        m.put("version", DbAssertRunner.formatVersion(p.product(), p.version()));
        m.put("readOnly", p.readOnly());
        m.put("message", p.message());
        return m;
    }

    /**
     * 项目在这个环境下能查的库：关联了就是关联的那几个，一个没关联就用通用库。
     * 和 {@link #pick} 不同，这里不要求唯一——读建表语句时挨个库找表就行。返回的连接带真实密码，只给服务端用。
     */
    public List<ApiDbConnection> candidates(String environment, String project) {
        return candidates(findByEnv(TestEnvironment.normalize(environment)), project);
    }

    static List<ApiDbConnection> candidates(List<ApiDbConnection> inEnv, String project) {
        String pj = project == null ? "" : project.trim();
        if (!pj.isEmpty()) {
            List<ApiDbConnection> bound = inEnv.stream()
                    .filter(c -> c.getProjects() != null && c.getProjects().stream().anyMatch(p -> p.equalsIgnoreCase(pj)))
                    .toList();
            if (!bound.isEmpty()) {
                return bound;
            }
        }
        return inEnv.stream().filter(ApiDbConnection::shared).toList();
    }

    /** 这条用例的这条断言该查哪个库 */
    public Pick pick(String environment, String project, String connName) {
        String env = TestEnvironment.normalize(environment);
        return pick(findByEnv(env), env, project, connName);
    }

    /**
     * 选库规则：
     * <ol>
     *   <li>断言写了 {@code conn} 就按名字找，找不到直接报——不退回去猜</li>
     *   <li>没写：项目只绑了一条就用它；绑了多条要求写明</li>
     *   <li>项目一条没绑：用通用连接（也只能有一条，多条同样要求写明）</li>
     * </ol>
     */
    static Pick pick(List<ApiDbConnection> inEnv, String env, String project, String connName) {
        String label = TestEnvironment.label(env);
        String want = trim(connName);
        if (want != null) {
            return inEnv.stream().filter(c -> c.getName().equalsIgnoreCase(want)).findFirst()
                    .map(Pick::ok)
                    .orElseGet(() -> Pick.fail(label + "没有叫「" + want + "」的数据库连接，去「项目管理」→「数据库」看看名字"));
        }
        String pj = project == null ? "" : project.trim().toLowerCase(Locale.ROOT);
        List<ApiDbConnection> bound = pj.isEmpty() ? List.of() : inEnv.stream()
                .filter(c -> c.getProjects() != null && c.getProjects().stream()
                        .anyMatch(p -> p.equalsIgnoreCase(pj)))
                .toList();
        List<ApiDbConnection> candidates = bound.isEmpty()
                ? inEnv.stream().filter(ApiDbConnection::shared).toList()
                : bound;
        if (candidates.size() == 1) {
            return Pick.ok(candidates.get(0));
        }
        String who = pj.isEmpty() ? "这条用例" : "项目 " + project;
        if (candidates.isEmpty()) {
            return Pick.fail(who + " 在" + label + "还没有可用的数据库连接，去「项目管理」→「数据库」配库并勾选关联");
        }
        String names = candidates.stream().map(ApiDbConnection::getName).collect(Collectors.joining("、"));
        return Pick.fail(who + " 在" + label + "能用的库有 " + candidates.size() + " 个（" + names
                + "），在断言上选一下查哪个库");
    }

    private List<ApiDbConnection> findByEnv(String env) {
        return jdbcTemplate.query("SELECT * FROM api_db_connections WHERE environment = ? ORDER BY name",
                this::map, env);
    }

    private ApiDbConnection map(ResultSet rs, int i) throws SQLException {
        ApiDbConnection c = new ApiDbConnection();
        c.setId(rs.getString("id"));
        c.setEnvironment(rs.getString("environment"));
        c.setName(rs.getString("name"));
        c.setDbHost(rs.getString("db_host"));
        int port = rs.getInt("db_port");
        c.setDbPort(rs.wasNull() ? null : port);
        c.setDbName(rs.getString("db_name"));
        c.setDbUser(rs.getString("db_user"));
        c.setDbPassword(rs.getString("db_password"));
        String pj = rs.getString("projects");
        c.setProjects(normalizeProjects(pj == null ? List.of() : Arrays.asList(pj.split(","))));
        c.setRemark(rs.getString("remark"));
        Timestamp t = rs.getTimestamp("updated_at");
        c.setUpdatedAt(t == null ? null : t.toLocalDateTime());
        return c;
    }

    static List<String> normalizeProjects(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String p : raw) {
                String s = trim(p);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String newId() {
        return "dbc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
