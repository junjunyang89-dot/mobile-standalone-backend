package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 每条生成的断言带上出处：{@code source} + 一句 {@code evidence}。
 * <p>
 * 人看用例时最想知道的是「这条断言凭什么」——从真实响应里验过的，和模型按参数名猜的，
 * 在列表里长得一模一样，跑出来也一样是绿的。执行器只认 type/path/equals 这些键，
 * 多出来的两个键不影响执行。
 */
public final class AssertionSource {

    /** 在抓包的真实响应上成立过 */
    public static final String CAPTURE = "capture";
    /** 试跑测试环境，按实际返回写的 */
    public static final String TRYRUN = "tryrun";
    /** 接口对比 / 抓包记录过的返回字段，只断言存在 */
    public static final String FIELDS = "fields";
    /** 模型写的，没有真实响应可验 */
    public static final String AI = "ai";
    /** 按项目配置的成功码约定写的，没在这个接口上验过 */
    public static final String CONVENTION = "convention";
    /** 平台固定加的（成功用例的 HTTP 200） */
    public static final String DEFAULT = "default";
    /** 读源码推出来的（登录注解、错误码），没在响应上验过 */
    public static final String CODE = "code";

    private AssertionSource() {
    }

    public static ObjectNode mark(ObjectNode a, String source, String evidence) {
        a.put("source", source);
        if (evidence != null && !evidence.isBlank()) {
            a.put("evidence", evidence);
        }
        return a;
    }

    /** 给还没标出处的断言统一补上 */
    public static void markUnset(ArrayNode asserts, String source, String evidence) {
        for (JsonNode a : asserts) {
            if (a instanceof ObjectNode o && !o.has("source")) {
                mark(o, source, evidence);
            }
        }
    }
}
