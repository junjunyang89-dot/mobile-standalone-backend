package com.mobile.standalone.service;

import com.mobile.standalone.service.apitest.RequirementCaseGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * query 这一列的格式。
 * <p>
 * 写错格式的后果不是「参数没带上」，而是执行时抛
 * {@code Unrecognized token 'steamId'} —— 一句 Jackson 的话，
 * 看的人完全猜不到是自己格式错了，只会以为接口坏了。
 */
class QueryFormatTest {

    @Test
    void 生成的用例把query存成JSON() throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("steamId", "76561199278980753");
        q.put("global", "true");

        String stored = RequirementCaseGenerator.toQueryString(q);

        assertThat(stored).startsWith("{").contains("\"steamId\"");
        // 存进去的要能被执行器读回来
        assertThat(ApiTestRunnerService.readQuery(stored))
                .containsEntry("steamId", "76561199278980753")
                .containsEntry("global", "true");
    }

    @Test
    void 人从地址栏粘的查询串也要认() throws Exception {
        // 只认 JSON 的话，粘一串过来就炸，而报错完全指不到原因
        assertThat(ApiTestRunnerService.readQuery("steamId=76561199278980753&global=true"))
                .containsEntry("steamId", "76561199278980753")
                .containsEntry("global", "true");
    }

    @Test
    void 带问号前缀的也认() throws Exception {
        assertThat(ApiTestRunnerService.readQuery("?a=1&b=2"))
                .containsEntry("a", "1")
                .containsEntry("b", "2");
    }

    @Test
    void 没有等号的参数保留成空值() throws Exception {
        // ?flag 照样是请求的一部分
        assertThat(ApiTestRunnerService.readQuery("a=1&flag&b=2")).containsEntry("flag", "");
    }

    @Test
    void 空的返回空不抛异常() throws Exception {
        assertThat(ApiTestRunnerService.readQuery(null)).isEmpty();
        assertThat(ApiTestRunnerService.readQuery("  ")).isEmpty();
    }

    @Test
    void 拼出来的URL两种写法一致() throws Exception {
        String fromJson = ApiTestRunnerService.buildUrl(
                "https://gw.test.com", "/a/b", "{\"x\":\"1\"}");
        String fromString = ApiTestRunnerService.buildUrl(
                "https://gw.test.com", "/a/b", "x=1");

        assertThat(fromJson).isEqualTo(fromString).isEqualTo("https://gw.test.com/a/b?x=1");
    }
}
