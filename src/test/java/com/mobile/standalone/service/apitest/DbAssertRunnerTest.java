package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据库断言的安全边界。
 * <p>
 * 这是给测试同学用的输入框，不能变成执行任意 SQL 的口子。
 * 要防的不是黑客，是一条手滑写成 UPDATE 的断言把测试库改掉——
 * 那种事故没有任何线索可查，等发现时已经不知道哪些数据被动过了。
 */
class DbAssertRunnerTest {

    private final DbAssertRunner runner = new DbAssertRunner();

    @Test
    void 只允许单条SELECT() {
        assertThat(runner.sanitizeSelect("SELECT COUNT(*) FROM orders WHERE uid=1 LIMIT 1"))
                .isEqualTo("SELECT COUNT(*) FROM orders WHERE uid=1 LIMIT 1");
    }

    @Test
    void 没写LIMIT就补一个() {
        // 一条误写的全表扫描能把测试库拖垮，而写的人完全不会意识到
        assertThat(runner.sanitizeSelect("SELECT id FROM orders")).endsWith(" LIMIT 100");
    }

    @Test
    void 写操作一律拒绝() {
        for (String sql : new String[]{
                "UPDATE orders SET status=1",
                "DELETE FROM orders",
                "DROP TABLE orders",
                "TRUNCATE orders",
                "INSERT INTO orders VALUES (1)"}) {
            assertThatRejected(sql);
        }
    }

    @Test
    void 多条SQL拒绝() {
        // 结尾的分号是允许的（会被去掉），中间出现说明想执行第二条
        assertThat(runner.sanitizeSelect("SELECT 1;")).isEqualTo("SELECT 1 LIMIT 100");
        assertThatRejected("SELECT 1; DROP TABLE orders");
    }

    @Test
    void 注释拒绝() {
        // 注释是绕过关键字检查的常规手法
        assertThatRejected("SELECT 1 -- DROP TABLE orders");
        assertThatRejected("SELECT /* drop */ 1");
        assertThatRejected("SELECT 1 # x");
    }

    @Test
    void 系统表和文件读写拒绝() {
        assertThatRejected("SELECT * FROM mysql.user");
        assertThatRejected("SELECT load_file('/etc/passwd')");
        assertThatRejected("SELECT 1 INTO OUTFILE '/tmp/x'");
    }

    @Test
    void 没配库时说清楚去哪配而不是抛连接异常() {
        ApiTestEnv env = new ApiTestEnv();
        env.setEnvironment("TEST");

        var r = runner.query(env, "SELECT 1");

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("还没配");
        assertThat(runner.configured(env)).isFalse();
    }

    private void assertThatRejected(String sql) {
        assertThat(catchError(sql))
                .as("这条本该被拒绝：%s", sql)
                .isNotNull();
    }

    private String catchError(String sql) {
        try {
            runner.sanitizeSelect(sql);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
