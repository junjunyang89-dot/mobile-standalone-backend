package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiTestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务成功码断言与 extract。
 * <p>
 * 这组存在的理由：批量导进来的 257 条 data-center 用例，断言清一色只有
 * {@code [{"type":"status","equals":200}]}。而这几个后端统一用
 * {@code ResponseResult{code,message,result}} 包一层，<b>业务失败也是 HTTP 200</b>——
 * 返回 {@code {code:5009001,message:"服务调用失败"}} 照样判通过。
 * <p>
 * 跑出来会是一份 100% 通过的报告。这比没有报告更糟：它会让人相信接口是好的。
 */
class ApiTestBizCodeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 只需要 get(code)，构造参数传 null——测试不会走到 mapper */
    private static class FakeProjectService extends ApiProjectService {
        final Map<String, ApiProject> rows = new LinkedHashMap<>();

        FakeProjectService() {
            super(null, null);
        }

        @Override
        public ApiProject get(String code) {
            return rows.get(code);
        }

        void put(String code, String path, String value) {
            ApiProject p = new ApiProject();
            p.setCode(code);
            p.setSuccessCodePath(path);
            p.setSuccessCodeValue(value);
            rows.put(code, p);
        }
    }

    private FakeProjectService projects;
    private ApiTestRunnerService runner;

    @BeforeEach
    void setUp() {
        projects = new FakeProjectService();
        // 其余依赖这组用例走不到，传 null 即可
        runner = new ApiTestRunnerService(null, null, null, null, null, projects, null,
                new com.mobile.standalone.service.apitest.DbAssertRunner(), null, null);
    }

    private static ApiTestCase c(String project) {
        ApiTestCase x = new ApiTestCase();
        x.setId("atc-1");
        x.setProject(project);
        return x;
    }

    private static List<JsonNode> parse(String json) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        JSON.readTree(json).forEach(out::add);
        return out;
    }

    // ==================== 自动补业务码断言 ====================

    @Test
    void 项目配了成功码就自动补一条() throws Exception {
        projects.put("data-center", "$.code", "0");

        List<JsonNode> arr = parse(runner.withBizCodeAssertion(
                c("data-center"), "[{\"type\":\"status\",\"equals\":200}]"));

        assertEquals(2, arr.size(), "原来那条要保留，只是在后面追加");
        JsonNode added = arr.get(1);
        assertEquals("jsonPath", added.get("type").asText());
        assertEquals("$.code", added.get("path").asText());
        assertTrue(added.get("equals").isNumber(), "数字码要按数字比，否则 \"0\" 和 0 判不等");
        assertEquals(0, added.get("equals").asInt());
    }

    /** 导进来的用例断言就长这样，这条最贴近真实数据 */
    @Test
    void 只有status断言的用例补完能挡住业务失败() throws Exception {
        projects.put("data-center", "$.code", "0");
        String filled = runner.withBizCodeAssertion(c("data-center"), "[{\"type\":\"status\",\"equals\":200}]");

        List<String> msgs = new ArrayList<>();
        boolean ok = evaluate(filled, 200, "{\"code\":5009001,\"message\":\"服务调用失败服务接口code异常\"}", msgs);

        assertFalse(ok, "HTTP 200 + 业务码 5009001 必须判失败，否则这条用例等于没跑");
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("FAIL") && m.contains("$.code")), msgs.toString());
    }

    @Test
    void 业务成功时照常通过() throws Exception {
        projects.put("data-center", "$.code", "0");
        String filled = runner.withBizCodeAssertion(c("data-center"), "[{\"type\":\"status\",\"equals\":200}]");

        assertTrue(evaluate(filled, 200, "{\"code\":0,\"message\":\"success\",\"result\":{}}", new ArrayList<>()));
    }

    /** 用例自己就在测某个错误码时，别再补一条要求成功的——那样它永远过不了 */
    @Test
    void 用例已断言同一路径时不重复补() throws Exception {
        projects.put("data-center", "$.code", "0");

        List<JsonNode> arr = parse(runner.withBizCodeAssertion(
                c("data-center"), "[{\"type\":\"jsonPath\",\"path\":\"$.code\",\"equals\":5009001}]"));

        assertEquals(1, arr.size(), "刻意测错误码的用例不能被强行改成要求成功");
        assertEquals(5009001, arr.get(0).get("equals").asInt());
    }

    @Test
    void 项目没配成功码时原样不动() {
        projects.put("other", null, null);
        String in = "[{\"type\":\"status\",\"equals\":200}]";

        assertEquals(in, runner.withBizCodeAssertion(c("other"), in));
        assertEquals(in, runner.withBizCodeAssertion(c("不存在的项目"), in), "查不到项目也不能炸");
    }

    /** 非数字码（有些服务用 "SUCCESS"）按字符串比 */
    @Test
    void 非数字成功码按字符串比() throws Exception {
        projects.put("p", "$.status", "SUCCESS");

        JsonNode added = parse(runner.withBizCodeAssertion(c("p"), "[]")).get(0);

        assertTrue(added.get("equals").isTextual());
        assertEquals("SUCCESS", added.get("equals").asText());
    }

    // ==================== extract ====================

    @Test
    void 抽出来的值写进本轮上下文() {
        ApiTestCase x = c("p");
        x.setExtractJson("{\"token\":\"$.result.token\",\"uid\":\"$.result.userId\"}");
        Map<String, String> runtime = new LinkedHashMap<>();
        List<String> msgs = new ArrayList<>();

        runner.extractInto(x, "{\"result\":{\"token\":\"tk-9\",\"userId\":42}}", runtime, msgs);

        assertEquals("tk-9", runtime.get("token"));
        assertEquals("42", runtime.get("uid"));
    }

    /**
     * 抽不到不算用例失败，但必须留一行。不写的话，后面那条引用 {{token}} 的用例
     * 会报「变量取不到值」，人得反向猜是哪一步没抽到。
     */
    @Test
    void 抽不到时留一行提示而不是静默() {
        ApiTestCase x = c("p");
        x.setExtractJson("{\"token\":\"$.result.token\"}");
        Map<String, String> runtime = new LinkedHashMap<>();
        List<String> msgs = new ArrayList<>();

        runner.extractInto(x, "{\"result\":{}}", runtime, msgs);

        assertTrue(runtime.isEmpty());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("token") && m.contains("取不到")), msgs.toString());
    }

    @Test
    void 响应不是JSON时不抛异常() {
        ApiTestCase x = c("p");
        x.setExtractJson("{\"token\":\"$.token\"}");
        List<String> msgs = new ArrayList<>();

        runner.extractInto(x, "<html>502 Bad Gateway</html>", new LinkedHashMap<>(), msgs);

        assertTrue(msgs.stream().anyMatch(m -> m.contains("取不到")), msgs.toString());
    }

    @Test
    void 没配extract时什么都不做() {
        List<String> msgs = new ArrayList<>();

        runner.extractInto(c("p"), "{\"a\":1}", new LinkedHashMap<>(), msgs);

        assertTrue(msgs.isEmpty());
    }

    /** 反射调私有的断言求值，免得为测试把可见性放开 */
    private boolean evaluate(String assertions, int status, String body, List<String> msgs) throws Exception {
        var m = ApiTestRunnerService.class.getDeclaredMethod(
                "evaluateAssertions", String.class, int.class, String.class, List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(runner, assertions, status, body, msgs);
    }
}
