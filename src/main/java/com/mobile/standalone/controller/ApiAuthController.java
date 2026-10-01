package com.mobile.standalone.controller;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.apitest.ApiAuthService;
import com.mobile.standalone.service.apitest.LoginEndpointFinder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 项目自动登录：识别 token 从哪来、配账号、试登 */
@RestController
@RequestMapping("/api-auth/projects/{project}")
@RequiredArgsConstructor
public class ApiAuthController {

    private final ApiAuthService authService;
    private final ApiProjectService projectService;
    private final ApiEndpointMapper endpointMapper;

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String project) {
        ApiAuthService.Profile p = authService.get(project);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", p);
        out.put("credentials", Map.of(
                "TEST", authService.credentialsView(project, "TEST"),
                "PROD", authService.credentialsView(project, "PROD")));
        List<ApiEndpoint> rows = endpointMapper.search(List.of(project), null, null, null);
        out.put("authRequired", rows.stream().filter(e -> Boolean.TRUE.equals(e.getAuthRequired())).count());
        out.put("authUnknown", rows.stream().filter(e -> e.getAuthRequired() == null).count());
        out.put("total", rows.size());
        return ResponseEntity.ok(out);
    }

    @SuppressWarnings("unchecked")
    @PutMapping
    public ResponseEntity<?> save(@PathVariable String project, @RequestBody Map<String, Object> body) {
        if (projectService.get(project) == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "项目不存在: " + project));
        }
        try {
            ApiAuthService.Profile p = new ApiAuthService.Profile();
            p.setProject(project);
            p.setEnabled(!Boolean.FALSE.equals(body.get("enabled")));
            p.setTokenHeaders(str(body.get("tokenHeaders")));
            p.setLoginMethod(str(body.get("loginMethod")));
            p.setLoginUrlTest(str(body.get("loginUrlTest")));
            p.setLoginUrlProd(str(body.get("loginUrlProd")));
            p.setLoginBody(str(body.get("loginBody")));
            p.setTokenPath(str(body.get("tokenPath")));
            ApiAuthService.Profile saved = authService.save(p);
            if (body.get("credentials") instanceof Map<?, ?> creds) {
                for (String env : List.of("TEST", "PROD")) {
                    if (creds.get(env) instanceof Map<?, ?> c) {
                        Map<String, Object> m = (Map<String, Object>) c;
                        String account = str(m.get("account"));
                        String secret = str(m.get("secret"));
                        boolean blankAccount = account == null || account.isBlank();
                        boolean blankSecret = secret == null || secret.isEmpty();
                        if (!(blankAccount && blankSecret)) {
                            authService.saveCredentials(project, env, account, secret);
                        }
                    }
                }
            }
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 在并排的仓库里找登录接口。
     * <p>
     * body.dir 是这个项目的源码目录（全量扫描时填的那个），搜它的上一级；
     * 不给就用项目管理里配的仓库路径。
     */
    @PostMapping("/detect")
    public ResponseEntity<?> detect(@PathVariable String project, @RequestBody(required = false) Map<String, String> body) {
        var proj = projectService.get(project);
        String dir = body == null ? null : body.get("dir");
        if ((dir == null || dir.isBlank()) && proj != null) {
            dir = proj.getRepoPath();
        }
        if (dir == null || dir.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "不知道源码在哪：先全量扫描一次，或在项目管理里配仓库路径"));
        }
        Path source = Path.of(dir.trim()).toAbsolutePath().normalize();
        Path root = source.getParent() == null ? source : source.getParent();
        try {
            var candidates = LoginEndpointFinder.find(List.of(root));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("searched", root.toString());
            out.put("candidates", candidates);
            ApiAuthService.Profile p = authService.get(project);
            out.put("tokenHeaders", p == null ? null : p.getTokenHeaders());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage() == null ? "识别失败" : e.getMessage()));
        }
    }

    @PostMapping("/test-login")
    public ResponseEntity<?> testLogin(@PathVariable String project, @RequestBody(required = false) Map<String, String> body) {
        String env = body == null ? "TEST" : body.getOrDefault("environment", "TEST");
        try {
            var r = authService.token(authService.get(project), env, true);
            String t = r.token();
            // 只回显头尾，完整 token 不出后端
            String masked = t.length() <= 12 ? t.charAt(0) + "…" : t.substring(0, 6) + "…" + t.substring(t.length() - 4);
            return ResponseEntity.ok(Map.of("token", masked, "tokenPath", r.tokenPath() == null ? "" : r.tokenPath()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
