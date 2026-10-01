package com.mobile.standalone.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流判定的单测。
 *
 * <p>守两个方向相反的错:
 * <ul>
 *   <li><b>漏判</b>:被限流的用例算成失败 → 跑太快时通过率下降,人去查用例,
 *       而真实原因是频率。响应还是 HTTP 200,这个误导极难查</li>
 *   <li><b>误判</b>:把真实的接口缺陷当成限流藏进 BLOCKED → 通过率变好看了,
 *       缺陷被摘出去了。这个更糟</li>
 * </ul>
 * 所以拿不准时一律 NOT_LIMITED。
 */
class RateLimitVerdictTest {

    private static final Set<String> THROTTLE = Set.of("910001");
    private static final Set<String> FORBID = Set.of("920001");
    private static final String PATH = "$.code";

    private static RateLimitVerdict d(int status, Map<String, List<String>> headers, String body) {
        return RateLimitVerdict.detect(status, headers, body, PATH, THROTTLE, FORBID);
    }

    // ---------- HTTP 层 ----------

    @Test
    @DisplayName("HTTP 429 不需要任何配置就该认 —— 这是标准语义")
    void http429() {
        assertEquals(RateLimitVerdict.THROTTLED,
                RateLimitVerdict.detect(429, Map.of(), "", null, Set.of(), Set.of()));
    }

    @Test
    @DisplayName("503 带 Retry-After 算限流,不带的不算")
    void http503() {
        assertEquals(RateLimitVerdict.THROTTLED,
                d(503, Map.of("Retry-After", List.of("30")), ""));
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                d(503, Map.of(), ""),
                "没有 Retry-After 的 503 多半是服务挂了，那是 ExecutionBlockers 管的事");
    }

    @Test
    @DisplayName("Retry-After 的大小写不影响")
    void 头名大小写() {
        assertEquals(RateLimitVerdict.THROTTLED, d(503, Map.of("retry-after", List.of("5")), ""));
        assertEquals(RateLimitVerdict.THROTTLED, d(503, Map.of("RETRY-AFTER", List.of("5")), ""));
    }

    // ---------- 业务码 ----------

    @Test
    @DisplayName("HTTP 200 + 限流业务码 —— 这是最常见的形态")
    void 业务码限流() {
        var v = d(200, Map.of(), "{\"code\":910001,\"msg\":\"系统繁忙，请稍后再试\"}");
        assertEquals(RateLimitVerdict.THROTTLED, v);
        assertTrue(v.worthRetrying(), "超过阈值，降速重试有意义");
    }

    @Test
    @DisplayName("禁止码和限流码必须分开 —— 一个该重试,一个重试没用")
    void 禁止码不重试() {
        var v = d(200, Map.of(), "{\"code\":920001}");
        assertEquals(RateLimitVerdict.FORBIDDEN, v);
        assertTrue(v.limited());
        assertFalse(v.worthRetrying(),
                "阈值被配成负数＝这个接口被关了，一直重试会撞墙到超时");
    }

    @Test
    @DisplayName("正常响应不算限流")
    void 正常响应() {
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "{\"code\":0,\"data\":{}}"));
    }

    @Test
    @DisplayName("别的业务错误码不算限流 —— 那是真失败,不能藏起来")
    void 别的错误码不算限流() {
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "{\"code\":5009001}"));
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "{\"code\":40001}"));
    }

    @Test
    @DisplayName("业务码是字符串时也要认")
    void 字符串业务码() {
        assertEquals(RateLimitVerdict.THROTTLED, d(200, Map.of(), "{\"code\":\"910001\"}"));
    }

    @Test
    @DisplayName("嵌套路径")
    void 嵌套路径() {
        assertEquals(RateLimitVerdict.THROTTLED,
                RateLimitVerdict.detect(200, Map.of(), "{\"head\":{\"code\":910001}}",
                        "$.head.code", THROTTLE, FORBID));
    }

    // ---------- 保守:拿不准就不算限流 ----------

    @Test
    @DisplayName("项目没配业务码路径 → 不看业务码")
    void 没配路径() {
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                RateLimitVerdict.detect(200, Map.of(), "{\"code\":910001}", null, THROTTLE, FORBID));
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                RateLimitVerdict.detect(200, Map.of(), "{\"code\":910001}", "  ", THROTTLE, FORBID));
    }

    @Test
    @DisplayName("响应不是 JSON → 不算限流")
    void 非JSON响应() {
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "<html>502 Bad Gateway</html>"));
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "not json at all"));
    }

    @Test
    @DisplayName("路径对不上结构 → 不算限流,而不是报错")
    void 路径对不上() {
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                RateLimitVerdict.detect(200, Map.of(), "{\"result\":{\"x\":1}}", "$.head.code",
                        THROTTLE, FORBID));
    }

    @Test
    @DisplayName("空响应体 / null → 不算限流")
    void 空响应() {
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), ""));
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), null));
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, null, null));
    }

    @Test
    @DisplayName("没配限流码时,即使返回 910001 也不算 —— 平台不硬编码某个项目的码")
    void 没配限流码() {
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                RateLimitVerdict.detect(200, Map.of(), "{\"code\":910001}", PATH, Set.of(), Set.of()));
        assertEquals(RateLimitVerdict.NOT_LIMITED,
                RateLimitVerdict.detect(200, Map.of(), "{\"code\":910001}", PATH, null, null));
    }

    @Test
    @DisplayName("业务码指向对象或数组 → 取不到,不算限流")
    void 业务码不是标量() {
        assertNull(RateLimitVerdict.businessCode("{\"code\":{\"x\":1}}", "$.code"));
        assertNull(RateLimitVerdict.businessCode("{\"code\":[1,2]}", "$.code"));
        assertEquals(RateLimitVerdict.NOT_LIMITED, d(200, Map.of(), "{\"code\":{\"x\":1}}"));
    }

    // ---------- 路径写法 ----------

    @Test
    @DisplayName("$ 前缀可有可无")
    void 路径前缀() {
        assertEquals("910001", RateLimitVerdict.businessCode("{\"code\":910001}", "$.code"));
        assertEquals("910001", RateLimitVerdict.businessCode("{\"code\":910001}", "code"));
        assertEquals("910001", RateLimitVerdict.businessCode("{\"code\":910001}", " $.code "));
    }

    @Test
    @DisplayName("limited() 和 worthRetrying() 的语义不能混")
    void 两个判断不是一回事() {
        assertTrue(RateLimitVerdict.THROTTLED.limited());
        assertTrue(RateLimitVerdict.FORBIDDEN.limited());
        assertFalse(RateLimitVerdict.NOT_LIMITED.limited());

        assertTrue(RateLimitVerdict.THROTTLED.worthRetrying());
        assertFalse(RateLimitVerdict.FORBIDDEN.worthRetrying(),
                "被关掉的接口重试多少次都一样");
    }

    @Test
    @DisplayName("FORBIDDEN 的文案要说清「重试没有意义」")
    void 禁止的文案() {
        assertTrue(RateLimitVerdict.FORBIDDEN.describe().contains("重试没有意义"));
    }
}
