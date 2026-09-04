package com.mobile.standalone.service.apitest;

import com.mobile.standalone.controller.PacketCaptureController;
import com.mobile.standalone.service.capture.PacketCaptureService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抓包挂上来的接口必须归到项目。
 * <p>
 * 不归项目的话，接口进不了接口清单，「去生成用例」点过去是空的、覆盖率永远 0%，
 * 而页面上还写着「4 个接口没有启用的用例」——让人以为是自己没点，
 * 其实是根本生成不了。这种「看起来成了，其实没成」比直接报错难查得多。
 */
class CapturedEndpointProjectTest {

    private final ApiEndpointService endpoints = mock(ApiEndpointService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final com.mobile.standalone.service.capture.PacketCaptureService capture =
            mock(com.mobile.standalone.service.capture.PacketCaptureService.class);
    private final RequirementEndpointService service =
            new RequirementEndpointService(jdbc, endpoints, capture);

    private static final List<RequirementEndpointService.Link> ONE = List.of(
            new RequirementEndpointService.Link(
                    null, "GET", "/gld/deadlock/friend/list", "gateway-qa.arca42.com", null, null));

    @Test
    void 选了项目就把接口收进清单() {
        when(endpoints.ensureEndpointFor(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("ep-1");

        int n = service.linkCaptured("APP-8999", "PogPog-H5", ONE);

        assertThat(n).isEqualTo(1);
        verify(endpoints).ensureEndpointFor(
                "PogPog-H5", "GET", "/gld/deadlock/friend/list", null, "gateway-qa.arca42.com");
    }

    @Test
    void 没选项目但路径可识别时按前缀推断() {
        when(endpoints.ensureEndpointFor(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("ep-1");

        int n = service.linkCaptured("APP-8999", "  ", ONE);

        assertThat(n).isEqualTo(1);
        verify(endpoints).ensureEndpointFor(
                "app-global-data", "GET", "/gld/deadlock/friend/list", null, "gateway-qa.arca42.com");
    }

    @Test
    void 路径认不出且没选项目就不挂() {
        var unknown = List.of(new RequirementEndpointService.Link(
                null, "GET", "/changelog/get", null, null, null));

        int n = service.linkCaptured("APP-8999", null, unknown);

        assertThat(n).isZero();
        verify(endpoints, never()).ensureEndpointFor(any(), any(), any(), any(), any());
    }

    @Test
    void 补挂时路径认不出就拦下来() {
        var controller = new PacketCaptureController(mock(PacketCaptureService.class), service, endpoints,
                mock(com.mobile.standalone.service.ApiTestCaseService.class),
                mock(com.mobile.standalone.service.capture.CaptureStore.class));

        ResponseEntity<?> r = controller.link(Map.of(
                "requirementNo", "APP-8999",
                "flows", List.of(Map.of("method", "GET", "path", "/changelog/get"))));

        assertThat(r.getStatusCode().is4xxClientError()).isTrue();
        assertThat(String.valueOf(r.getBody())).contains("项目");
        verify(endpoints, never()).ensureEndpointFor(any(), any(), any(), any(), any());
    }

    @Test
    void 补挂时gld路径没选项目也能挂上() {
        when(endpoints.ensureEndpointFor(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("ep-1");
        var controller = new PacketCaptureController(mock(PacketCaptureService.class), service, endpoints,
                mock(com.mobile.standalone.service.ApiTestCaseService.class),
                mock(com.mobile.standalone.service.capture.CaptureStore.class));

        ResponseEntity<?> r = controller.link(Map.of(
                "requirementNo", "APP-8999",
                "flows", List.of(Map.of("method", "GET", "path", "/gld/deadlock/friend/list"))));

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        verify(endpoints).ensureEndpointFor(
                "app-global-data", "GET", "/gld/deadlock/friend/list", null, null);
    }
}
