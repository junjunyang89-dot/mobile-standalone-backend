package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.util.ExecutionLogJson;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 这次请求是不是被限流拦掉了。
 *
 * <p><b>为什么要单独判</b>:被限流的用例<b>一条断言都没跑到</b>——
 * 和服务连不上是同一类,都属于「没跑成」,不是「没通过」。
 * 算进通过率的话,跑太快会让通过率下降,人会去查用例、查断言、查环境变量,
 * 而真实原因是频率。这个误导极难查,因为响应看起来是正常的 HTTP 200。
 *
 * <p>被测后端的限流是全局的:{@code EntryInterceptor} 挂在所有接口上,
 * 没有 {@code addPathPatterns},一个接口都不漏。
 *
 * <p><b>两种限流要分开</b>,因为下一步动作完全不同:
 * <ul>
 *   <li>{@link #THROTTLED} —— 超过阈值,<b>降速重试有意义</b></li>
 *   <li>{@link #FORBIDDEN} —— 阈值被配成负数,这个接口被运维关了,
 *       <b>重试多少次都没用</b>,该报出来让人去问</li>
 * </ul>
 * 混成一种的话,第二种会让自动化一直撞墙到超时。
 *
 * <p><b>判定一律偏保守:拿不准就算不是限流。</b>
 * 这和 {@code ExecutionBlockers} 的原则一致——误判成普通失败最多让人多查一会儿,
 * 误判成限流会把一个真实的接口缺陷从通过率里摘出去藏起来,那更糟。
 */
public enum RateLimitVerdict {

    /** 不是限流 */
    NOT_LIMITED,

    /** 被限流,降速重试有意义 */
    THROTTLED,

    /** 被策略禁止,重试没有意义 */
    FORBIDDEN;

    public boolean limited() {
        return this != NOT_LIMITED;
    }

    /** 重试有没有意义 */
    public boolean worthRetrying() {
        return this == THROTTLED;
    }

    public String describe() {
        return switch (this) {
            case NOT_LIMITED -> "";
            case THROTTLED -> "被限流拦掉了,一条断言都没跑到——降低执行频率后重试";
            case FORBIDDEN -> "这个接口被限流策略禁止访问(阈值被配成了负数),"
                    + "重试没有意义,需要找运维确认是不是有意关掉的";
        };
    }

    /**
     * 判定。
     *
     * @param httpStatus      HTTP 状态码
     * @param headers         响应头(大小写不敏感地查 Retry-After)
     * @param body            响应体
     * @param successCodePath 项目配的业务码路径,如 {@code $.code};<b>没配就不看业务码</b>
     * @param throttleCodes   被限流的业务码
     * @param forbidCodes     被禁止的业务码
     */
    public static RateLimitVerdict detect(int httpStatus,
                                          Map<String, List<String>> headers,
                                          String body,
                                          String successCodePath,
                                          Set<String> throttleCodes,
                                          Set<String> forbidCodes) {
        // HTTP 429 是标准语义,不需要任何配置就该认
        if (httpStatus == 429) {
            return THROTTLED;
        }
        // 503 单独看 Retry-After:没有这个头的 503 多半是服务挂了,那是 ExecutionBlockers 的事
        if (httpStatus == 503 && hasHeader(headers, "retry-after")) {
            return THROTTLED;
        }

        String code = businessCode(body, successCodePath);
        if (code == null) {
            // 取不到业务码就不下结论。解析失败当成限流的话,
            // 每一个返回体格式变了的接口都会被悄悄藏进 BLOCKED
            return NOT_LIMITED;
        }
        if (forbidCodes != null && forbidCodes.contains(code)) {
            return FORBIDDEN;
        }
        if (throttleCodes != null && throttleCodes.contains(code)) {
            return THROTTLED;
        }
        return NOT_LIMITED;
    }

    /**
     * 按项目配的路径取业务码。
     *
     * <p>只支持 {@code $.a.b} 这种直白的点路径——限流码不会藏在数组里,
     * 为它引一套完整的 JsonPath 实现不值当,而多一个依赖就多一处会变的东西。
     */
    static String businessCode(String body, String path) {
        if (body == null || body.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim();
        if (p.startsWith("$.")) {
            p = p.substring(2);
        } else if (p.startsWith("$")) {
            p = p.substring(1);
        }
        if (p.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ExecutionLogJson.mapper().readTree(body);
            for (String seg : p.split("\\.")) {
                if (node == null || seg.isBlank()) {
                    return null;
                }
                node = node.get(seg);
            }
            if (node == null || node.isNull() || node.isContainerNode()) {
                return null;
            }
            return node.asText();
        } catch (Exception e) {
            // 不是 JSON、或者结构对不上：当作取不到，交给上面的保守分支
            return null;
        }
    }

    private static boolean hasHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return false;
        }
        for (String k : headers.keySet()) {
            if (k != null && k.toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }
}
