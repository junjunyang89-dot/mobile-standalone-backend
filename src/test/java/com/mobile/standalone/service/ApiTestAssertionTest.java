package com.mobile.standalone.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断言类型与算子。
 * <p>
 * 挑出来单测的都是<b>容易写出「假通过」</b>的边界：字段没返回冒充成 0、
 * 响应头大小写、数组长度对上了但类型不是数组。这类错误不会报错，只会让用例悄悄失去意义。
 */
class ApiTestAssertionTest {

    private ApiTestRunnerService runner;

    @BeforeEach
    void setUp() {
        runner = new ApiTestRunnerService(null, null, null, null, null, null, null,
                new com.mobile.standalone.service.apitest.DbAssertRunner(), null, null);
    }

    private boolean check(String assertions, int status, String body,
                          Map<String, List<String>> headers, long elapsedMs) throws Exception {
        var m = ApiTestRunnerService.class.getDeclaredMethod(
                "evaluateAssertions", String.class, int.class, String.class, Map.class, long.class,
                ApiTestRunnerService.DbContext.class, List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(runner, assertions, status, body, headers, elapsedMs,
                ApiTestRunnerService.DbContext.empty(), new ArrayList<String>());
    }

    private boolean check(String assertions, String body) throws Exception {
        return check(assertions, 200, body, Map.of(), 0L);
    }

    // ==================== jsonPath 算子 ====================

    @Test
    void notEquals() throws Exception {
        assertTrue(check("[{\"type\":\"jsonPath\",\"path\":\"$.code\",\"notEquals\":1}]", "{\"code\":0}"));
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.code\",\"notEquals\":0}]", "{\"code\":0}"));
    }

    @Test
    void contains() throws Exception {
        assertTrue(check("[{\"type\":\"jsonPath\",\"path\":\"$.msg\",\"contains\":\"成功\"}]",
                "{\"msg\":\"操作成功\"}"));
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.msg\",\"contains\":\"成功\"}]",
                "{\"msg\":\"失败\"}"));
    }

    @Test
    void 大于小于() throws Exception {
        assertTrue(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"gt\":0}]", "{\"total\":5}"));
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"gt\":10}]", "{\"total\":5}"));
        assertTrue(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"lt\":10}]", "{\"total\":5}"));
    }

    /**
     * 本组最要紧的一条。字段根本没返回时，如果把 null 当 0 参与比较，
     * {@code lt: 10} 会通过——用例绿着，而实际上后端把这个字段整个删了。
     */
    @Test
    void 字段没返回时不能当成0() throws Exception {
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"lt\":10}]", "{}"),
                "字段缺失必须判失败，否则「后端删了字段」会伪装成「值很小」");
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"gt\":-1}]", "{}"));
    }

    @Test
    void 非数字类型不参与大小比较() throws Exception {
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.total\",\"gt\":0}]", "{\"total\":\"5\"}"),
                "字符串 \"5\" 不该当数字比——类型从 int 改成 String 正是要抓的那类变更");
    }

    @Test
    void 数组长度() throws Exception {
        assertTrue(check("[{\"type\":\"jsonPath\",\"path\":\"$.list\",\"size\":2}]",
                "{\"list\":[1,2]}"));
        assertFalse(check("[{\"type\":\"jsonPath\",\"path\":\"$.list\",\"size\":2}]",
                "{\"list\":{\"a\":1,\"b\":2}}"), "对象有两个 key 也不算数组长度为 2");
    }

    // ==================== 响应头 ====================

    @Test
    void 响应头大小写不敏感() throws Exception {
        Map<String, List<String>> h = Map.of("content-type", List.of("application/json;charset=UTF-8"));

        assertTrue(check("[{\"type\":\"header\",\"name\":\"Content-Type\",\"contains\":\"json\"}]",
                200, "{}", h, 0L), "HTTP 头本来就大小写不敏感，别让人被这个绊住");
    }

    @Test
    void 响应头存在性() throws Exception {
        Map<String, List<String>> h = Map.of("set-cookie", List.of("sid=1"));

        assertTrue(check("[{\"type\":\"header\",\"name\":\"Set-Cookie\",\"exists\":true}]", 200, "{}", h, 0L));
        assertTrue(check("[{\"type\":\"header\",\"name\":\"X-Nope\",\"exists\":false}]", 200, "{}", h, 0L));
        assertFalse(check("[{\"type\":\"header\",\"name\":\"X-Nope\",\"exists\":true}]", 200, "{}", h, 0L));
    }

    // ==================== 响应耗时 ====================

    @Test
    void 耗时超阈值判失败() throws Exception {
        assertTrue(check("[{\"type\":\"responseTime\",\"lessThan\":1000}]", 200, "{}", Map.of(), 300L));
        assertFalse(check("[{\"type\":\"responseTime\",\"lessThan\":1000}]", 200, "{}", Map.of(), 1500L));
    }

    /** 没填阈值当成不限制，不能反过来判所有请求都失败 */
    @Test
    void 耗时没填阈值时不生效() throws Exception {
        assertTrue(check("[{\"type\":\"responseTime\"}]", 200, "{}", Map.of(), 9999L));
    }

    // ==================== 兜底 ====================

    @Test
    void 空断言时默认要求2xx() throws Exception {
        assertTrue(check("[]", 200, "{}", Map.of(), 0L));
        assertFalse(check("[]", 500, "{}", Map.of(), 0L));
    }

    @Test
    void 未知断言类型判失败而不是跳过() throws Exception {
        assertFalse(check("[{\"type\":\"xxx\"}]", "{}"),
                "静默跳过会让写错 type 的用例一直绿着，它其实什么都没测");
    }
}
