package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.AuthTokenService;
import com.mobile.standalone.auth.PermissionRules;
import com.mobile.standalone.auth.PlatformRole;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.auth.PasswordResetService;
import com.mobile.standalone.auth.PlatformUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PlatformUserService userService;
    private final AuthTokenService tokenService;
    private final PasswordResetService passwordResetService;

    /** 忘记密码的响应恒定，连「已发出」都不能说得更具体——说了就是在回答「这个账号存不存在」 */
    private static final String FORGOT_REPLY = "若该账号存在且已绑定邮箱，验证码已发出，请查收（10 分钟内有效）";

    /**
     * 平台初始化到哪一步了。登录页据此决定显示「登录」还是「创建第一个管理员」。
     * <p>
     * 不需要令牌——没账号的时候本来就没有令牌可拿。
     */
    @GetMapping("/bootstrap-status")
    public ResponseEntity<Map<String, Object>> bootstrapStatus() {
        boolean bootstrapping;
        try {
            bootstrapping = userService.isBootstrapping();
        } catch (PlatformUserService.AuthUnavailableException e) {
            // 这个接口决定登录页显示「登录」还是「创建第一个管理员」。
            // DB 不可用时回 503，别让前端以为「还没建账号」而弹出建管理员的表单
            return ResponseEntity.status(503).body(Map.of(
                    "message", "账号服务暂时不可用，请稍后重试"));
        }
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

    /**
     * 申请重置验证码。
     * <p>
     * <b>无论账号存不存在、有没有绑邮箱、有没有撞限流，都返回同一句话、同一个状态码。</b>
     * 任何一种情况单独报错，这个接口就变成了一个不需要登录的账号枚举器。
     * 发信是异步的，响应耗时也因此保持一致——耗时差本身也是一条枚举信道。
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(HttpServletRequest request,
                                            @RequestBody ForgotPasswordRequest req) {
        try {
            passwordResetService.requestResetCode(req == null ? null : req.getAccount(), clientIp(request));
        } catch (Exception e) {
            // 出了任何意外也不能把差异透出去，日志里留痕就够了
            log.error("[auth-reset] 申请重置异常: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok(Map.of("message", FORGOT_REPLY));
    }

    /** 用验证码把密码改掉。成功后<b>不自动登录</b>——那只省一步，却把「拿到那封邮件＝进系统」的窗口拉长 */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "参数为空"));
        }
        try {
            passwordResetService.resetPassword(req.getAccount(), req.getCode(), req.getNewPassword());
            return ResponseEntity.ok(Map.of("success", true, "message", "密码已重置，请用新密码登录"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 绑定邮箱第一步：验当前密码，往新邮箱发验证码 */
    @PostMapping("/bind-email/code")
    public ResponseEntity<?> sendBindCode(HttpServletRequest request, @RequestBody BindEmailRequest req) {
        PlatformUser user = (PlatformUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        try {
            passwordResetService.requestBindCode(user, req.getEmail(), req.getPassword());
            return ResponseEntity.ok(Map.of("success", true, "message", "验证码已发出，请查收"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 绑定邮箱第二步：填验证码确认 */
    @PostMapping("/bind-email/confirm")
    public ResponseEntity<?> confirmBindEmail(HttpServletRequest request, @RequestBody BindEmailRequest req) {
        PlatformUser user = (PlatformUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "未登录"));
        }
        try {
            passwordResetService.confirmBind(user, req.getEmail(), req.getCode());
            return ResponseEntity.ok(Map.of("success", true, "message", "邮箱已绑定"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** 取真实客户端 IP：客户端是 Electron，也可能经过内网反代 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
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
        vo.put("email", user.getEmail());
        // 没绑邮箱的人忘了密码只能找管理员，前端据此每次登录提示一次绑定
        vo.put("emailBound", user.getEmail() != null && !user.getEmail().isBlank());
        vo.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
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
    public static class ForgotPasswordRequest {
        /** 用户名或邮箱都收，用户不用记自己当初填的是哪个 */
        private String account;
    }

    @Data
    public static class ResetPasswordRequest {
        private String account;
        private String code;
        private String newPassword;
    }

    @Data
    public static class BindEmailRequest {
        private String email;
        /** 发码这步要填当前密码 */
        private String password;
        /** 确认这步要填验证码 */
        private String code;
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
}
