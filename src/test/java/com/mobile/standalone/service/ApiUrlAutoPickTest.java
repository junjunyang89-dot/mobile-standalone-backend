package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiUrlEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiUrlAutoPickTest {

    private static ApiUrlEntry url(String id, String env, String base, Boolean enabled) {
        ApiUrlEntry u = new ApiUrlEntry();
        u.setId(id);
        u.setEnvironment(env);
        u.setBaseUrl(base);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    void sameEnvironmentBeatsAnyEvenWhenAnyIsSortedFirst() {
        List<ApiUrlEntry> list = List.of(
                url("any", "ANY", "https://any.example", true),
                url("prod", "PROD", "https://gwapi.pwesports.cn", true));
        assertThat(ApiUrlService.pick(list, "PROD").getId()).isEqualTo("prod");
    }

    @Test
    void firstInSortOrderWinsWithinSameEnvironment() {
        List<ApiUrlEntry> list = List.of(
                url("gw", "TEST", "https://gwapi-staging.pwesports.cn", true),
                url("host", "TEST", "http://172.16.108.178:8103", true));
        assertThat(ApiUrlService.pick(list, "TEST").getId()).isEqualTo("gw");
    }

    @Test
    void fallsBackToAnyThenNull() {
        assertThat(ApiUrlService.pick(List.of(url("any", "ANY", "https://a", true)), "TEST").getId())
                .isEqualTo("any");
        assertThat(ApiUrlService.pick(List.of(url("t", "TEST", "https://t", true)), "PROD")).isNull();
        assertThat(ApiUrlService.pick(List.of(), "PROD")).isNull();
    }

    @Test
    void skipsDisabledAndBlank() {
        List<ApiUrlEntry> list = List.of(
                url("off", "PROD", "https://off", false),
                url("blank", "PROD", " ", true),
                url("ok", "PROD", "https://ok", null));
        assertThat(ApiUrlService.pick(list, "PROD").getId()).isEqualTo("ok");
    }
}
