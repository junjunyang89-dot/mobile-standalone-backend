package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathProjectGuesserTest {

    private static ApiProject proj(String code, String name) {
        ApiProject p = new ApiProject();
        p.setCode(code);
        p.setName(name);
        return p;
    }

    private static ApiEndpoint ep(String project, String path) {
        ApiEndpoint e = new ApiEndpoint();
        e.setProject(project);
        e.setPath(path);
        return e;
    }

    @Test
    void firstSegmentMatchingProjectCodeWins() {
        var g = new PathProjectGuesser(List.of(proj("app-user", "APP-USER"), proj("app-event", "APP-EVENT")),
                List.of(ep("app-event", "/appuser/a"), ep("app-event", "/appuser/b"), ep("app-event", "/appuser/c")));
        var guess = g.guess("/appuser/community/user/getUserSupportTeam");
        assertEquals("app-user", guess.project());
        assertEquals("路径 /appuser/ 和项目编码对得上", guess.reason());
    }

    @Test
    void otherwiseClearMajorityInCatalog() {
        var g = new PathProjectGuesser(List.of(proj("app-engine", "APP-ENGINE"), proj("app-event", "APP-EVENT")),
                List.of(ep("app-engine", "/steamcn/a"), ep("app-engine", "/steamcn/b"),
                        ep("app-engine", "/steamcn/c"), ep("app-event", "/steamcn/d"),
                        ep(CaptureCatalogService.INBOX, "/steamcn/e"), ep(CaptureCatalogService.INBOX, "/steamcn/f")));
        var guess = g.guess("/steamcn/community/x");
        assertEquals("app-engine", guess.project());
        assertEquals("清单里 /steamcn/ 开头的接口 3/4 个在这个项目", guess.reason());
    }

    @Test
    void noGuessWhenSplitGenericOrUnknown() {
        var g = new PathProjectGuesser(List.of(proj("p1", "P1"), proj("p2", "P2")),
                List.of(ep("p1", "/mix/a"), ep("p1", "/mix/b"), ep("p2", "/mix/c"), ep("p2", "/mix/d"),
                        ep("p1", "/solo/a")));
        assertNull(g.guess("/mix/x"));
        assertNull(g.guess("/solo/x"));
        assertNull(g.guess("/api/v1/x"));
        assertNull(g.guess("/nobody/x"));
        assertNull(g.guess(null));
    }

    @Test
    void directDomainDecidesBetweenCopiesInSeveralRepos() {
        var projects = List.of(proj("app-engine", "APP-ENGINE"), proj("app-activity", "APP-ACTIVITY"));
        var eps = List.of(ep("app-activity", "/steamcn/a"), ep("app-activity", "/steamcn/b"),
                ep("app-activity", "/steamcn/c"), ep("app-engine", "/steamcn/a"));
        var g = new PathProjectGuesser(projects, eps);
        var guess = g.guess("https://appengine-testing.wmpvp.com/steamcn/community/talk/getTalkList?x=1",
                "/steamcn/community/talk/getTalkList");
        assertEquals("app-engine", guess.project());
        assertEquals("域名 appengine-testing.wmpvp.com 和项目编码对得上", guess.reason());
        assertEquals("app-activity", g.guess("https://appactivity.wmpvp.com/steamcn/x", "/steamcn/x").project());
    }

    @Test
    void gatewayDomainFallsBackToRoute() {
        var projects = List.of(proj("app-community", "APP-COMMUNITY"), proj("datacenter", "数据中心"),
                proj("app-robot", "APP-ROBOT"));
        var routes = new java.util.LinkedHashMap<String, String>();
        routes.put("acty", "app-community");
        routes.put("appdatacenter", "app-data-center");
        routes.put("robot", "app-robot");
        routes.put("pvp-vip", "pvp-vip");
        var g = new PathProjectGuesser(projects, List.of(), routes);
        var acty = g.guess("https://gwapi-staging.pwesports.cn/acty/community/moments/getSquareMoments",
                "/acty/community/moments/getSquareMoments");
        assertEquals("app-community", acty.project());
        assertEquals("网关路由 /acty/** 转到服务 app-community", acty.reason());
        assertEquals("datacenter", g.guess("https://gwapi.pwesports.cn/appdatacenter/x", "/appdatacenter/x").project());
        assertEquals("app-robot", g.guess(null, "/robot/x").project());
        assertNull(g.guess("https://gwapi.pwesports.cn/pvp-vip/x", "/pvp-vip/x"));
    }

    @Test
    void genericOrIpHostIgnored() {
        var g = new PathProjectGuesser(List.of(proj("api", "API"), proj("p1", "P1")), List.of());
        assertNull(g.guess("https://api-staging.wmpvp.com/nobody/x", "/nobody/x"));
        assertNull(g.guess("http://140.206.230.189:8052/nobody/x", "/nobody/x"));
        assertNull(PathProjectGuesser.hostLabel("not a url ::"));
    }

    @Test
    void parseRoutesSkipsBrokenItems() {
        var r = CaptureCatalogService.parseRoutes(" acty = app-community ,bad,=x,y=,avo=app-video");
        assertEquals(2, r.size());
        assertEquals("app-community", r.get("acty"));
        assertEquals("app-video", r.get("avo"));
    }
}
