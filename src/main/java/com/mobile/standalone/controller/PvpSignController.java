package com.mobile.standalone.controller;

import com.mobile.standalone.service.EnvVariableService;
import com.mobile.standalone.service.apitest.PvpApiSigner;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PVP 接口签名。
 * <p>
 * 有了它平台才能直接调这些接口，从而做「接口返回 vs 页面渲染」的比对——
 * 否则只能让页面自己去调，页面登录态一出问题就分不清是接口错了还是渲染错了。
 * <p>
 * <b>appKey 从环境变量取，不接受调用方传</b>。它是长期有效的密钥，
 * 让它出现在请求体里就意味着它会进访问日志、进浏览器历史、进任何抓包记录。
 */
@Slf4j
@RestController
@RequestMapping("/pvp-sign")
@RequiredArgsConstructor
public class PvpSignController {

    /** 环境变量里放密钥的两个名字 */
    public static final String VAR_APP_ID = "PVP_APP_ID";
    public static final String VAR_APP_KEY = "PVP_APP_KEY";

    private final EnvVariableService variableService;

    /**
     * 算一条签名。
     * <p>
     * 用来核对实现对不对：把抓包里那条请求的 a/r/t 和业务参数原样填进来，
     * 算出的 s 和抓到的一致，就说明签名实现是对的。
     *
     * @param body {@code environment} 取哪个环境的密钥（默认 TEST）；
     *             {@code r}/{@code t} 随机数与时间戳；
     *             {@code params} 业务参数（a/r/t/s 传了也会被忽略）
     */
    @PostMapping
    public ResponseEntity<?> sign(@RequestBody Map<String, Object> body) {
        try {
            String env = TestEnvironment.normalize(str(body.get("environment")));
            Map<String, String> vars = variableService.resolveValues(env);

            String appId = vars.get(VAR_APP_ID);
            String appKey = vars.get(VAR_APP_KEY);
            if (appId == null || appId.isBlank() || appKey == null || appKey.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        "环境 " + env + " 里还没配 " + VAR_APP_ID + " / " + VAR_APP_KEY
                                + "。到「环境变量」里加上，APP KEY 记得勾 secret——"
                                + "它是长期密钥，泄露等于别人能伪造你们所有 API 请求"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = body.get("params") instanceof Map
                    ? (Map<String, Object>) body.get("params") : Map.of();
            Map<String, String> params = new LinkedHashMap<>();
            raw.forEach((k, v) -> params.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));

            String r = str(body.get("r"));
            String t = str(body.get("t"));
            String s = PvpApiSigner.sign(appId, appKey, r, t, params);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("a", appId);
            out.put("r", r);
            out.put("t", t);
            out.put("s", s);
            // 把被签名的串也回出去：签不对时基本都错在这一步，
            // 只回一个 s 的话人没法自己比对哪里不一样
            out.put("signedRequest", PvpApiSigner.buildRequest(params));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
