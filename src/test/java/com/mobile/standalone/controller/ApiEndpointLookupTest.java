package com.mobile.standalone.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiEndpointLookupTest {

    @Test
    void 断言类型按顺序读出_坏JSON当没有() {
        assertThat(ApiEndpointController.assertionTypes(
                "[{\"type\":\"status\",\"equals\":200},{\"type\":\"jsonPath\",\"path\":\"$.code\"}]"))
                .containsExactly("status", "jsonPath");
        assertThat(ApiEndpointController.assertionTypes("坏的")).isEmpty();
        assertThat(ApiEndpointController.assertionTypes(null)).isEmpty();
    }
}
