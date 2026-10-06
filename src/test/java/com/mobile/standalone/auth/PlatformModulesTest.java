package com.mobile.standalone.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformModulesTest {

    @Test
    void routesAreUniqueAndHomeIsNotSelectable() {
        List<String> routes = PlatformModules.routes();
        assertThat(new HashSet<>(routes)).hasSameSizeAs(routes);
        assertThat(routes).doesNotContain(PlatformModules.HOME, "/platform-users");
    }

    @Test
    void sanitizeDropsUnknownAndKeepsCatalogOrder() {
        assertThat(PlatformModules.sanitize(List.of("/settings", "/hack", " /api-reports ", "/settings")))
                .containsExactly("/api-reports", "/settings");
    }

    @Test
    void nullMeansUnrestrictedBlankMeansNothing() {
        assertThat(PlatformModules.parse(null)).isNull();
        assertThat(PlatformModules.parse("")).isEmpty();
        assertThat(PlatformModules.parse(PlatformModules.serialize(List.of("/api-reports", "/devices"))))
                .containsExactly("/devices", "/api-reports");
    }

    @Test
    void adminIsNeverModuleRestricted() {
        PlatformUser admin = user(PlatformRole.ADMIN, "");
        PlatformUser tester = user(PlatformRole.TESTER, "/api-reports");
        PlatformUser legacy = user(PlatformRole.VIEWER, null);

        assertThat(admin.visibleModules()).isNull();
        assertThat(tester.visibleModules()).containsExactly("/api-reports");
        assertThat(legacy.visibleModules()).isNull();
    }

    @Test
    void hiddenRoutesCombineRoleAndModules() {
        List<String> hidden = PermissionRules.hiddenRoutes(PlatformRole.VIEWER, List.of("/api-reports", "/devices"));

        assertThat(hidden).doesNotContain("/api-reports");
        assertThat(hidden).contains("/api-collections", "/settings");
        // 勾了也挡不住角色不够：设备池要「测试」档
        assertThat(hidden).contains("/devices");
        assertThat(PermissionRules.visibleRoutes(hidden)).containsExactly("/api-reports");
    }

    @Test
    void unrestrictedTesterSeesSameAsBefore() {
        List<String> hidden = PermissionRules.hiddenRoutes(PlatformRole.TESTER, null);
        assertThat(hidden).containsExactly("/platform-users");
    }

    @Test
    void nestedRouteCanBeVisibleWhileParentHidden() {
        List<String> hidden = PermissionRules.hiddenRoutes(PlatformRole.TESTER,
                List.of("/reports/esports/performance"));
        assertThat(hidden).contains("/reports");
        assertThat(PermissionRules.visibleRoutes(hidden)).containsExactly("/reports/esports/performance");
    }

    private static PlatformUser user(PlatformRole role, String modules) {
        PlatformUser u = new PlatformUser();
        u.setRole(role.name());
        u.setAllowedModules(modules);
        return u;
    }
}
