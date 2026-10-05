package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抓包路径带网关前缀、代码路径不带——同一个接口不能因此变成两行。
 */
class EndpointIdentityTest {

    private static ApiEndpoint row(String id, String method, String path) {
        ApiEndpoint e = new ApiEndpoint();
        e.setId(id);
        e.setProject("p");
        e.setHttpMethod(method);
        e.setPath(path);
        e.setStatus("ACTIVE");
        return e;
    }

    private static ApiEndpoint captured(String id, String method, String path) {
        ApiEndpoint e = row(id, method, path);
        e.setCaptureMeta("{\"capturedAt\":\"2026-09-01\"}");
        return e;
    }

    private static ApiEndpoint fromCode(String id, String method, String path) {
        ApiEndpoint e = row(id, method, path);
        e.setCodePath(path);
        e.setSourceFile("src/main/java/X.java");
        return e;
    }

    private static SourceEndpointScanner.Scanned scanned(String method, String path) {
        return new SourceEndpointScanner.Scanned(method, path, "C.m", "说明", "src/main/java/C.java", null);
    }

    @Test
    void 网关前缀() {
        assertThat(SourceControllerMatcher.gatewayPrefix("/steamcn/community/task/signIn", "/community/task/signIn"))
                .isEqualTo("/steamcn");
        assertThat(SourceControllerMatcher.gatewayPrefix("/a/b", "/a/b")).isEmpty();
        assertThat(SourceControllerMatcher.gatewayPrefix("/gw/user/123/info", "/user/{id}/info")).isEqualTo("/gw");
        // 段要完整对上
        assertThat(SourceControllerMatcher.gatewayPrefix("/gw/xtask/signIn", "/task/signIn")).isNull();
        // 一段的代码路径不认前缀，尾巴是 /list 的太多
        assertThat(SourceControllerMatcher.gatewayPrefix("/gw/list", "/list")).isNull();
        // 前缀超过两段就不像网关了
        assertThat(SourceControllerMatcher.gatewayPrefix("/a/b/c/x/y", "/x/y")).isNull();
    }

    @Test
    void 占多数的前缀才算() {
        assertThat(SourceControllerMatcher.dominantPrefix(List.of("/gw", "/gw", "/other"))).isEqualTo("/gw");
        assertThat(SourceControllerMatcher.dominantPrefix(List.of("/gw", "/other"))).isNull();
        assertThat(SourceControllerMatcher.dominantPrefix(List.of())).isNull();
        assertThat(SourceControllerMatcher.dominantPrefix(List.of("", "", "/gw"))).isEmpty();
    }

    @Test
    void 抓包落到源码接口上_路径改成实际调用的() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint code = fromCode("ep-code", "POST", "/community/task/signIn");
        when(mapper.listByProject("p", "POST")).thenReturn(List.of(code));

        String id = new ApiEndpointService(mapper, null)
                .ensureEndpointFor("p", "POST", "/steamcn/community/task/signIn", null, null);

