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
        assertThat(PermissionRules.required("POST", "/ai-test-cases/12/ask")).isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("POST", "/ai-test-cases/requirements/APP-9069/ask"))
                .isEqualTo(PlatformRole.VIEWER);
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
        assertThat(PermissionRules.isPublic("/functional-test/tasks/abc/screenshots/3")).isTrue();
        assertThat(PermissionRules.isPublic("/functional-test/tasks/abc/screenshot")).isTrue();
        assertThat(PermissionRules.isPublic("/functional-test/tasks/abc/screenshot-list")).isFalse();
        assertThat(PermissionRules.isPublic("/functional-test/tasks/abc/execution-log")).isFalse();
        assertThat(PermissionRules.isPublic("/ui-element-previews/a.png")).isTrue();
        assertThat(PermissionRules.isPublic("/bug-images/2026-09-04/a.png")).isTrue();
        assertThat(PermissionRules.isPublic("/issue-images/2026-09-04/a.png")).isTrue();
        assertThat(PermissionRules.isPublic("/knowledge-note-images/2026-09-07/a.png")).isTrue();
        assertThat(PermissionRules.isPublic("/auth/login")).isTrue();
        assertThat(PermissionRules.isPublic("/health")).isTrue();
        assertThat(PermissionRules.isPublic("/debug-tests/open")).isTrue();
        assertThat(PermissionRules.isPublic("/debug-tests")).isFalse();
        assertThat(PermissionRules.isPublic("/debug-tests/ping")).isFalse();
        assertThat(PermissionRules.isPublic("/debug-tests/1")).isFalse();
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
        assertThat(PermissionRules.minRoleForRoute("/app-packs")).isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.minRoleForRoute("/incident-playbooks")).isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.minRoleForRoute("/app-automation")).isEqualTo(PlatformRole.TESTER);
        // 没列出来的默认可见——菜单是给人找路的，默认藏起来只会让人以为功能没了
        assertThat(PermissionRules.minRoleForRoute("/reports")).isEqualTo(PlatformRole.VIEWER);
    }

    /** 忘记密码的人本来就登录不了，这两个必须免登录——防护靠恒定响应 + 限流，不靠鉴权 */
    @Test
    void 忘记密码接口免登录() {
        assertThat(PermissionRules.isPublic("/auth/forgot-password")).isTrue();
        assertThat(PermissionRules.isPublic("/auth/reset-password")).isTrue();
    }

    /**
     * 免登录前缀是按 startsWith 匹配的，很容易误伤同名后缀。
     * 管理员那条重置接口路径里也有 reset-password，放开了就是任何人能重置任何人的密码。
     */
    @Test
    void 管理员重置接口不能被误放行() {
        assertThat(PermissionRules.isPublic("/platform-users/usr-1/reset-password")).isFalse();
        assertThat(PermissionRules.required("POST", "/platform-users/usr-1/reset-password"))
                .isEqualTo(PlatformRole.ADMIN);
    }

    /** 绑邮箱是每个人自己的事：只读账号也得能绑，否则他们永远只能找管理员重置 */
    @Test
    void 绑邮箱只读账号也能用() {
        assertThat(PermissionRules.required("POST", "/auth/bind-email/code"))
                .isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("POST", "/auth/bind-email/confirm"))
                .isEqualTo(PlatformRole.VIEWER);
        // 但它要登录，不能是公开接口——公开了就能往任意邮箱发信
        assertThat(PermissionRules.isPublic("/auth/bind-email/code")).isFalse();
    }

    /**
     * 这条对应一个真实的越权链：{@code POST /api/health/../platform-users} 带 role=ADMIN，
     * filter 看到 /health 前缀放行，请求却落到建账号接口——不登录就造出一个管理员。
     * controller 里没有任何角色校验，filter 是唯一的边界，所以这里破了就是全破。
     */
    @Test
    void 穿越路径一律拒绝() {
        assertThat(PermissionRules.looksTraversal("/health/../platform-users")).isTrue();
        assertThat(PermissionRules.looksTraversal("/auth/../platform-users")).isTrue();
        assertThat(PermissionRules.looksTraversal("/./platform-users")).isTrue();
        // 解码后还剩 % 说明是双重编码（%252e），同样拒绝
        assertThat(PermissionRules.looksTraversal("/health/%2e%2e/platform-users")).isTrue();
        assertThat(PermissionRules.looksTraversal("/health\\..\\platform-users")).isTrue();
    }

    @Test
    void 正常路径不受影响() {
        assertThat(PermissionRules.looksTraversal("/platform-users/usr-1/reset-password")).isFalse();
        assertThat(PermissionRules.looksTraversal("/devices/emulator-5554/inspect/hierarchy")).isFalse();
        assertThat(PermissionRules.looksTraversal("/ai-test-cases/requirements/APP-9069/ask")).isFalse();
        assertThat(PermissionRules.looksTraversal("/")).isFalse();
        assertThat(PermissionRules.looksTraversal(null)).isFalse();
    }

    /**
     * 前缀按路径段匹配，不是裸 startsWith。
     * 裸 startsWith 的话，以后新增一个 /health-check 会自动继承 /health 的免鉴权。
     */
    @Test
    void 前缀按路径段匹配() {
        assertThat(PermissionRules.isPublic("/health")).isTrue();
        assertThat(PermissionRules.isPublic("/health/db")).isTrue();
        // 同前缀开头但不同段：不能白拿免鉴权
        assertThat(PermissionRules.isPublic("/health-check")).isFalse();
        assertThat(PermissionRules.isPublic("/authorize")).isFalse();
        assertThat(PermissionRules.isPublic("/auth/login-history")).isFalse();
        // 管理员前缀同理，不能因为开头像就误判
        assertThat(PermissionRules.required("GET", "/platform-users-export"))
                .isNotEqualTo(PlatformRole.ADMIN);
    }

    // ================= 50 人共用带来的提权 =================

    /**
     * 「DELETE 要 ADMIN」的前提是销毁都走 DELETE，可实际上批量销毁常写成 POST。
     * reimport 会先删光一批用例再重导，比很多 DELETE 更危险。
     */
    @Test
    void 用POST实现的批量销毁要管理员() {
        assertThat(PermissionRules.required("POST", "/api-tests/cases/reimport"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/screenshot-storage/cleanup"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/some-module/reset"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/some-module/purge"))
                .isEqualTo(PlatformRole.ADMIN);
    }

    /** 普通的写操作不能被上面那份清单带上去，否则测试同学寸步难行 */
    @Test
    void 普通写操作仍然是测试档() {
        assertThat(PermissionRules.required("POST", "/ui-flows")).isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("POST", "/api-tests/cases")).isEqualTo(PlatformRole.TESTER);
        // 「cleanup」出现在中间段而不是结尾，不算销毁接口
        assertThat(PermissionRules.required("POST", "/cleanup-report/preview"))
                .isEqualTo(PlatformRole.VIEWER);
    }

    /**
     * 改生产环境配置要 ADMIN。
     * <p>
     * 这里改的是所有人共用的生产 baseUrl / 鉴权头 / 账号变量，而 secret 覆盖之后
     * 没有任何接口能读回旧值——覆盖即永久丢失。
     */
    @Test
    void 写生产环境配置要管理员() {
        assertThat(PermissionRules.required("PUT", "/api-tests/envs/PROD"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("PUT", "/api-tests/envs/PROD/variables"))
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("POST", "/api-tests/envs/prod/variables"))
                .as("大小写不能绕过——normalize 会把 prod/PROD 都归一")
                .isEqualTo(PlatformRole.ADMIN);
        assertThat(PermissionRules.required("DELETE", "/api-tests/envs/PROD/variables/token"))
                .isEqualTo(PlatformRole.ADMIN);
    }

    /** 只拦写。读生产配置（变量值已掩码）不该被挡住，否则查问题都查不了 */
    @Test
    void 读生产环境配置不提权() {
        assertThat(PermissionRules.required("GET", "/api-tests/envs/PROD"))
                .isEqualTo(PlatformRole.VIEWER);
        assertThat(PermissionRules.required("GET", "/api-tests/envs/PROD/variables"))
                .isEqualTo(PlatformRole.VIEWER);
    }

    /** 测试环境的写操作不受影响，不然日常工作全卡住 */
    @Test
    void 写测试环境配置仍是测试档() {
        assertThat(PermissionRules.required("PUT", "/api-tests/envs/TEST"))
                .isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("PUT", "/api-tests/envs/TEST/variables"))
                .isEqualTo(PlatformRole.TESTER);
    }

    /**
     * 名字里带 prod 的普通路径不能被误判。
     * <p>
     * 用包含匹配是因为生产段夹在路径中间，但不能因此把 product / production-report 这类也提权——
     * 那样只读用户会莫名其妙点不开页面，而且没人查得出为什么。
     */
    @Test
    void 名字里带prod的普通路径不被误判() {
        assertThat(PermissionRules.required("PUT", "/products/p-1"))
                .isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.required("POST", "/production-report"))
                .isEqualTo(PlatformRole.TESTER);
        assertThat(PermissionRules.isProdWritePath("/products/p-1")).isFalse();
        assertThat(PermissionRules.isProdWritePath("/api-tests/envs/PROD")).isTrue();
    }
}
