package com.mobile.standalone.service;

import com.mobile.standalone.dto.WebTestDtos.CaseSpec;
import com.mobile.standalone.entity.UiH5Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * H5 的 Cookie 登录态。
 * <p>
 * 实测「国服战令」这类活动页：静态资源全加载成功、一个接口都没请求、页面停在「去登录」。
 * 前端 bundle 里登录态取自 {@code c.get("PVP_APP_TOKEN")} 等三个 Cookie，
 * 而配置只有 TOKEN_QUERY，方式对不上——<b>填了 token 也不生效，且全程不报错</b>。
 * 这类静默失效最难查，所以这里盯的就是它。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebTestCookieAuthTest {

    @Mock
    private UiH5PageService uiH5PageService;

    @InjectMocks
    private WebTestService service;

    private static UiH5Page page(String authType, String token, String cookies) {
        UiH5Page p = new UiH5Page();
        p.setId("h5-1");
        p.setName("国服战令");
        p.setUrl("http://news-staging.wmpvp.com:8039/v3/act-seasonBenefit.html");
        p.setAuthType(authType);
        p.setAuthToken(token);
        p.setAuthCookies(cookies);
        return p;
    }

    private CaseSpec enrich(UiH5Page p) {
        when(uiH5PageService.get("h5-1")).thenReturn(p);
        CaseSpec c = new CaseSpec();
        c.setH5PageId("h5-1");
        ReflectionTestUtils.invokeMethod(service, "enrichFromH5Page", c);
        return c;
    }

    /**
     * 这条是核心：Cookie 模式下 auth_token 通常是空的，登录态整个在 cookie 里。
     * 原实现把认证方式的判断挂在「token 非空」下面，配了 cookie 也永远进不来。
     */
    @Test
    void cookie模式在没有token时也要生效() {
        CaseSpec c = enrich(page("TOKEN_COOKIE", null,
                "{\"PVP_APP_TOKEN\":\"t1\",\"PVP_APP_STEAMID\":\"s1\",\"PVP_APP_USERID\":\"u1\"}"));

        assertThat(c.getAuthMode()).isEqualTo("cookie");
        assertThat(c.getAuthCookies()).contains("PVP_APP_TOKEN");
    }

    @Test
    void query模式没有token时不该假装已配好登录态() {
        CaseSpec c = enrich(page("TOKEN_QUERY", null, null));

        // 没 token 的 query 模式等于没有登录态，标成 query 只会让人以为配好了
        assertThat(c.getAuthMode()).isEqualTo("none");
    }

    @Test
    void query模式有token时照旧() {
        assertThat(enrich(page("TOKEN_QUERY", "abc", null)).getAuthMode()).isEqualTo("query");
    }

    /** 下发给 Playwright 的是对象而不是字符串，那边不用再解析一次 */
    @Test
    void cookie配置解析成对象下发() {
        Map<String, Object> out = ReflectionTestUtils.invokeMethod(
                service, "parseCookies",
                "{\"PVP_APP_TOKEN\":\"t1\",\"PVP_APP_STEAMID\":\"s1\"}");

        assertThat(out).containsExactly(
                Map.entry("PVP_APP_TOKEN", "t1"),
                Map.entry("PVP_APP_STEAMID", "s1"));
    }

    @Test
    void 没配cookie时下发null而不是空对象() {
        assertThat((Object) ReflectionTestUtils.invokeMethod(service, "parseCookies", "")).isNull();
        assertThat((Object) ReflectionTestUtils.invokeMethod(service, "parseCookies", (String) null)).isNull();
    }

    /**
     * JSON 写错要当场报，别等到浏览器里。
     * 到那时候看到的只是「未登录」，根本看不出是配置格式的问题。
     */
    @Test
    void cookie配置格式错误当场报出来() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "parseCookies", "not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authCookies");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "parseCookies", "[1,2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 对象");
    }
}