        assertThat(id).isEqualTo("ep-code");
        verify(mapper).updatePath("ep-code", "/steamcn/community/task/signIn");
        verify(mapper, never()).insert(any());
    }

    @Test
    void 已经抓过包的接口不改路径_只挂上() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint cap = captured("ep-cap", "POST", "/steamcn/community/task/signIn");
        cap.setCodePath("/community/task/signIn");
        when(mapper.listByProject("p", "POST")).thenReturn(List.of(cap));

        String id = new ApiEndpointService(mapper, null)
                .ensureEndpointFor("p", "POST", "/community/task/signIn", null, null);

        assertThat(id).isEqualTo("ep-cap");
        verify(mapper, never()).updatePath(anyString(), anyString());
        verify(mapper, never()).insert(any());
    }

    @Test
    void 两个一样长的代码路径都对得上时不猜_另建一条() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint a = fromCode("ep-a", "GET", "/x/list");
        ApiEndpoint b = fromCode("ep-b", "GET", "/x/list");
        when(mapper.listByProject("p", "GET")).thenReturn(List.of(a, b));

        new ApiEndpointService(mapper, null).ensureEndpointFor("p", "GET", "/gw/x/list", null, null);

        verify(mapper).insert(any());
    }

    @Test
    void 存量重复配对() {
        ApiEndpoint cap = captured("A", "POST", "/steamcn/community/task/signIn");
        ApiEndpoint code = fromCode("B", "POST", "/community/task/signIn");
        ApiEndpoint other = fromCode("C", "POST", "/community/task/other");
        var r = ApiEndpointService.findDuplicates(List.of(cap, code, other));

        assertThat(r.pairs()).hasSize(1);
        assertThat(r.pairs().get(0).keepId()).isEqualTo("A");
        assertThat(r.pairs().get(0).dropId()).isEqualTo("B");
        assertThat(r.ambiguous()).isEmpty();
    }

    @Test
    void 一条源码接口对上两条抓包接口时交给人() {
        ApiEndpoint a1 = captured("A1", "GET", "/gw1/user/info");
        ApiEndpoint a2 = captured("A2", "GET", "/gw2/user/info");
        ApiEndpoint code = fromCode("B", "GET", "/user/info");
        var r = ApiEndpointService.findDuplicates(List.of(a1, a2, code));

        assertThat(r.pairs()).isEmpty();
        assertThat(r.ambiguous()).hasSize(1);
        assertThat(r.ambiguous().get(0).paths()).containsExactlyInAnyOrder("/gw1/user/info", "/gw2/user/info");
    }

    @Test
    void 方法不同不算重复() {
        var r = ApiEndpointService.findDuplicates(List.of(
                captured("A", "POST", "/gw/user/info"), fromCode("B", "GET", "/user/info")));
        assertThat(r.pairs()).isEmpty();
    }

    @Test
    void 扫描_抓包接口认上代码_新接口补网关前缀() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint cap1 = captured("A", "POST", "/steamcn/community/task/signIn");
        ApiEndpoint cap2 = captured("B", "GET", "/steamcn/community/task/list");
        when(mapper.search(any(), any(), any(), any())).thenReturn(new ArrayList<>(List.of(cap1, cap2)));

        var r = new ApiEndpointService(mapper, null).upsertFromSource("p", List.of(
                scanned("POST", "/community/task/signIn"),
                scanned("GET", "/community/task/list"),
                scanned("GET", "/community/task/detail")), null);

        assertThat(r.linked()).isEqualTo(2);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.prefix()).isEqualTo("/steamcn");
        ArgumentCaptor<ApiEndpoint> ins = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).insert(ins.capture());
        assertThat(ins.getValue().getPath()).isEqualTo("/steamcn/community/task/detail");
        assertThat(ins.getValue().getCodePath()).isEqualTo("/community/task/detail");
    }

    @Test
    void 按基线扫描_代码里没有的标出来_分支上新加的不标() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint goneOne = fromCode("G", "GET", "/old/api");
        ApiEndpoint branchNew = fromCode("N", "GET", "/new/api");
        branchNew.setSourceBranch("feat/x");
        ApiEndpoint manual = row("M", "GET", "/hand/written");
        when(mapper.search(any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(goneOne, branchNew, manual)));

        var r = new ApiEndpointService(mapper, null)
                .upsertFromSource("p", List.of(scanned("GET", "/other/api")), "origin/master@abc");

        assertThat(r.gone()).isEqualTo(1);
        verify(mapper).updateGone(eq("G"), any(), eq("origin/master@abc"));
        verify(mapper, never()).updateGone(eq("N"), any(), any());
        verify(mapper, never()).updateGone(eq("M"), any(), any());
    }

    @Test
    void 以前进了清单的基础接口扫描时标废弃且不再标没有() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint offline = fromCode("O", "GET", "/basic/offline");
        ApiEndpoint swagger = captured("S", "GET", "/acty/swagger-ui.html");
        ApiEndpoint biz = fromCode("B", "GET", "/biz/api");
        when(mapper.search(any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(offline, swagger, biz)));

        var r = new ApiEndpointService(mapper, null)
                .upsertFromSource("p", List.of(scanned("GET", "/biz/api")), "origin/master@abc");

        verify(mapper).updateRetired(eq("O"), any(), eq(ApiEndpointService.INFRA_RETIRED_BY));
        verify(mapper).updateRetired(eq("S"), any(), eq(ApiEndpointService.INFRA_RETIRED_BY));
        verify(mapper, never()).updateRetired(eq("B"), any(), any());
        assertThat(r.gone()).isZero();
    }

    @Test
    void 扫本地目录不标没有() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.search(any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(List.of(fromCode("G", "GET", "/old/api"))));

        var r = new ApiEndpointService(mapper, null).upsertFromSource("p", List.of(scanned("GET", "/x/y")), null);

        assertThat(r.gone()).isZero();
        verify(mapper, never()).updateGone(anyString(), any(), any());
    }

    @Test
    void 重新出现的清掉标记() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint back = fromCode("G", "GET", "/old/api");
        back.setGoneAt(java.time.LocalDateTime.now());
        when(mapper.search(any(), any(), any(), any())).thenReturn(new ArrayList<>(List.of(back)));

        var r = new ApiEndpointService(mapper, null)
                .upsertFromSource("p", List.of(scanned("GET", "/old/api")), "origin/master@abc");

        assertThat(r.revived()).isEqualTo(1);
        verify(mapper).updateGone("G", null, null);
    }
}
