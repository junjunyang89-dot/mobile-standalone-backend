package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.EnvVariable;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.EnvVariableService;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目级自动登录：执行前用配好的账号调一次登录接口，把 token 塞进服务读的那几个头。
 * <p>
 * 之前 token 是人从 App 里抓出来粘进环境变量的，一过期整批用例变红，
 * 看着像接口挂了，其实是登录态没了。
 * <p>
 * 账号和密码/验证码存成项目级环境变量 {@code loginAccount} / {@code loginSecret}（后者加密打码），
 * 登录请求体里用 {@code {{loginAccount}}} 引用——和用例用的是同一套变量，不另起一套存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiAuthService {

    public static final String ACCOUNT_VAR = "loginAccount";
    public static final String SECRET_VAR = "loginSecret";

    /** 登录返回里找 token 时认这些键 */
    private static final Set<String> TOKEN_KEYS = Set.of("token", "accesstoken", "access_token", "jwt", "authtoken");
    /** passport 签的 token 有效期按天算，这里保守一点：过期前就换新的 */
    private static final Duration TOKEN_TTL = Duration.ofHours(6);

    private final JdbcTemplate jdbcTemplate;
    private final EnvVariableService variableService;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String token, long at) {
    }

    @Data
    public static class Profile {
        private String project;
        private Boolean enabled;
        /** 服务从这些头读 token，逗号分隔；扫源码时自动识别 */
        private String tokenHeaders;
        private String loginMethod;
        /** 登录接口的完整地址，按环境分开：测试和线上的账号体系通常不是一个 */
        private String loginUrlTest;
        private String loginUrlProd;
        private String loginBody;
        /** token 在登录返回里的位置；空＝第一次登录时自动找，找到后记下来 */
        private String tokenPath;
    }

    public record TokenResult(String token, String tokenPath, boolean fromCache) {
    }

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_auth_profiles (
                        project VARCHAR(64) PRIMARY KEY,
                        enabled TINYINT(1) NOT NULL DEFAULT 1,
                        token_headers VARCHAR(255) NULL COMMENT '服务读 token 的头，逗号分隔',
                        login_method VARCHAR(8) NULL,
                        login_url_test VARCHAR(512) NULL,
                        login_url_prod VARCHAR(512) NULL,
                        login_body TEXT NULL COMMENT '登录请求体，引用 {{loginAccount}} {{loginSecret}}',
                        token_path VARCHAR(255) NULL COMMENT 'token 在返回里的位置，如 $.result.token',
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口测试：项目自动登录配置'
                    """);
        } catch (Exception e) {
            log.warn("[api-auth] 建表失败: {}", e.getMessage());
        }
    }

    public Profile get(String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        List<Profile> rows = jdbcTemplate.query(
                "SELECT * FROM api_auth_profiles WHERE project = ?", (rs, i) -> {
                    Profile p = new Profile();
                    p.setProject(rs.getString("project"));
                    p.setEnabled(rs.getBoolean("enabled"));
                    p.setTokenHeaders(rs.getString("token_headers"));
                    p.setLoginMethod(rs.getString("login_method"));
                    p.setLoginUrlTest(rs.getString("login_url_test"));
                    p.setLoginUrlProd(rs.getString("login_url_prod"));
                    p.setLoginBody(rs.getString("login_body"));
                    p.setTokenPath(rs.getString("token_path"));
                    return p;
                }, project);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 用例里的项目码可能是旧写法（data-center），归到接口清单用的那个 */
    public Profile findForCaseProject(String project) {
        Profile p = get(project);
        if (p == null && project != null) {
            String key = ApiProjectService.inventoryProjectKey(project);
            if (key != null && !key.equals(project)) {
                p = get(key);
            }
        }
        return p;
    }

    public Profile save(Profile p) {
        if (p == null || p.getProject() == null || p.getProject().isBlank()) {
            throw new IllegalArgumentException("项目不能为空");
        }
        jdbcTemplate.update("""
                INSERT INTO api_auth_profiles
                    (project, enabled, token_headers, login_method, login_url_test, login_url_prod, login_body, token_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), token_headers = VALUES(token_headers),
                    login_method = VALUES(login_method), login_url_test = VALUES(login_url_test),
                    login_url_prod = VALUES(login_url_prod), login_body = VALUES(login_body),
                    token_path = VALUES(token_path)
                """,
                p.getProject(), !Boolean.FALSE.equals(p.getEnabled()), blank(p.getTokenHeaders()),
                blank(p.getLoginMethod()) == null ? "POST" : p.getLoginMethod().toUpperCase(Locale.ROOT),
                blank(p.getLoginUrlTest()), blank(p.getLoginUrlProd()), blank(p.getLoginBody()), blank(p.getTokenPath()));
        invalidate(p.getProject());
        return get(p.getProject());
    }

    /** 扫源码识别出的 token 头：没配过才写，人改过的不覆盖 */
    public void rememberDetectedHeaders(String project, List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        Profile p = get(project);
        if (p != null && p.getTokenHeaders() != null && !p.getTokenHeaders().isBlank()) {
            return;
        }
        if (p == null) {
            p = new Profile();
            p.setProject(project);
            p.setEnabled(true);
        }
        p.setTokenHeaders(String.join(",", headers));
        save(p);
    }

    public void saveCredentials(String project, String environment, String account, String secret) {
        String env = TestEnvironment.normalize(environment);
        if (account != null) {
            EnvVariable a = new EnvVariable();
            a.setEnvironment(env);
            a.setProject(project);
            a.setName(ACCOUNT_VAR);
            a.setValue(account.trim());
            a.setSecret(false);
            a.setDescription("自动登录账号");
            variableService.save(a);
        }
        // 前端回显的是掩码，没改就原样提交回来，EnvVariableService 会保留真值
        if (secret != null && !secret.isEmpty()) {
            EnvVariable s = new EnvVariable();
            s.setEnvironment(env);
            s.setProject(project);
            s.setName(SECRET_VAR);
            s.setValue(secret);
            s.setSecret(true);
            s.setDescription("自动登录密码/验证码");
            variableService.save(s);
        }
        invalidate(project);
    }

    /** 给页面回显：账号明文，密码只告诉有没有配 */
    public Map<String, Object> credentialsView(String project, String environment) {
        Map<String, String> vars = variableService.resolveValues(TestEnvironment.normalize(environment), project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", vars.getOrDefault(ACCOUNT_VAR, ""));
        String secret = vars.getOrDefault(SECRET_VAR, "");
        // 只是引用了别的变量（{{prodCode}}）就不算密钥本身，回显出来人才知道用的是哪个
        boolean reference = secret.trim().matches("\\{\\{\\s*[\\w.\\-$]+\\s*}}");
        out.put("secret", secret.isEmpty() ? "" : reference ? secret.trim() : EnvVariableService.MASK);
        return out;
    }

    public void invalidate(String project) {
        cache.keySet().removeIf(k -> k.startsWith(project + "|"));
    }

    /** 这个项目配了能用的自动登录 */
    public boolean configured(Profile p, String environment) {
        return p != null && !Boolean.FALSE.equals(p.getEnabled())
                && blank(TestEnvironment.PROD.equals(TestEnvironment.normalize(environment))
                ? p.getLoginUrlProd() : p.getLoginUrlTest()) != null;
    }

    public List<String> headersOf(Profile p) {
        List<String> out = new ArrayList<>();
        if (p != null && p.getTokenHeaders() != null) {
            for (String h : p.getTokenHeaders().split("[,\\s]+")) {
                if (!h.isBlank()) {
                    out.add(h.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add("token");
        }
        return out;
    }

    /**
     * 拿这个项目的 token：缓存里有且没过期就用缓存，否则现登一次。
     *
     * @throws IllegalStateException 没配 / 登录失败 / 返回里找不到 token，消息直接给人看
     */
    public TokenResult token(Profile p, String environment, boolean force) {
        String env = TestEnvironment.normalize(environment);
        if (!configured(p, env)) {
            throw new IllegalStateException("没配" + TestEnvironment.label(env) + "的登录地址");
        }
        String key = p.getProject() + "|" + env;
        Cached c = cache.get(key);
        if (!force && c != null && System.currentTimeMillis() - c.at() < TOKEN_TTL.toMillis()) {
            return new TokenResult(c.token(), p.getTokenPath(), true);
        }
        String url = TestEnvironment.PROD.equals(env) ? p.getLoginUrlProd() : p.getLoginUrlTest();
        Map<String, String> vars = variableService.resolveValues(env, p.getProject());
        if (blank(vars.get(ACCOUNT_VAR)) == null) {
            throw new IllegalStateException("没填" + TestEnvironment.label(env) + "的登录账号");
        }
        String body;
        try {
            body = VariableResolver.resolve(p.getLoginBody() == null ? "{}" : p.getLoginBody(), null, vars);
        } catch (VariableResolver.UnresolvedException e) {
            throw new IllegalStateException("登录请求体里" + e.getMessage());
        }
        String method = blank(p.getLoginMethod()) == null ? "POST" : p.getLoginMethod().toUpperCase(Locale.ROOT);
        String respBody;
        int status;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(20));
            if ("GET".equals(method)) {
                b.uri(URI.create(withQuery(url.trim(), body))).GET();
            } else {
                b.uri(URI.create(url.trim()))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = resp.statusCode();
            respBody = resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("登录接口连不上：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        if (status >= 400) {
            throw new IllegalStateException("登录接口返回 HTTP " + status + "：" + preview(respBody));
        }
        String path = blank(p.getTokenPath());
        String token = null;
        try {
            if (path != null) {
                token = JsonPaths.readAsString(respBody, path);
            } else {
                path = findTokenPath(json.readTree(respBody), "$");
                if (path != null) {
                    token = JsonPaths.readAsString(respBody, path);
                    // 找到一次就记下来，下次直接按路径取，也方便人核对
                    jdbcTemplate.update("UPDATE api_auth_profiles SET token_path = ? WHERE project = ?",
                            path, p.getProject());
                    p.setTokenPath(path);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("登录返回不是 JSON：" + preview(respBody));
        }
        if (token == null || token.isBlank()) {
            // 业务失败（验证码错、账号不存在）通常是 HTTP 200 + 错误码，把原文给人看
            throw new IllegalStateException("登录返回里没找到 token：" + preview(respBody));
        }
        cache.put(key, new Cached(token, System.currentTimeMillis()));
        log.info("[api-auth] {} {} 自动登录成功，token 在 {}", p.getProject(), env, path);
        return new TokenResult(token, path, false);
    }

    /** 深度优先找第一个像 token 的字段 */
    static String findTokenPath(JsonNode n, String at) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            List<Map.Entry<String, JsonNode>> nested = new ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (TOKEN_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT)) && v.isTextual() && v.asText().length() >= 8) {
                    return at + "." + e.getKey();
                }
                if (v.isContainerNode()) {
                    nested.add(e);
                }
            }
            for (Map.Entry<String, JsonNode> e : nested) {
                String found = findTokenPath(e.getValue(), at + "." + e.getKey());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String withQuery(String url, String body) throws Exception {
        JsonNode n = json.readTree(body == null || body.isBlank() ? "{}" : body);
        if (!n.isObject() || n.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url).append(url.contains("?") ? "&" : "?");
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        boolean first = true;
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue().asText(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String preview(String s) {
        if (s == null) {
            return "(空)";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
