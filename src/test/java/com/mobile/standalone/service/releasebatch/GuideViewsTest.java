package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ConcurrencyRiskScanner;
import com.mobile.standalone.service.apichange.MustTestGuide;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndGroup;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndpointCard;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicItem;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideViewsTest {

    private static Target backend() {
        Target t = new Target();
        t.setRawProject("app-activity");
        t.setProjectCode("app-activity");
        t.setProjectName("app-activity");
        t.setKind("BACKEND");
        t.setBranch("dyl/20260903/yd");
        t.setAnalyzable(true);
        return t;
    }

    private static Ticket ticket(Target be) {
        Ticket t = new Ticket();
        t.setKey("APP-9078");
        t.setSummary("活动页新增抽奖入口");
        t.getTargets().add(be);
        return t;
    }

    private static EndpointChange ep(String type, String risk, String method, String path, int cases) {
        EndpointChange e = new EndpointChange();
        e.setChangeType(type);
        e.setRisk(risk);
        e.setHttpMethod(method);
        e.setPath(path);
        e.setTestCases(cases);
        return e;
    }

    private static EndpointCard card(Assessment a, String path) {
        return a.getEndpointCards().stream().filter(c -> c.getPath().equals(path)).findFirst().orElseThrow();
    }

    private static EndGroup impact(Assessment a, String key) {
        return a.getImpacts().stream().filter(g -> g.getKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void 接口卡片按结论排序_新接口带安全项_只改实现的按有无用例分重跑和补用例() {
        Target be = backend();
        Report r = new Report();
        EndpointChange rerun = ep("MODIFIED", "SAFE", "GET", "/activity/list", 3);
        rerun.getChangedImpls().add("ActivityService");
        EndpointChange add = ep("MODIFIED", "SAFE", "GET", "/activity/detail", 0);
        add.getChangedImpls().add("ActivityService");
        EndpointChange shape = ep("MODIFIED", "SAFE", "GET", "/activity/rank", 2);
        ParamChange p = new ParamChange();
        p.setChangeType("ADDED");
        p.setName("size");
        p.setNewType("Integer");
        shape.getParams().add(p);
        r.getEndpoints().addAll(List.of(rerun, add, shape,
                ep("ADDED", "SAFE", "POST", "/activity/lottery/draw", 0),
                ep("REMOVED", "BREAKING", "GET", "/activity/old", 1)));
        r.setMustTest(MustTestGuide.of(r));

        Assessment a = TestAssessmentBuilder.build("v", ticket(be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-05");

        assertEquals(GuideViews.VERSION, a.getGuideVersion());
        assertEquals(List.of("NEW", "OFFLINE", "SHAPE", "ADD", "RERUN"),
                a.getEndpointCards().stream().map(EndpointCard::getVerdict).toList());
        EndpointCard draw = card(a, "/activity/lottery/draw");
        assertTrue(draw.getSecurity().stream().anyMatch(s -> s.startsWith("登录态")));
        assertTrue(draw.getSecurity().stream().anyMatch(s -> s.startsWith("重复提交")), "写接口要测重复提交");
        assertTrue(card(a, "/activity/old").getAction().contains("1 条用例要停用"));
        assertTrue(card(a, "/activity/list").getAction().contains("重跑已有 3 条"));
        assertTrue(card(a, "/activity/detail").getAction().contains("生成用例到分支"));
        assertFalse(card(a, "/activity/detail").getTests().isEmpty(), "没有指引时也要给一条怎么测");
        assertTrue(card(a, "/activity/detail").getTests().stream().noneMatch(s -> s.startsWith("改动的实现")),
                "实现类已经写在「改了什么」里");
    }

    @Test
    void 影响面_后端没分析成功是不知道_不能显示成不涉及() {
        Target be = backend();
        Assessment failed = TestAssessmentBuilder.build("v", ticket(be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, null)), "2026-10-05");
        assertFalse(failed.getImpacts().isEmpty());
        failed.getImpacts().forEach(g -> assertEquals(GuideViews.UNKNOWN, g.getState(), g.getKey()));

        Report r = new Report();
        r.getEndpoints().add(ep("MODIFIED", "SAFE", "GET", "/activity/list", 1));
        r.setMustTest(MustTestGuide.of(r));
        Assessment ok = TestAssessmentBuilder.build("v", ticket(be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-05");
        assertEquals(GuideViews.NO, impact(ok, "db").getState());
        assertEquals(GuideViews.NO, impact(ok, "cache").getState());
    }

    @Test
    void 单子里写了配置_配置格算涉及() {
        Target be = backend();
        Ticket t = ticket(be);
        t.setConfigChanges("activity.lottery.enabled=true");
        Report r = new Report();
        r.getEndpoints().add(ep("MODIFIED", "SAFE", "GET", "/activity/list", 1));
        Assessment a = TestAssessmentBuilder.build("v", t,
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-05");
        EndGroup config = impact(a, "config");
        assertEquals(GuideViews.YES, config.getState());
        assertTrue(config.getItems().stream().anyMatch(i -> i.what().contains("activity.lottery.enabled")));
    }

    @Test
    void 没有后端项目_不出影响面() {
        Target fe = backend();
        fe.setKind("FRONTEND");
        fe.setAnalyzable(false);
        Assessment a = TestAssessmentBuilder.build("v", ticket(fe), List.of(), "2026-10-05");
        assertTrue(a.getImpacts().isEmpty());
    }

    @Test
    void 方法改动没连上接口时_按实现类补连_Impl写法也认() {
        LogicItem li = new LogicItem();
        li.setProject("app-activity");
        li.setClassName("ActivityServiceImpl");
        EndpointChange e = ep("MODIFIED", "SAFE", "GET", "/activity/list", 0);
        e.getChangedImpls().add("ActivityService");
        EndpointChange gone = ep("REMOVED", "BREAKING", "GET", "/activity/old", 0);
        gone.getChangedImpls().add("ActivityService");
        List<LogicItem> items = new ArrayList<>(List.of(li));

        GuideViews.linkLogic(items, List.of(new TestAssessmentBuilder.Item<>("app-activity", e),
                new TestAssessmentBuilder.Item<>("app-activity", gone),
                new TestAssessmentBuilder.Item<>("other", e)));

        assertEquals(List.of("GET /activity/list"), li.getEndpoints());
        assertTrue(GuideViews.sameService("CommonService", "CommonServiceImpl"));
        assertFalse(GuideViews.sameService("CommonService", "CommonServiceHelper"));
    }

    @Test
    void 缓存并发_标题不再抄一长串key_改成重点块() {
        ConcurrencyRiskScanner.Risk r = new ConcurrencyRiskScanner.Risk("POST", "/c/doComment", "h", "INFO", "CACHE_RACE",
                "写接口同时在操作缓存：cs_total_count、cs_first_page。并发写时，缓存和库可能被更新成不同的顺序。",
                "两个请求并发打这个接口。", "缓存 key: cs_total_count, cs_first_page");
        ReleaseBatchDtos.Focus fc = GuideViews.focus(r, 2);
        assertEquals("并发", fc.label());
        assertEquals("并发写时，缓存和库可能被更新成不同的顺序。", fc.risk());
        assertFalse(fc.title().contains("cs_total_count"));
        assertEquals(2, fc.keys());
    }

    @Test
    void JIRA单号_从地址和文字里认出来并去重() {
        assertEquals(List.of("APP-9078", "H5-12"), ReleaseBatchService.issueKeys(
                "https://jira.example.com/browse/APP-9078\nAPP-9078 再看下 H5-12，utf-8 不算"));
        assertTrue(ReleaseBatchService.issueKeys("  ").isEmpty());
    }
}
