package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTestBurstTest {

    private static ApiTestCase base(String method, String query, String body) {
        ApiTestCase c = new ApiTestCase();
        c.setId("c1");
        c.setMethod(method);
        c.setPath("/x");
        c.setQuery(query);
        c.setBody(body);
        return c;
    }

    @Test
    void 参数在query里就改query_不动原用例() {
        ApiTestCase b = base("POST", "{\"nick\":\"a\"}", "{\"id\":1}");
        ApiTestCase c = ApiTestRunnerService.withParam(b, "nick", "qa_2");

        assertThat(c.getQuery()).isEqualTo("{\"nick\":\"qa_2\"}");
        assertThat(c.getBody()).isEqualTo("{\"id\":1}");
        assertThat(b.getQuery()).isEqualTo("{\"nick\":\"a\"}");
    }

    @Test
    void POST且query里没有就改body_数字字段保持数字() {
        ApiTestCase c = ApiTestRunnerService.withParam(base("POST", "", "{\"id\":1,\"nick\":\"a\"}"), "id", "7");
        assertThat(c.getBody()).isEqualTo("{\"id\":7,\"nick\":\"a\"}");

        ApiTestCase d = ApiTestRunnerService.withParam(base("POST", null, null), "nick", "b");
        assertThat(d.getBody()).isEqualTo("{\"nick\":\"b\"}");
    }

    @Test
    void GET都没有就加到query() {
        ApiTestCase c = ApiTestRunnerService.withParam(base("GET", null, null), "page", "2");
        assertThat(c.getQuery()).isEqualTo("{\"page\":\"2\"}");
    }

    @Test
    void 全通过且换了值_提示去对库和缓存() {
        var shots = List.of(
                new ApiTestRunnerService.BurstShot(1, "a", "PASSED", 200, 10L, "{}", null),
                new ApiTestRunnerService.BurstShot(2, "b", "PASSED", 200, 12L, "{}", null));
        String v = ApiTestRunnerService.burstVerdict(shots, 1, "nick");
        assertThat(v).contains("通过 2").contains("库和缓存").contains("nick");
    }

    @Test
    void 有成功有失败_提示看锁和唯一索引() {
        var shots = List.of(
                new ApiTestRunnerService.BurstShot(1, null, "PASSED", 200, 10L, "{\"code\":0}", null),
                new ApiTestRunnerService.BurstShot(2, null, "FAILED", 200, 12L, "{\"code\":1}", "x"));
        assertThat(ApiTestRunnerService.burstVerdict(shots, 2, null)).contains("失败 1").contains("唯一索引");
    }
}
