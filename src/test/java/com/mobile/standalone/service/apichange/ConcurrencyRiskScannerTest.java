package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyRiskScannerTest {

    private static ApiEndpoint ep(String method, String path) {
        return new ApiEndpoint(method, path, "handler", null, "摘要", null, List.of(), Set.of());
    }

    private static final String DDL_UNIQUE = """
            CREATE TABLE user_task (
              id BIGINT NOT NULL AUTO_INCREMENT,
              user_id BIGINT NOT NULL,
              task_id BIGINT NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_user_task (user_id, task_id)
            )""";

    private static final String DDL_COUNTER = """
            CREATE TABLE user_wallet (
              id BIGINT NOT NULL,
              balance DECIMAL(18,2) NOT NULL DEFAULT 0,
              login_times INT NOT NULL DEFAULT 0,
              nickname VARCHAR(64) DEFAULT NULL,
              PRIMARY KEY (id)
            )""";

    /** GET 的并发是性能话题，不该出现在并发正确性清单里 */
    @Test
    void scan_ignoresReadOnlyEndpoints() {
        assertThat(ConcurrencyRiskScanner.scan(
                ep("GET", "/user/task"), List.of("user_task"),
                Map.of("user_task", DDL_UNIQUE), List.of())).isEmpty();
    }

    @Test
    void scan_flagsDuplicateSubmitOnUniqueKey() {
        List<ConcurrencyRiskScanner.Risk> out = ConcurrencyRiskScanner.scan(
                ep("POST", "/user/task/claim"), List.of("user_task"),
                Map.of("user_task", DDL_UNIQUE), List.of());

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("DUPLICATE_SUBMIT");
            assertThat(r.level()).isEqualTo("RISK");
            assertThat(r.basis()).contains("user_id, task_id");
            // 只报风险不说怎么测等于制造焦虑，这一栏要给出可执行的步骤和判据
            assertThat(r.howToTest()).contains("线程同时").contains("1 条成功");
        });
    }

    @Test
    void scan_flagsLostUpdateOnCounterColumns() {
        List<ConcurrencyRiskScanner.Risk> out = ConcurrencyRiskScanner.scan(
                ep("POST", "/wallet/recharge"), List.of("user_wallet"),
                Map.of("user_wallet", DDL_COUNTER), List.of());

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("LOST_UPDATE");
            assertThat(r.basis()).contains("balance").contains("login_times");
            assertThat(r.howToTest()).contains("+20");
        });
    }

    /** 只有数字列才是计数面；nickname 这种不能因为出现在同一张表就被算进去 */
    @Test
    void scan_ignoresNonNumericColumns() {
        String ddl = """
                CREATE TABLE t (
                  total_desc VARCHAR(64) DEFAULT NULL,
                  name VARCHAR(64) DEFAULT NULL
                )""";
        assertThat(ConcurrencyRiskScanner.scan(
                ep("POST", "/t"), List.of("t"), Map.of("t", ddl), List.of())).isEmpty();
    }

    /**
     * 没有建表语句就没有依据。宁可漏也不错报——按表名猜出来的并发风险
     * 验起来最费时间，还最容易是假的。
     */
    @Test
    void scan_skipsTablesWithoutDdlRatherThanGuessing() {
        assertThat(ConcurrencyRiskScanner.scan(
                ep("POST", "/order/create"), List.of("order_stock"),
                Map.of(), List.of())).isEmpty();
    }

    @Test
    void scan_reportsCacheRaceForWriteEndpointsTouchingRedis() {
        List<ConcurrencyRiskScanner.Risk> out = ConcurrencyRiskScanner.scan(
                ep("PUT", "/user/profile"), List.of(), Map.of(),
                List.of("user:profile:", "user:stat:"));

        assertThat(out).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("CACHE_RACE");
            assertThat(r.level()).isEqualTo("INFO");
            assertThat(r.basis()).contains("user:profile:");
        });
    }

    /** 一个接口可能同时踩多个面，不能只报第一个 */
    @Test
    void scan_reportsEveryRiskOnOneEndpoint() {
        List<ConcurrencyRiskScanner.Risk> out = ConcurrencyRiskScanner.scan(
                ep("POST", "/x"), List.of("user_task", "user_wallet"),
                Map.of("user_task", DDL_UNIQUE, "user_wallet", DDL_COUNTER),
                List.of("cache:x"));

        assertThat(out).extracting(ConcurrencyRiskScanner.Risk::kind)
                .containsExactlyInAnyOrder("DUPLICATE_SUBMIT", "LOST_UPDATE", "CACHE_RACE");
    }

    @Test
    void scan_handlesDeleteAndPatchAsWrites() {
        assertThat(ConcurrencyRiskScanner.scan(ep("DELETE", "/x"), List.of("user_task"),
                Map.of("user_task", DDL_UNIQUE), List.of())).isNotEmpty();
        assertThat(ConcurrencyRiskScanner.scan(ep("PATCH", "/x"), List.of("user_task"),
                Map.of("user_task", DDL_UNIQUE), List.of())).isNotEmpty();
    }
}
