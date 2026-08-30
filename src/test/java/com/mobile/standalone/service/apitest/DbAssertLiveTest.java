package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对着真的 MySQL 跑一遍。
 * <p>
 * 安全边界的单测只证明了「这些 SQL 被拒绝」，证明不了「放行的 SQL 真能查出正确的值」——
 * 而后者才是数据库断言的全部意义。默认不跑（本机没库时会红一片），
 * 加 {@code -Ddb.live=true} 手动跑。
 */
@EnabledIfSystemProperty(named = "db.live", matches = "true")
class DbAssertLiveTest {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/?useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASS = "1qaz!QAZ9";
    private static final String DB = "ms_dbassert_live";

    private final DbAssertRunner runner = new DbAssertRunner();

    @BeforeAll
    static void setUp() throws Exception {
        exec("DROP DATABASE IF EXISTS " + DB,
                "CREATE DATABASE " + DB + " DEFAULT CHARACTER SET utf8mb4",
                "CREATE TABLE " + DB + ".orders (id INT PRIMARY KEY, uid INT, status INT)",
                "INSERT INTO " + DB + ".orders VALUES (1,7,0),(2,7,1),(3,9,0)");
    }

    @AfterAll
    static void tearDown() throws Exception {
        exec("DROP DATABASE IF EXISTS " + DB);
    }

    private ApiTestEnv env() {
        ApiTestEnv e = new ApiTestEnv();
        e.setEnvironment("TEST");
        e.setDbHost("127.0.0.1");
        e.setDbPort(3306);
        e.setDbName(DB);
        e.setDbUser(USER);
        e.setDbPassword(PASS);
        return e;
    }

    @Test
    void 单行单列直接给标量() {
        // 断言里写 count(*) 是最常见的写法，它必须能直接和期望值比
        var r = runner.query(env(), "SELECT COUNT(*) FROM orders WHERE uid=7");

        assertThat(r.ok()).isTrue();
        assertThat(String.valueOf(r.value())).isEqualTo("2");
    }

    @Test
    void 多行时给行数并回带前几行() {
        var r = runner.query(env(), "SELECT id, uid FROM orders");

        assertThat(r.ok()).isTrue();
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(String.valueOf(r.value())).isEqualTo("3");
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows().get(0)).containsKeys("id", "uid");
    }

    @Test
    void 查不到时给0而不是报错() {
        // 「一条都没有」本身就是个有效的断言结果（比如「删除后应该查不到」）
        var r = runner.query(env(), "SELECT COUNT(*) FROM orders WHERE uid=999");

        assertThat(r.ok()).isTrue();
        assertThat(String.valueOf(r.value())).isEqualTo("0");
    }

    @Test
    void 写操作到了驱动这层也执行不了() {
        // 校验那层已经拦了，这里验的是第二道保险：万一哪天校验被绕过
        var r = runner.query(env(), "SELECT 1; UPDATE orders SET status=9");

        assertThat(r.ok()).isFalse();
        // 数据没被动过才算真的挡住了
        var check = runner.query(env(), "SELECT COUNT(*) FROM orders WHERE status=9");
        assertThat(String.valueOf(check.value())).isEqualTo("0");
    }

    @Test
    void 表不存在时把错误原样给人看() {
        var r = runner.query(env(), "SELECT COUNT(*) FROM no_such_table");

        assertThat(r.ok()).isFalse();
        // 「查库失败」不能被吞掉当成断言通过——查不到就等于没验
        assertThat(r.error()).contains("no_such_table");
    }

    private static void exec(String... sqls) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        }
    }
}
