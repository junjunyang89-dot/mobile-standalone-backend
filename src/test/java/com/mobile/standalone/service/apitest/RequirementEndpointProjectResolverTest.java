package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementEndpointProjectResolverTest {

    @Test
    void inferFromPath_gldDeadlock() {
        assertThat(RequirementEndpointProjectResolver.inferFromPath("/gld/deadlock/friend/list"))
                .isEqualTo("app-global-data");
        assertThat(RequirementEndpointProjectResolver.inferFromPath("/api/gld/deadlock/x"))
                .isEqualTo("app-global-data");
    }

    @Test
    void inferFromPath_appdatacenter() {
        assertThat(RequirementEndpointProjectResolver.inferFromPath("/appdatacenter/user/info"))
                .isEqualTo("app-data-center");
    }

    @Test
    void inferFromPath_unknown() {
        assertThat(RequirementEndpointProjectResolver.inferFromPath("/changelog/get")).isNull();
        assertThat(RequirementEndpointProjectResolver.inferFromPath("")).isNull();
    }

    @Test
    void resolve_prefersExplicit() {
        assertThat(RequirementEndpointProjectResolver.resolve("data-center", "/other/x"))
                .isEqualTo("app-data-center");
    }

    @Test
    void resolve_fallsBackToPath() {
        assertThat(RequirementEndpointProjectResolver.resolve(null, "/gld/foo"))
                .isEqualTo("app-global-data");
    }
}
