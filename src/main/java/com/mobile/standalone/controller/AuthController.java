package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.AuthTokenService;
import com.mobile.standalone.auth.PermissionRules;
import com.mobile.standalone.auth.PlatformRole;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.auth.PlatformUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PlatformUserService userService;
    private final AuthTokenService tokenService;

    /**
     * 平台初始化到哪一步了。登录页据此决定显示「登录」还是「创建第一个管理员」。
     * <p>
     * 不需要令牌——没账号的时候本来就没有令牌可拿。
     */
    @GetMapping("/bootstrap-status")
    public ResponseEntity<Map<String, Object>> bootstrapStatus() {
        boolean bootstrapping = userService.isBootstrapping();
        return ResponseEntity.ok(Map.of(
                "bootstrapping", bootstrapping,
                "message", bootstrapping
                        ? "还没有任何账号，权限校验暂未生效。创建第一个管理员后立即开始拦截"
                        : "已启用账号登录"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        // 没建账号时也允许「创建第一个管理员」走这个入口之外的 bootstrap 接口，
        // 这里只处理正常登录
        PlatformUser user = userService.authenticate(req.getUsername(), req.getPassword());
        if (user == null) {
            // 用户不存在和密码错误回同一句话：分开报的话，
            // 「用户不存在」就成了一个免费的账号枚举接口
            return ResponseEntity.status(401).body(Map.of("message", "登录名或密码不对"));
        }
        return ResponseEntity.ok(Map.of(
                "token", tokenService.issue(user.getId()),
                "user", meVo(user)));
    }

    /** 创建第一个管理员。一旦有了账号，这个接口就永久关闭——否则它就是个后门 */
    @PostMapping("/bootstrap-admin")
    public ResponseEntity<?> bootstrapAdmin(@RequestBody LoginRequest req) {
        try {
            PlatformUser user = userService.bootstrapAdmin(
                    req.getUsername(), req.getDisplayName(), req.getPassword());
            return ResponseEntity.ok(Map.of(
                    "token", tokenService.issue(user.getId()),
                    "user", meVo(user)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 当前登录人 + 他能看见哪些路由。
     * <p>
     * 路由权限由后端下发，前端不另写一份——两份规则迟早对不上，
     * 而对不上的表现极难查：后端拦了前端没禁用，用户只看到一句「操作失败」。
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        PlatformUser user = (PlatformUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (user == null) {
            // 还没建账号的过渡态：filter 整体放行，这里也按管理员报，
            // 否则菜单会因为「拿不到角色」而空掉，看起来像平台坏了
            if (userService.isBootstrapping()) {
                return ResponseEntity.ok(Map.of(
                        "bootstrapping", true,
                        "username", "未登录",
                        "displayName", "未启用账号",
                        "role", PlatformRole.ADMIN.name(),
                        "roleLabel", "未启用权限",
                        "hiddenRoutes", List.of()));
            }
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        return ResponseEntity.ok(meVo(user));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(HttpServletRequest request,
                                            @RequestBody ChangePasswordRequest req) {
        PlatformUser user = (PlatformUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        try {
            userService.changeOwnPassword(user.getId(), req.getOldPassword(), req.getNewPassword());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private Map<String, Object> meVo(PlatformUser user) {
        PlatformRole role = user.roleEnum();
        List<String> hidden = new ArrayList<>();
        PermissionRules.routeRules().forEach((route, min) -> {
            if (!role.satisfies(min)) {
                hidden.add(route);
            }
        });
        Map<String, Object> vo = new HashMap<>();
        vo.put("bootstrapping", false);
        vo.put("id", user.getId());
        vo.put("username", user.getUsername());
        vo.put("displayName", user.getDisplayName());
        vo.put("role", role.name());
        vo.put("roleLabel", role.label());
        // 只读看不到写按钮，测试看不到删除按钮——前端据此禁用，而不是隐藏：
        // 隐藏了用户会以为是 bug，禁用加提示才说得清是权限问题
        vo.put("canWrite", role.satisfies(PlatformRole.TESTER));
        vo.put("canDelete", role.satisfies(PlatformRole.ADMIN));
        vo.put("canManageUsers", role.satisfies(PlatformRole.ADMIN));
        vo.put("hiddenRoutes", hidden);
        return vo;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String displayName;
        private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
}
