package com.mobile.standalone.service.apitest;

import java.util.Locale;

/**
 * 被测服务自带的基础接口：不进接口清单，抓包不收，执行器也不发请求。
 * <ul>
 *   <li>Swagger / OpenAPI 文档页：安全组要求测试平台一律不碰；</li>
 *   <li>{@code /basic/offline}：把服务从 Nacos 摘掉，调一次这台实例就下线了；</li>
 *   <li>{@code /basic/healthCheck}：探活用，不是业务接口。</li>
 * </ul>
 * 同一个 HealthController 里常混着业务接口（如 loadMajorHotComments），所以按路径认，不按类名。
 */
public final class InfraEndpoints {

    private InfraEndpoints() {
    }

    /**
     * @param pathOrUrl 路径或完整地址，带 query 也行
     * @return 是基础接口时给原因，否则 null
     */
    public static String reason(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        String p = pathOrUrl.trim().toLowerCase(Locale.ROOT);
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int slash = p.indexOf('/', scheme + 3);
            p = slash < 0 ? "/" : p.substring(slash);
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int hash = p.indexOf('#');
        if (hash >= 0) {
            p = p.substring(0, hash);
        }
        if (p.contains("swagger") || p.contains("api-docs") || p.endsWith("/doc.html") || p.equals("doc.html")
                || p.contains("/webjars/")) {
            return "Swagger 文档页（安全组要求测试平台不碰）";
        }
        String tail = "/" + p.replaceAll("^/+|/+$", "");
        if (tail.endsWith("/basic/offline")) {
            return "Nacos 下线接口（调用会把服务实例摘掉）";
        }
        if (tail.endsWith("/basic/healthcheck")) {
            return "健康检查（不是业务接口）";
        }
        return null;
    }

    public static boolean is(String pathOrUrl) {
        return reason(pathOrUrl) != null;
    }
}
