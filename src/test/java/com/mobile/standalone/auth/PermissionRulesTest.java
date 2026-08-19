package com.mobile.standalone.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则表是前后端共用的唯一一份，错一条就是一个越权口子或一个用不了的功能。
 */
class PermissionRulesTest {

    @Test
    void 读改删对应三档() {
        assertThat(PermissionRules.required("GET", "/ui-flows")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("POST", "/ui-flows")).isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("PUT", "/ui-flows/uif-1")).isEqualTo(PlatformRole.TESTER);
        // 删除是唯一不可回退的操作
        assertThat(PermissionRules.required("DELETE", "/ui-flows/uif-1")).isEqualTo(PlatformRole.ADMIN);
    }

    /** 浏览器预检带不上令牌，拦掉的话所有跨域请求都发不出去 */
    @Test
    void 预检请求不拦() {
        assertThat(PermissionRules.required("OPTIONS", "/ui-flows")).isEqualTo(PlatformRole.VIEWER);
    }

    /** 长得像写、其实是查的 POST——不放行的话只读用户点不开接口对比分析 */
    @Test
    void 查询类POST按只读处理() {
        assertThat(PermissionRules.required("POST", "/api-changes/diff")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("POST", "/api-code-dirs/analyze")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("POST", "/ui-flows/preview")).isEqualTo(PlatformRole.VIEWER);
    }

    /** 只读用户必须能改自己的密码，否则只能去找管理员重置——那不是权限，是障碍 */
    @Test
    void 自助操作不看角色() {
        assertThat(PermissionRules.required("POST", "/auth/change-password"))
                .isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("GET", "/auth/me")).isEqualTo(PlatformRole.VIEWER);
    }

    @Test
    void 账号管理只有管理员() {
        assertThat(PermissionRules.required("GET", "/platform-users")).isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/platform-users")).isEqualTo(PlatformRole.ADMIN);
    }

    /**
     * 尾斜杠必须归一。
     * 不归一的话 {@code GET /platform-users/} 会绕开管理员限制，直接把全员账号列出来。
     */
    @Test
    void 尾斜杠不能绕过限制() {
        assertThat(PermissionRules.required("GET", "/platform-users/")).isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("GET", "/platform-users/?x=1")).isEqualTo(PlatformRole.ADMIN);
    }

    /** 截图是写进 img src 的，带不上请求头；拦下来报告里的图会全变裂图 */
    @Test
    void 截图和登录接口公开() {
        assertThat(PermissionRules.isPublic("/tmp/screenshots/task-1/1.png")).isTrue();
        assertThat(PermissionRules.isPublic("/ui-element-previews/a.png")).isTrue();
        assertThat(PermissionRules.isPublic("/auth/login")).isTrue();
        assertThat(PermissionRules.isPublic("/health")).isTrue();
        // 业务接口一律不公开
        assertThat(PermissionRules.isPublic("/ui-flows")).isFalse();
        assertThat(PermissionRules.isPublic("/devices")).isFalse();
    }

    @Test
    void 角色是包含关系不是并列() {
        assertThat(PlatformRole.ADMIN.satisfies(PlatformRole.TESTER)).isTrue();
        assertThat(PlatformRole.ADMIN.satisfies(PlatformRole.VIEWER)).isTrue();
        assertThat(PlatformRole.TESTER.satisfies(PlatformRole.VIEWER)).isTrue();
        assertThat(PlatformRole.TESTER.satisfies(PlatformRole.ADMIN)).isFalse();
        assertThat(PlatformRole.VIEWER.satisfies(PlatformRole.TESTER)).isFalse();
    }

    /**
     * 认不出的角色按只读处理。
     * 方向很重要：宁可让人少看见几个按钮来找你，也不能悄悄放成管理员——
     * 前者会被立刻发现，后者不会。
     */
    @Test
    void 认不出的角色退回只读() {
        assertThat(PlatformRole.from("管理员")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PlatformRole.from(null)).isEqualTo(PlatformRole.VIEWER);
        assertThat(PlatformRole.from("")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PlatformRole.from(" admin ")).isEqualTo(PlatformRole.ADMIN);
    }

    @Test
    void 路由权限按角色下发() {
        assertThat(PermissionRules.minRoleForRoute("/platform-users")).isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.minRoleForRoute("/app-online-regression")).isEqualTo(PlatformRole.TESTER);
        // 没列出来的默认可见——菜单是给人找路的，默认藏起来只会让人以为功能没了
        assertThat(PermissionRules.minRoleForRoute("/reports")).isEqualTo(PlatformRole.VIEWER);
    }
}
