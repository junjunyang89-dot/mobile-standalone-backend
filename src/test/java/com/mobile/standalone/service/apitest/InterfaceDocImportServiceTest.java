package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口文档导入。
 * <p>
 * 文档补的是抓包补不了的那一半：必填、默认值、边界、哪些字段可空。
 * 这些进不了库，用例就只能是「把这次抓到的调用重放一遍」。
 */
class InterfaceDocImportServiceTest {

    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);
    private final InterfaceDocImportService service = new InterfaceDocImportService(endpoints);

    private InputStream doc() {
        return getClass().getResourceAsStream("/doc/app-8999.docx");
    }

    @Test
    void 把文档里的接口写进清单() throws Exception {
        when(endpoints.applyDocMeta(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ApiEndpoint());

        var r = service.importDoc("app-global-data", doc(), "APP-8999接口文档.docx");

        assertThat(r.matched()).isGreaterThanOrEqualTo(8);
        assertThat(r.unmatched()).isEmpty();
        assertThat(r.branches()).contains("fch/20260821/APP-8999");
    }

    @Test
    void 参数约束和可空字段都带进去() throws Exception {
        when(endpoints.applyDocMeta(anyString(), anyString(), anyString(), any()))
                .thenReturn(new ApiEndpoint());

        service.importDoc("app-global-data", doc(), "d.docx");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(endpoints, org.mockito.Mockito.atLeastOnce())
                .applyDocMeta(anyString(), anyString(), anyString(), cap.capture());

        var friendList = cap.getAllValues().stream()
                .filter(m -> "/appdatacenter/deadlock/data/friend/list".equals(m.get("path")))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) friendList.get("params");
        var mode = params.stream().filter(p -> "mode".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(mode.get("default")).isEqualTo("1");
        assertThat(mode.get("required")).isEqualTo(false);
        // 原文说明必须留住：「其它回落 1」归纳成结构化字段一定丢信息
        assertThat(String.valueOf(mode.get("note"))).contains("回落");

        // 可空字段单独拎一份：生成断言时要跳过它们，
        // 埋在 fieldSpecs 里的话每个用到的地方都得自己遍历，总有一处会忘
        @SuppressWarnings("unchecked")
        List<String> nullable = (List<String>) friendList.get("nullableFields");
        assertThat(nullable).contains("personaname", "avatar").doesNotContain("steamId");
    }

    @Test
    void 清单里没有的接口要报出来不能吞掉() throws Exception {
        // 文档里有、清单里没有，多半是这个接口还没被抓包或对比分析覆盖到——那是漏测点
        when(endpoints.applyDocMeta(anyString(), anyString(), anyString(), any())).thenReturn(null);

        var r = service.importDoc("app-global-data", doc(), "d.docx");

        assertThat(r.matched()).isZero();
        assertThat(r.unmatched()).isNotEmpty();
    }

    @Test
    void 没选项目直接拦下来() {
        assertThatThrownBy(() -> service.importDoc("  ", doc(), "d.docx"))
                .hasMessageContaining("项目");
    }

    @Test
    void 解析不出接口时说清楚要什么格式() {
        // 传错文件时给出「该长什么样」，而不是一句「导入失败」
        assertThatThrownBy(() -> service.importDoc(
                "p", new java.io.ByteArrayInputStream("x".getBytes()), "x.docx"))
                .hasMessageContaining("Confluence");
    }
}
