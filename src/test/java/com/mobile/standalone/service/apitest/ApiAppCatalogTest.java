package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAppCatalogTest {

    @Test
    void canonicalCode_normalizesLegacyAndGitlabNames() {
        assertThat(ApiAppCatalog.canonicalCode("app-seckill")).isEqualTo("app-seckill");
        assertThat(ApiAppCatalog.canonicalCode("seckill")).isEqualTo("app-seckill");
        assertThat(ApiAppCatalog.canonicalCode("data-center")).isEqualTo("app-data-center");
        assertThat(ApiAppCatalog.canonicalCode("app_esports_api")).isEqualTo("app-esports-api");
        assertThat(ApiAppCatalog.canonicalCode("app-task-cneter")).isEqualTo("app-task-center");
    }

    @Test
    void appKeyOfProject_mapsToWmpvp() {
        assertThat(ApiAppCatalog.appKeyOfProject("app-engine")).isEqualTo("wmpvp");
        assertThat(ApiAppCatalog.appKeyOfProject("app-data-center")).isEqualTo("wmpvp");
        assertThat(ApiAppCatalog.appKeyOfProject("unknown-foo")).isEqualTo("other");
    }

    @Test
    void matchProjectCodes_includesLegacyAlias() {
        assertThat(ApiAppCatalog.matchProjectCodes("app-data-center"))
                .contains("app-data-center", "data-center");
    }
}
