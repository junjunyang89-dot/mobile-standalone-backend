package com.mobile.standalone.auth;

import com.mobile.standalone.config.OpenApiProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开放接口能下发真机任务，鉴权上任何一个口子都等于把设备交给内网所有人。
 */
class OpenApiAuthFilterTest {

    private static OpenApiProperties props(String... nameKeyPairs) {
        OpenApiProperties p = new OpenApiProperties();
        for (int i = 0; i + 1 < nameKeyPairs.length; i += 2) {
            OpenApiProperties.Client c = new OpenApiProperties.Client();
            c.setName(nameKeyPairs[i]);
            c.setKey(nameKeyPairs[i + 1]);
            p.getClients().add(c);
        }
        return p;
    }

    private static AuthFilter filter(OpenApiProperties p, boolean bootstrapping) {
        AuthTokenService tokens = mock(AuthTokenService.class);
        when(tokens.verify(anyString())).thenReturn(null);
        PlatformUserService users = mock(PlatformUserService.class);
        when(users.isBootstrapping()).thenReturn(bootstrapping);
        return new AuthFilter(tokens, users, new OpenApiAuth(p));
    }

    private static MockHttpServletRequest req(String key) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/open/v1/runs");
        if (key != null) {
            r.addHeader("X-API-Key", key);
        }
        return r;
    }

    @Test
    void 正确的Key放行并带上调用方名() throws Exception {
        MockHttpServletRequest r = req("k-node");
        FilterChain chain = mock(FilterChain.class);

        filter(props("node-admin", "k-node"), false).doFilter(r, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        assertThat(r.getAttribute(OpenApiAuth.ATTR_CLIENT)).isEqualTo("node-admin");
    }

    @Test
    void 错误的Key拦401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props("node-admin", "k-node"), false).doFilter(req("wrong"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 不带Key拦401() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props("node-admin", "k-node"), false).doFilter(req(null), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /** 没配 key 绝不能当成「不鉴权」 */
    @Test
    void 没配Key时整个开放接口关闭() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(new OpenApiProperties(), false).doFilter(req("anything"), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(any(), any());
    }

    /** 平台还没建账号时其它接口全放行，开放接口不能跟着放 */
    @Test
    void 未初始化账号时开放接口仍要Key() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props("node-admin", "k-node"), true).doFilter(req(null), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 停用的Key拦401() throws Exception {
        OpenApiProperties p = props("node-admin", "k-node", "old", "k-old");
        p.getClients().get(1).setEnabled(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(p, false).doFilter(req("k-old"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    /** API Key 不能顺带打开内部接口 */
    @Test
    void Key对内部接口无效() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/ui-flows");
        r.addHeader("X-API-Key", "k-node");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(props("node-admin", "k-node"), false).doFilter(r, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 前缀要按段匹配() {
        assertThat(OpenApiAuth.isOpenPath("/open/v1/runs")).isTrue();
        assertThat(OpenApiAuth.isOpenPath("/open/v1")).isTrue();
        assertThat(OpenApiAuth.isOpenPath("/open/v10/runs")).isFalse();
        assertThat(OpenApiAuth.isOpenPath("/debug-tests/open")).isFalse();
    }
}
