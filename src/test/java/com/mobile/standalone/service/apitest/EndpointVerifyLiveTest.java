package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestCase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑完回写「验过没有」，对着真库验一遍。
 * <p>
 * 这条链路全是 SQL，单测把 JdbcTemplate mock 掉就只是在验我自己写的字符串。
 * 加 {@code -Ddb.live=true} 跑。
 */
@EnabledIfSystemProperty(named = "db.live", matches = "true")
class EndpointVerifyLiveTest {

    private static final String DB = "ms_verify_live";
    private static JdbcTemplate jdbc;
    private EndpointVerifyService service;

    @BeforeEach
    void setUp() {
        var admin = new JdbcTemplate(ds(""));
        admin.execute("DROP DATABASE IF EXISTS " + DB);
        admin.execute("CREATE DATABASE " + DB + " DEFAULT CHARACTER SET utf8mb4");

        jdbc = new JdbcTemplate(ds(DB));
        jdbc.execute("""
                CREATE TABLE requirement_endpoints (
                    id VARCHAR(64) PRIMARY KEY,
                    requirement_no VARCHAR(64) NOT NULL,
                    project VARCHAR(64) DEFAULT NULL,
                    endpoint_id VARCHAR(64) DEFAULT NULL,
                    http_method VARCHAR(16) NOT NULL,
                    path VARCHAR(512) NOT NULL,
                    source VARCHAR(16) NOT NULL DEFAULT 'SYNC'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("INSERT INTO requirement_endpoints VALUES "
                + "('r1','APP-8999',NULL,'ep-1','GET','/gld/deadlock/friend/list','CAPTURE'),"
                + "('r2','APP-8999',NULL,NULL,'GET','/gld/deadlock/user/match/cnt','CAPTURE')");

        service = new EndpointVerifyService(jdbc);
        service.ensureSchema();
    }

    @AfterAll
    static void tearDown() {
        new JdbcTemplate(ds("")).execute("DROP DATABASE IF EXISTS " + DB);
    }

    @Test
    void 跑通了就标已验证() {
        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), true)));

        assertThat(verifiedOf("r1")).isEqualTo(1);
        assertThat(statusOf("r1")).isEqualTo("PASSED");
    }

    @Test
    void 验过之后跑挂要退回待测() {
        // 只升不降的指标绿了之后就不再有信息量——回归挂了必须能看出来
        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), true)));
        assertThat(verifiedOf("r1")).isEqualTo(1);

        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), false)));

        assertThat(verifiedOf("r1")).isZero();
        assertThat(statusOf("r1")).isEqualTo("FAILED");
    }

    @Test
    void 同一个接口有一条用例挂了就不算验过() {
        service.applyRunOutcome(List.of(
                new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), true),
                new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), false)));

        assertThat(verifiedOf("r1")).isZero();
    }

    @Test
    void 没有endpointId时按方法加路径匹配() {
        // 抓包挂上来的老数据没有 endpoint_id，不能因此永远验不上
        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(
                c(null, "GET", "/gld/deadlock/user/match/cnt?page=1"), true)));

        assertThat(verifiedOf("r2")).isEqualTo(1);
    }

    @Test
    void 有endpointId的不会被同名路径误伤() {
        // r1 有 endpoint_id，按签名那一路必须跳过它，否则别的项目下同名路径会互相串味
        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(
                c(null, "GET", "/gld/deadlock/friend/list"), true)));

        assertThat(verifiedOf("r1")).isZero();
    }

    @Test
    void 需求单汇总数得对() {
        service.applyRunOutcome(List.of(new EndpointVerifyService.CaseOutcome(c("ep-1", "GET", "/x"), true)));

        var s = service.summaryOf("APP-8999");
        assertThat(s.get("total")).isEqualTo(2);
        assertThat(s.get("verified")).isEqualTo(1L);
    }

    private static ApiTestCase c(String endpointId, String method, String path) {
        ApiTestCase x = new ApiTestCase();
        x.setEndpointId(endpointId);
        x.setMethod(method);
        x.setPath(path);
        return x;
    }

    private int verifiedOf(String id) {
        Integer v = jdbc.queryForObject(
                "SELECT verified FROM requirement_endpoints WHERE id = ?", Integer.class, id);
        return v == null ? 0 : v;
    }

    private String statusOf(String id) {
        return jdbc.queryForObject(
                "SELECT last_run_status FROM requirement_endpoints WHERE id = ?", String.class, id);
    }

    private static DriverManagerDataSource ds(String db) {
        var d = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/" + db
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        d.setUsername("root");
        d.setPassword("1qaz!QAZ9");
        return d;
    }
}
