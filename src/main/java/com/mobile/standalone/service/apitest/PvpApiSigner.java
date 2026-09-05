package com.mobile.standalone.service.apitest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PVP 开放接口的请求签名。
 * <p>
 * 算得出签名，平台才能<b>直接调这些接口</b>——那是「接口返回 vs 页面渲染」比对里缺的一方。
 * 不然只能靠页面自己去调，而页面一旦登录态出问题（比如 Cookie 没注进去），
 * 就分不清是接口的问题还是渲染的问题。
 * <p>
 * 算法：
 * <pre>
 *   request = 除 a/r/t/s 外的参数，按 "key=value" 整串升序排列，用 &amp; 连接，再 URL 解码
 *   s       = sha1( appId + md5( r + t + request ) + appKey )
 * </pre>
 * <p>
 * 有两处文档没写、但前端实现里有的细节，漏了就签不对：
 * <ul>
 *   <li><b>拼进 md5 之前要 URL 解码</b>。前端是 {@code decodeURIComponent(g)}，
 *       所以 {@code access_token=NzY1...=} 末尾那个 {@code =} 是解码后的形态</li>
 *   <li><b>排序排的是整个 {@code key=value} 串</b>，不是只排 key。前端用的
 *       {@code sortBy} 不带取值函数，按字符串自然序排。绝大多数参数两种排法结果一致，
 *       但既然服务端校验的是前端那份，这里就跟着前端来</li>
 * </ul>
 * <p>
 * <b>appKey 不进代码</b>。它是长期有效的密钥，泄露就等于别人能伪造你们所有 API 请求，
 * 所以由调用方从 {@code env_variables}（secret 掩码那类）取来传进来。
 */
public final class PvpApiSigner {

    private PvpApiSigner() {
    }

    /** 这四个是签名协议自身的参数，不参与被签名的 request 串 */
    private static final Set<String> RESERVED = Set.of("a", "r", "t", "s");

    /**
     * @param appId  分配的 APP ID，对应 query 里的 {@code a}
     * @param appKey 分配的 APP KEY，<b>只参与计算、不出现在请求里</b>
     * @param r      随机数
     * @param t      unix 时间戳（秒）
     * @param params 全部业务参数；其中 a/r/t/s 会被忽略，传不传都行
     * @return 小写十六进制的签名，对应 query 里的 {@code s}
     */
    public static String sign(String appId, String appKey, String r, String t,
                              Map<String, String> params) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (appKey == null || appKey.isBlank()) {
            // 没有 appKey 算出来的签名一定是错的，但服务端只会回一句「签名错误」，
            // 到那时候根本看不出是漏配了密钥
            throw new IllegalArgumentException("appKey 不能为空（应从环境变量里取，不要写进代码）");
        }
        String request = buildRequest(params);
        String md5 = md5(nz(r) + nz(t) + request);
        return sha1(appId + md5 + appKey);
    }

    /**
     * 拼出被签名的 request 串：排除协议参数 → 按整串升序 → 用 &amp; 连 → URL 解码。
     * <p>
     * 公开的：签不对时十有八九错在这一步，而不是在两个哈希上。把它回给调用方，
     * 人才能拿自己抓到的请求逐字符比对，而不是对着一个签名干瞪眼。
     */
    public static String buildRequest(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null || RESERVED.contains(e.getKey())) {
                continue;
            }
            parts.add(e.getKey() + "=" + nz(e.getValue()));
        }
        parts.sort(String::compareTo);
        return urlDecode(String.join("&", parts));
    }

    /** 解不开就原样返回：签名算错好过整个请求发不出去，而且原文更便于对着排查 */
    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    static String md5(String s) {
        return hex("MD5", s);
    }

    static String sha1(String s) {
        return hex("SHA-1", s);
    }

    private static String hex(String algorithm, String s) {
        try {
            byte[] d = MessageDigest.getInstance(algorithm)
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 " + algorithm + " 失败", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
