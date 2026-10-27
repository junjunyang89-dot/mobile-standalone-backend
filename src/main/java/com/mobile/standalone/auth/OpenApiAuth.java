package com.mobile.standalone.auth;

import com.mobile.standalone.config.OpenApiProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 开放接口的鉴权：请求头 {@code X-API-Key}（或 {@code Authorization: Bearer <key>}）对上配置里的某个调用方。
 * <p>
 * 开放接口不走登录令牌：令牌会过期，机器调用方没法在过期时「重新登录」，
 * 结果就是半夜定时下发的回归全部 401。
 */
@Component
@RequiredArgsConstructor
public class OpenApiAuth {

    public static final String ATTR_CLIENT = "openApiClient";
    public static final String PREFIX = "/open/v1";

    private final OpenApiProperties props;

    public record Result(String client, int status, String message) {
        public boolean ok() {
            return client != null;
        }
    }

    public static boolean isOpenPath(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    public Result verify(HttpServletRequest request) {
        boolean anyEnabled = props.getClients() != null && props.getClients().stream()
                .anyMatch(c -> c.isEnabled() && c.getKey() != null && !c.getKey().isBlank());
        // 没配 key 不能当成「不鉴权」：开放接口能下发真机任务，敞开就是谁都能用
        if (!anyEnabled) {
            return new Result(null, 503, "开放接口未启用：服务端没有配置 open-api.clients");
        }
        String provided = keyOf(request);
        if (provided == null) {
            return new Result(null, 401, "缺少 API Key：请求头带 X-API-Key");
        }
        byte[] given = provided.getBytes(StandardCharsets.UTF_8);
        for (OpenApiProperties.Client c : props.getClients()) {
            if (!c.isEnabled() || c.getKey() == null || c.getKey().isBlank()) {
                continue;
            }
            if (MessageDigest.isEqual(given, c.getKey().trim().getBytes(StandardCharsets.UTF_8))) {
                String name = c.getName() == null || c.getName().isBlank() ? "open-api" : c.getName().trim();
                return new Result(name, 200, null);
            }
        }
        return new Result(null, 401, "API Key 无效或已停用");
    }

    /** 给回调签名用：按调用方名取它的 key */
    public String keyOf(String clientName) {
        if (clientName == null || props.getClients() == null) {
            return null;
        }
        return props.getClients().stream()
                .filter(c -> clientName.equals(c.getName() == null ? null : c.getName().trim()))
                .map(OpenApiProperties.Client::getKey)
                .findFirst().orElse(null);
    }

    private static String keyOf(HttpServletRequest request) {
        String x = request.getHeader("X-API-Key");
        if (x != null && !x.isBlank()) {
            return x.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String v = auth.substring(7).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }
}
