package com.mobile.standalone.controller;

import com.mobile.standalone.auth.AuthFilter;
import com.mobile.standalone.auth.PlatformModules;
import com.mobile.standalone.auth.PlatformRole;
import com.mobile.standalone.auth.PlatformUser;
import com.mobile.standalone.auth.PlatformUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 账号管理。整个 {@code /platform-users} 前缀在 {@code PermissionRules} 里是管理员专属，
 * 所以这里不再逐个方法判角色——判两遍容易出现两边不一致的漏洞。
 */
@RestController
@RequestMapping("/platform-users")
@RequiredArgsConstructor
public class PlatformUserController {

    private final PlatformUserService userService;

    @GetMapping
    public ResponseEntity<List<PlatformUser>> list() {
        return ResponseEntity.ok(userService.list());
    }

    /** 角色字典，前端下拉直接用，不在前端硬编码三个中文名 */
    @GetMapping("/roles")
    public ResponseEntity<List<Map<String, Object>>> roles() {
        return ResponseEntity.ok(Arrays.stream(PlatformRole.values())
                .map(r -> Map.<String, Object>of(
                        "code", r.name(),
                        "label", r.label(),
                        "description", describe(r)))
                .toList());
    }

    /** 可勾选的功能模块，按侧栏分组。勾选框从这里渲染，不在前端另写一份 */
    @GetMapping("/modules")
    public ResponseEntity<List<PlatformModules.Group>> modules() {
        return ResponseEntity.ok(PlatformModules.groups());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        try {
            return ResponseEntity.ok(userService.create(
                    req.getUsername(), req.getDisplayName(), req.getEmail(),
                    req.getPassword(), req.getRole(),
                    Boolean.FALSE.equals(req.getAllModules()) ? orEmpty(req.getModules()) : null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody UpdateRequest req) {
        try {
            if (req.getEmail() != null) {
                userService.updateEmail(id, req.getEmail());
            }
            PlatformUser updated = userService.update(
                    id, req.getDisplayName(), req.getRole(), req.getEnabled());
            if (updated != null && req.getAllModules() != null) {
                updated = userService.updateModules(id, req.getAllModules() ? null : orEmpty(req.getModules()));
            }
            return updated == null
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable String id, @RequestBody ResetRequest req) {
        try {
            // 走 adminResetPassword 而不是 resetPassword：管理员设的密码管理员自己知道，
            // 而且多半是 IM 口头告诉本人的，必须逼着下次登录改掉
            userService.adminResetPassword(id, req.getPassword());
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "已重置，该账号下次登录会被要求先改密码"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        PlatformUser me = (PlatformUser) request.getAttribute(AuthFilter.ATTR_USER);
        if (me != null && me.getId().equals(id)) {
            // 删自己会立刻把自己踢下线，而且如果是最后一个管理员就再也进不来了
            return ResponseEntity.badRequest().body(Map.of("message", "不能删除自己"));
        }
        try {
            userService.delete(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private static List<String> orEmpty(List<String> modules) {
        return modules == null ? List.of() : modules;
    }

    private String describe(PlatformRole role) {
        return switch (role) {
            case VIEWER -> "只能看报告、用例、元素，改不了任何东西";
            case TESTER -> "跑用例、编排流程、改元素、跑线上回归；删不了东西";
            case ADMIN -> "全权限：删数据、改配置、管账号";
        };
    }

    @Data
    public static class CreateRequest {
        private String username;
        private String displayName;
        /** 建号时就填上，是让这个人以后能自助找回密码的唯一办法 */
        private String email;
        private String password;
        private String role;
        /** false 时只放行 {@link #modules}；不传或 true = 全部模块 */
        private Boolean allModules;
        private List<String> modules;
    }

    @Data
    public static class UpdateRequest {
        private String displayName;
        /** 传空串＝解绑。管理员填的邮箱视为可信，不需要用户再确认——账号本来就是管理员建的 */
        private String email;
        private String role;
        private Boolean enabled;
        /** 不传 = 模块不动；true = 全部；false = 只放行 {@link #modules} */
        private Boolean allModules;
        private List<String> modules;
    }

    @Data
    public static class ResetRequest {
        private String password;
    }
}
