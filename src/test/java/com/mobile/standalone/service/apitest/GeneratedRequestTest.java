package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成的用例要带上抓包的真实请求。
 * <p>
 * 只带一个 Accept 是跑不起来的：网关要 appId、platform、appversion，缺一个就直接回
 * 「Service is busy」——而人看到的是一条红用例，会以为是接口坏了或者断言写错了。
 * <p>
 * 但 <b>token 不能写进用例</b>：它会过期，写死的话过期那天所有用例集体变红，
 * 而红的原因和接口毫无关系。
 */
class GeneratedRequestTest {

    private Map<String, String> captured() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("appId", "1");
        h.put("platform", "ios");
        h.put("appversion", "1.3.0");
        h.put("token", "b0c061f13dd0b87d2621c186ca9e1d00da459291");
        h.put("Authorization", "Bearer abc");
        h.put("Cookie", "sid=xyz");
        h.put("Host", "gateway-qa.arca42.com");
        h.put("Content-Length", "0");
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    @Test
    void token和鉴权头一律不落库() {
        var kept = ApiEndpointService.usefulHeaders(captured());

        assertThat(kept).doesNotContainKeys("token", "Authorization", "Cookie");
    }

    @Test
    void 传输层的头也不留() {
        // Host / Content-Length / Accept-Encoding 换个客户端就变，留着只会误导
        var kept = ApiEndpointService.usefulHeaders(captured());

        assertThat(kept).doesNotContainKeys("Host", "Content-Length", "Accept-Encoding");
    }

    @Test
    void 业务头必须留下() {
        // 正是这几个决定了请求能不能过网关
        var kept = ApiEndpointService.usefulHeaders(captured());

        assertThat(kept).containsEntry("appId", "1")
                .containsEntry("platform", "ios")
                .containsEntry("appversion", "1.3.0");
    }

    @Test
    void 生成的请求头里有业务头没有token() {
        String json = RequirementCaseGenerator.headersJson(
                ApiEndpointService.usefulHeaders(captured()));

        assertThat(json).contains("appId").contains("platform").contains("Accept");
        assertThat(json).doesNotContain("b0c061f13dd0b87d2621c186ca9e1d00da459291");
    }

    @Test
    void 没有抓包头时至少给一个Accept() {
        assertThat(RequirementCaseGenerator.headersJson(null)).contains("Accept");
        assertThat(RequirementCaseGenerator.headersJson(Map.of())).contains("Accept");
    }

    @Test
    void query存成JSON而不是查询串() {
        // 这一列的既定格式是 JSON。写成 a=1&b=2 的话执行时会被当 JSON 解析，
        // 报出来的是「Unrecognized token 'steamId'」，看的人只会以为接口坏了
        Map<String, String> q = new LinkedHashMap<>();
        q.put("steamId", "103561140");
        q.put("mode", "4");

        assertThat(RequirementCaseGenerator.toQueryString(q))
                .isEqualTo("{\"steamId\":\"103561140\",\"mode\":\"4\"}");
    }

    @Test
    void 解析query时没有等号的也留着() {
        // ?flag 这种照样是请求的一部分，重放时要带上
        var parsed = ApiEndpointService.parseQuery("steamId=1&flag&mode=4");

        assertThat(parsed).containsEntry("steamId", "1")
                .containsEntry("flag", "")
                .containsEntry("mode", "4");
    }
}
