package com.mobile.standalone.auth;

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
 * filter 的<b>顺序</b>——规则表对不代表 filter 对。
 * <p>
 * 预检那个坑就是这样：{@code PermissionRules} 说 OPTIONS 只要只读，
 * 但 filter 在判角色<b>之前</b>先要令牌，而预检请求带不了任何请求头。
 * 结果是每个带 JSON body 的请求都先死在跨域上，报错还完全看不出跟鉴权有关。
 */
class AuthFilterTest {

    private PlatformUser user(PlatformRole role, boolean enabled) {
        PlatformUser u = new PlatformUser();
        u.setId("usr-1");
        u.setUsername("someone");
        u.setRole(role.name());
        u.setEnabled(enabled);
        return u;
    }

    private AuthFilter filterWith(boolean bootstrapping, String validToken, PlatformUser user) {
        AuthTokenService tokens = mock(AuthTokenService.class);
        when(tokens.verify(anyString())).thenReturn(null);
        if (validToken != null) {
            when(tokens.verify(validToken)).thenReturn("usr-1");
        }
        PlatformUserService users = mock(PlatformUserService.class);
        when(users.isBootstrapping()).thenReturn(bootstrapping);
        when(users.get(anyString())).thenReturn(user);
        return new AuthFilter(tokens, users);
    }

    private MockHttpServletRequest req(String method, String uri, String token) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        if (token != null) {
            r.addHeader("Authorization", "Bearer " + token);
        }
        return r;
    }

    /** 预检带不了 Authorization，拦掉就等于把所有跨域请求打死 */
    @Test
    void 跨域预检直接放行() throws Exception {
        AuthFilter filter = filterWith(false, "good", user(PlatformRole.ADMIN, true));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("OPTIONS", "/ui-flows", null), res, chain);

        verify(chain).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /** 一个账号都没有时整体放行，否则第一个管理员没法创建 */
    @Test
    void 未初始化时全放行() throws Exception {
        AuthFilter filter = filterWith(true, null, null);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("DELETE", "/ui-flows/x", null), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void 无令牌拦401() throws Exception {
        AuthFilter filter = filterWith(false, "good", user(PlatformRole.ADMIN, true));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("GET", "/ui-flows", null), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    /**
     * 令牌签名有效，但账号已被停用。
     * 令牌不存服务端，所以「停用立刻生效」全靠这里每次现查一遍账号状态——
     * 少了这一步，被停用的人手上那张令牌还能再用一周。
     */
    @Test
    void 账号停用后令牌立刻失效() throws Exception {
        AuthFilter filter = filterWith(false, "good", user(PlatformRole.ADMIN, false));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("GET", "/ui-flows", "good"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("已停用");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 角色不够拦403并说清缺什么() throws Exception {
        AuthFilter filter = filterWith(false, "good", user(PlatformRole.TESTER, true));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("DELETE", "/ui-flows/x", "good"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        // 提示必须说清「你是什么、这需要什么」，否则用户只看到一句操作失败
        assertThat(res.getContentAsString()).contains("测试").contains("管理员");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void 权限够就放行并挂上当前用户() throws Exception {
        PlatformUser admin = user(PlatformRole.ADMIN, true);
        AuthFilter filter = filterWith(false, "good", admin);
        MockHttpServletRequest request = req("DELETE", "/ui-flows/x", "good");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        // controller 靠这个属性知道「是谁在操作」
        assertThat(request.getAttribute(AuthFilter.ATTR_USER)).isSameAs(admin);
    }

    /** 截图是写进 img src 的，带不了请求头；拦下来报告里的图会全变裂图 */
    @Test
    void 静态截图不拦() throws Exception {
        AuthFilter filter = filterWith(false, "good", user(PlatformRole.ADMIN, true));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("GET", "/tmp/screenshots/task-1/1.png", null),
                new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }
}
