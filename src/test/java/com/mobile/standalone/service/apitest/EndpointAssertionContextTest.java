package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndpointAssertionContextTest {

    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);

    @Test
    void 文档字段不参与断言素材() {
        ApiEndpoint e = new ApiEndpoint();
        e.setFieldsSnapshot("[\"codeFromCode\"]");

        when(endpoints.responseFieldsOf(e)).thenReturn(List.of("codeFromCode"));
        when(endpoints.captureSamplesOf(e)).thenReturn(Map.of());
        when(endpoints.captureMetaOf(e)).thenReturn(Map.of());
        when(endpoints.docMetaOf(e)).thenReturn(Map.of(
                "summary", "好友列表",
                "fields", List.of("docOnlyField", "anotherDocField"),
                "mysqlTables", List.of("friend")));

        var ctx = EndpointAssertionContext.of(e, endpoints);

        assertThat(ctx.responseFields()).containsExactly("codeFromCode");
        assertThat(ctx.responseFields()).doesNotContain("docOnlyField");
        assertThat(ctx.captureSamples()).isEmpty();
        assertThat(ctx.docReference()).contains("文档参考").contains("未验证");
    }

    @Test
    void 抓包样本进入断言素材() {
        ApiEndpoint e = new ApiEndpoint();
        when(endpoints.responseFieldsOf(e)).thenReturn(List.of("code"));
        when(endpoints.captureSamplesOf(e)).thenReturn(Map.of("code", 0));
        when(endpoints.captureMetaOf(e)).thenReturn(Map.of("samples", Map.of("code", 0)));
        when(endpoints.docMetaOf(e)).thenReturn(Map.of());

        var ctx = EndpointAssertionContext.of(e, endpoints);

        assertThat(ctx.captureSamples()).containsEntry("code", 0);
        assertThat(ctx.noteHint()).contains("抓包实测");
    }
}
