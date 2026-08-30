package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从涉及接口生成用例。
 */
class RequirementCaseGeneratorTest {

    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);
    private final ApiProjectService projects = mock(ApiProjectService.class);
    private final ApiTestCaseService cases = mock(ApiTestCaseService.class);
    private final ApiTestCollectionService collections = mock(ApiTestCollectionService.class);
    private final RequirementEndpointService links = mock(RequirementEndpointService.class);

    private final com.mobile.standalone.mapper.ApiTestCaseMapper caseMapper =
            mock(com.mobile.standalone.mapper.ApiTestCaseMapper.class);

    private final RequirementCaseGenerator gen =
            new RequirementCaseGenerator(endpoints, projects, cases, collections, links, caseMapper);

    private static final RequirementEndpointService.LinkRow LINKED =
            new RequirementEndpointService.LinkRow("re-1", "APP-8999", "PogPog-H5", "ep-1",
                    "GET", "/gld/deadlock/friend/list", null, null);

    @BeforeEach
    void setUp() {
        ApiEndpoint e = new ApiEndpoint();
        e.setId("ep-1");
        e.setHttpMethod("GET");
        e.setPath("/gld/deadlock/friend/list");
        when(endpoints.get("ep-1")).thenReturn(e);
        when(cases.createCase(any())).thenAnswer(i -> {
            ApiTestCase c = i.getArgument(0);
            c.setId("case-1");
            return c;
        });
    }

    private ApiTestCollection col(String id, String name) {
        ApiTestCollection c = new ApiTestCollection();
        c.setId(id);
        c.setName(name);
        return c;
    }

    @Test
    void 没有集合时按需求单自动建一个() {
        when(collections.findExistingTestCollection(
                eq("PogPog-H5"), eq("APP-8999"), eq("9.3 海外客户端"), isNull()))
                .thenReturn(null);
        when(collections.findOrCreateTestCollection(
                eq("PogPog-H5"), eq("APP-8999"), eq("9.3 海外客户端"), isNull()))
                .thenReturn(col("col-new", "APP-8999-接口"));
        when(links.get("re-1")).thenReturn(LINKED);

        var r = gen.generate(new RequirementCaseGenerator.Request(
                "re-1", null, "9.3 海外客户端", null));

        assertThat(r.collectionCreated()).isTrue();
        assertThat(r.collectionName()).isEqualTo("APP-8999-接口");
        verify(collections).assign(eq("col-new"), anyList());
    }

    @Test
    void 已经有集合就直接进去不重复建() {
        when(collections.findExistingTestCollection(
                eq("PogPog-H5"), eq("APP-8999"), eq("9.3 海外客户端"), isNull()))
                .thenReturn(col("col-old", "APP-8999-接口"));
        when(collections.findOrCreateTestCollection(
                eq("PogPog-H5"), eq("APP-8999"), eq("9.3 海外客户端"), isNull()))
                .thenReturn(col("col-old", "APP-8999-接口"));
        when(links.get("re-1")).thenReturn(LINKED);

        var r = gen.generate(new RequirementCaseGenerator.Request("re-1", null, "9.3 海外客户端", null));

        assertThat(r.collectionCreated()).isFalse();
        assertThat(r.collectionId()).isEqualTo("col-old");
        verify(collections, never()).create(any());
    }

    @Test
    void 生成的用例只在测试环境跑() {
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(links.get("re-1")).thenReturn(LINKED);

        gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(ApiTestCase.class);
        verify(cases).createCase(captor.capture());
        assertThat(captor.getValue().getEnvironment()).isEqualTo("TEST");
        assertThat(captor.getValue().getEndpointId()).isEqualTo("ep-1");
        assertThat(captor.getValue().getRequirementRef()).isEqualTo("APP-8999");
    }

    @Test
    void 没归项目的接口拦下来并说清楚怎么办() {
        when(links.get("re-1")).thenReturn(new RequirementEndpointService.LinkRow(
                "re-1", "APP-8999", null, null, "GET", "/x", null, null));

        assertThatThrownBy(() -> gen.generate(
                new RequirementCaseGenerator.Request("re-1", null, null, null)))
                .hasMessageContaining("还没归项目");

        verify(cases, never()).createCase(any());
    }

    @Test
    void 老数据没有endpointId时先收进清单再回写() {
        when(links.get("re-1")).thenReturn(new RequirementEndpointService.LinkRow(
                "re-1", "APP-8999", "PogPog-H5", null, "GET", "/gld/deadlock/friend/list", null, null));
        when(endpoints.ensureEndpointFor(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("ep-1");
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));

        gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        verify(links).bindEndpoint("re-1", "ep-1");
    }

    @Test
    void 分支对比来的用分支命名而不是硬塞一个单号() {
        assertThat(ApiTestCollectionService.buildTestCollectionName(null, "backend-0816"))
                .isEqualTo("backend-0816 · 分支对比");
        assertThat(ApiTestCollectionService.buildTestCollectionName("APP-8999", null))
                .isEqualTo("APP-8999-接口");
    }

    @Test
    void 点两次生成不会造出两条一样的用例() {
        // 重复的用例是纯垃圾：跑起来重复、覆盖率虚高，人也分不出该编辑哪一条
        ApiTestCase already = new ApiTestCase();
        already.setId("case-old");
        already.setName("GET friend/list");
        already.setCreatedBy("涉及接口");
        when(caseMapper.findByEndpointId("ep-1")).thenReturn(java.util.List.of(already));
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(links.get("re-1")).thenReturn(LINKED);

        var r = gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        assertThat(r.caseId()).isEqualTo("case-old");
        assertThat(r.note()).contains("已经生成过");
        verify(cases, never()).createCase(any());
    }

    @Test
    void 人手写的同路径用例不算重复() {
        // 那是人有意写的第二条，拿它顶掉自动生成的，等于悄悄改了人的东西
        ApiTestCase handWritten = new ApiTestCase();
        handWritten.setId("case-hand");
        handWritten.setCreatedBy("杨工");
        when(caseMapper.findByEndpointId("ep-1")).thenReturn(java.util.List.of(handWritten));
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(links.get("re-1")).thenReturn(LINKED);

        gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        verify(cases).createCase(any());
    }

    @Test
    void 导了文档就按参数约束多造几条边界用例() {
        // 只有抓包时用例是「把这次调用重放一遍」，边界一条都验不到
        var docParams = java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("name", "steamId", "required", true, "note", "非法 → PARAM_ERROR"),
                java.util.Map.of("name", "mode", "required", false, "default", "1",
                        "note", "1=标准，4=天梯；其它回落 1"));
        var e = new ApiEndpoint();
        e.setId("ep-1");
        e.setHttpMethod("GET");
        e.setPath("/gld/deadlock/friend/list");
        e.setDocMeta("{\"params\":" + toJson(docParams) + ",\"nullableFields\":[]}");
        e.setCaptureMeta("{\"queryParams\":{\"steamId\":\"103561140\",\"mode\":\"4\"}}");
        when(endpoints.get("ep-1")).thenReturn(e);
        when(endpoints.docMetaOf(any())).thenCallRealMethod();
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(links.get("re-1")).thenReturn(LINKED);

        var r = gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        // 主用例 + 边界用例，不是只有一条
        assertThat(r.variantCount()).isGreaterThan(0);
        verify(cases, org.mockito.Mockito.atLeast(2)).createCase(any());
    }

    @Test
    void 没导文档时不硬造边界用例() {
        // 那时唯一的信息来源是抓包，而抓包造不出边界。硬造的话期望是什么谁也不知道
        when(collections.findExistingTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(collections.findOrCreateTestCollection(anyString(), anyString(), any(), any()))
                .thenReturn(col("c", "c"));
        when(links.get("re-1")).thenReturn(LINKED);

        var r = gen.generate(new RequirementCaseGenerator.Request("re-1", null, null, null));

        assertThat(r.variantCount()).isZero();
    }

    @Test
    void 参数不传时query里就没有这个键() {
        // 用空串会变成 steamId=""，那是「传了空值」，和「不传」是两回事
        assertThat(RequirementCaseGenerator.toQueryString(
                new java.util.LinkedHashMap<>(java.util.Map.of("mode", "4"))))
                .isEqualTo("{\"mode\":\"4\"}")
                .doesNotContain("steamId");
        assertThat(RequirementCaseGenerator.toQueryString(java.util.Map.of())).isNull();
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
