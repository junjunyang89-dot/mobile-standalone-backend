package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.DdlChange;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.apichange.MustTestGuide;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Check;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAssessmentBuilderTest {

    private static Target target(String code, String kind, String branch) {
        Target t = new Target();
        t.setRawProject(code);
        t.setProjectCode(code);
        t.setProjectName(code);
        t.setKind(kind);
        t.setBranch(branch);
        t.setAnalyzable("BACKEND".equals(kind));
        return t;
    }

    private static Ticket ticket(String summary, Target... targets) {
        Ticket t = new Ticket();
        t.setKey("APP-9078");
        t.setSummary(summary);
        t.setAssignee("张三");
        t.setQaAssignee("李四");
        t.getTargets().addAll(List.of(targets));
        return t;
    }

    private static boolean checked(List<Check> checks, String label) {
        return checks.stream().filter(c -> c.label().equals(label)).findFirst().orElseThrow().checked();
    }

    @Test
    void 新增必填入参_加表结构_判高风险并勾选对应项() {
        Target be = target("app-activity", "BACKEND", "dyl/20260903/yd");
        Target fe = target("app-swift-v3", "FRONTEND", "cj/app-9078");
        Ticket t = ticket("活动页新增抽奖入口", be, fe);
        t.setDbChanges("ALTER TABLE t_lottery ADD COLUMN times int;");

        Report r = new Report();
        EndpointChange e = new EndpointChange();
        e.setChangeType("MODIFIED");
        e.setRisk("BREAKING");
        e.setHttpMethod("POST");
        e.setPath("/activity/lottery/draw");
        e.setSummary("抽奖");
        ParamChange p = new ParamChange();
        p.setChangeType("ADDED");
        p.setName("activityId");
        p.setNewType("Long");
        p.setRequired(true);
        e.getParams().add(p);
        FieldChange f = new FieldChange();
        f.setChangeType("ADDED");
        f.setName("prizeName");
        f.setNewType("String");
        e.getFields().add(f);
        r.getEndpoints().add(e);
        TableChange tc = new TableChange();
        tc.setTable("t_lottery");
        tc.setDdl(true);
        tc.getDdlDetails().add(new DdlChange("t_lottery", "ADD_COLUMN", "times", "int"));
        tc.getUsedByEndpoints().add("GET /activity/lottery/list");
        r.getTables().add(tc);
        OtherChange log = new OtherChange();
        log.setKind("SERVICE");
        log.setFileName("LotteryHelper.java");
        log.setTrivial("只增删或改了日志，逻辑没变");
        r.getOtherChanges().add(log);
        r.setMustTest(MustTestGuide.of(r));

        Assessment a = TestAssessmentBuilder.build("10.9 国内H5", t,
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");

        assertEquals("HIGH", a.getRiskLevel());
        assertEquals("app-activity", a.getProjectName());
        assertTrue(checked(a.getChangeTypes(), "新增功能"));
        assertTrue(checked(a.getChangeTypes(), "数据库变更"));
        assertTrue(checked(a.getChangeTypes(), "接口调整"));
        assertFalse(checked(a.getChangeTypes(), "Bug 修复"));
        assertTrue(a.getNeedMigration().checked());
        assertTrue(a.getFrontends().get(0).contains("cj/app-9078"));
        assertTrue(a.getApiChanges().get(0).getInputs().get(0).contains("activityId"));
        assertTrue(a.getErrorCases().stream().anyMatch(s -> s.contains("故意不传 activityId")));
        assertTrue(a.getNoRegress().stream().anyMatch(s -> s.contains("LotteryHelper")));
        assertTrue(a.getRelatedRegress().stream().anyMatch(s -> s.startsWith("GET /activity/lottery/list")));
        assertTrue(a.getAutomation().checked());
        assertTrue(a.getAutomationRefs().get(0).contains("APP-9078"));
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("不兼容")));
        assertTrue(checked(a.getMethods(), "造数据测试"));
        assertTrue(checked(a.getMethods(), "兼容性测试"));
        assertFalse(checked(a.getMethods(), "性能测试"));
    }

    @Test
    void 只有日志改动_低风险_勾代码重构() {
        Target be = target("app-engine", "BACKEND", "dyl/20260903/yd");
        Report r = new Report();
        OtherChange log = new OtherChange();
        log.setKind("SERVICE");
        log.setFileName("A.java");
        log.setTrivial("只改了注释、空行或 import，逻辑没变");
        r.getOtherChanges().add(log);
        Assessment a = TestAssessmentBuilder.build("v", ticket("修复日志打印", be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");
        assertEquals("LOW", a.getRiskLevel());
        assertTrue(checked(a.getChangeTypes(), "代码重构"));
        assertTrue(checked(a.getChangeTypes(), "Bug 修复"));
        assertTrue(a.getMustRegress().isEmpty());
    }

    /**
     * 分支改动已经合进基线：什么都没比出来。原来这里给的是「低风险，冒烟即可」，
     * 「无需回归」里还挂着一个单测文件——等于替没做的分析背书。
     */
    @Test
    void 改动已在基线里_不给风险等级_结论说清没比出来() {
        Target be = target("app-comment-service", "BACKEND", "dyl/score");
        Report r = new Report();
        r.setAlreadyInBase(true);
        OtherChange same = new OtherChange();
        same.setKind("SERVICE");
        same.setFileName("CommonServiceImpl.java");
        same.setTrivial(com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ALREADY_IN_BASE);
        r.getOtherChanges().add(same);

        Assessment a = TestAssessmentBuilder.build("v", ticket("评论得分计算规则调整", be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");

        assertNull(a.getRiskLevel());
        assertTrue(a.getConclusion().contains("没比出差异"), a.getConclusion());
        assertTrue(a.getNoRegress().isEmpty(), "没得比不等于不用回归：" + a.getNoRegress());
        assertTrue(a.getLimits().stream().anyMatch(s -> s.contains("CommonServiceImpl")), a.getLimits().toString());
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("已经上线")), a.getQuestions().toString());
        assertFalse(checked(a.getChangeTypes(), "代码重构"));
    }

    static MethodLogicDiff.Change logic(String kind, String member, String title, String before, String after, String hint) {
        MethodLogicDiff.Change c = new MethodLogicDiff.Change();
        c.setFile("src/main/java/com/x/CommonServiceImpl.java");
        c.setClassName("CommonServiceImpl");
        c.setMember(member);
        c.setKind(kind);
        c.setTitle(title);
        c.setBefore(before);
        c.setAfter(after);
        c.setHint(hint);
        return c;
    }

    /** 评论得分规则调整（comment-service 283f3ca 的真实形态）：条件、公式、配置都改了，单子却没写配置 */
    @Test
    void 方法逻辑改动_分进风险测试场景和待确认_单子没写的配置升高风险() {
        Target be = target("app-comment-service", "BACKEND", "dyl/score");
        Report r = new Report();
        r.setChangedFiles(1);
        MethodLogicDiff.Change cond = logic("CONDITION", "calculateCommentScore", "判断条件改了",
                "factors.size() < 6", "factors.size() < 7", "边界取 6、7 各测一次");
        cond.getEndpoints().add("POST /comment/score");
        r.getLogicChanges().add(cond);
        r.getLogicChanges().add(logic("CONDITION", "calculateCommentScore", "去掉了判断",
                "timeFactor <= 44", null, "原来 timeFactor=44、45 的数据现在怎么算"));
        r.getLogicChanges().add(logic("FORMULA", "calculateCommentScore", "计算公式改了",
                "a * 0.1", "Math.log(a)", "拿同一条评论对比改前改后的得分"));
        MethodLogicDiff.Change cfg = logic("CONFIG", "factorWeights", "配置 comment.score.factors 默认值改了",
                "1,1,1,1,1,1", "1,1,1,1,1,1,1", "配置中心的值要配 7 个");
        cfg.setRef("comment.score.factors");
        r.getLogicChanges().add(cfg);
        r.getLogicChanges().add(logic("METHOD_CHANGED", "buildSummary", "buildSummary() 改了（+2 / -1 行）",
                "a", "b", "没认出具体是哪类改动，对照这几行找开发问清楚新旧行为差在哪"));

        Assessment a = TestAssessmentBuilder.build("v", ticket("评论得分计算规则调整", be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");

        assertEquals(5, a.getLogicDetails().size());
        assertTrue(a.getBoundaryCases().stream().anyMatch(s -> s.startsWith("POST /comment/score（CommonServiceImpl.calculateCommentScore()）")
                && s.contains("6、7")), a.getBoundaryCases().toString());
        assertTrue(a.getLogicRisks().stream().anyMatch(s -> s.contains("timeFactor <= 44")), a.getLogicRisks().toString());
        assertTrue(a.getLogicRisks().stream().anyMatch(s -> s.contains("计算方式改了")), a.getLogicRisks().toString());
        assertTrue(a.getNormalCases().stream().anyMatch(s -> s.contains("改前改后的得分")), a.getNormalCases().toString());
        assertTrue(a.getTestData().stream().anyMatch(s -> s.contains("配 7 个")), a.getTestData().toString());
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("去掉了判断「timeFactor <= 44」")), a.getQuestions().toString());
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.startsWith("【单子没写 · 配置】")), a.getQuestions().toString());
        assertTrue(a.getTicketChecks().stream().anyMatch(c -> "配置".equals(c.topic())
                && TicketCodeCheck.CODE_ONLY.equals(c.status())));
        assertTrue(checked(a.getChangeTypes(), "逻辑优化"));
        assertTrue(a.getLogicDetails().stream().anyMatch(d -> "factors.size() < 7".equals(d.getAfter())
                && d.getEndpoints().contains("POST /comment/score")));
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("没认出是哪类改动")), a.getQuestions().toString());
        assertEquals("HIGH", a.getRiskLevel());
        assertTrue(a.getRiskReasons().stream().anyMatch(s -> s.contains("单子里没写")), a.getRiskReasons().toString());
    }

    private static ReleaseBatchDtos.EndGroup group(Assessment a, String key) {
        return a.getEndGroups().stream().filter(g -> key.equals(g.getKey())).findFirst().orElseThrow();
    }

    private static ReleaseBatchDtos.Stat stat(Assessment a, String key) {
        return a.getOverview().stream().filter(s -> key.equals(s.key())).findFirst().orElseThrow();
    }

    /** 后端删了返回字段，线上老版本 App 还在读：要升高风险、进必测、问清楚怎么处理、App 那一组有具体测法 */
    @Test
    void 线上老版本App受影响_进风险必测待确认_概览和按端分组都能看到() {
        Target be = target("app-activity", "BACKEND", "dyl/x");
        Report r = new Report();
        r.setChangedFiles(1);
        EndpointChange e = new EndpointChange();
        e.setChangeType("MODIFIED");
        e.setRisk("BREAKING");
        e.setHttpMethod("POST");
        e.setPath("/activity/lottery/draw");
        FieldChange f = new FieldChange();
        f.setChangeType("REMOVED");
        f.setName("prizeName");
        e.getFields().add(f);
        r.getEndpoints().add(e);
        Ticket t = ticket("抽奖返回结构调整", be);
        t.setDbChanges("ALTER TABLE t_lottery ADD COLUMN times int;");

        ReleaseBatchDtos.CompatCheck compat = new ReleaseBatchDtos.CompatCheck();
        ReleaseBatchDtos.CompatHit h = new ReleaseBatchDtos.CompatHit();
        h.setLevel("RISK");
        h.setCaller("app-esports-android");
        h.setCallerKind("CLIENT");
        h.setEndpoint("POST /activity/lottery/draw");
        h.setProblem("删了返回字段 prizeName");
        h.setField("prizeName");
        h.setFieldFound("FOUND");
        h.getFieldRefs().add("app/src/main/java/com/x/model/DrawResp.kt:2");
        h.getCallRefs().add("app/src/main/java/com/x/api/LotteryApi.kt:2");
        h.setAdvice("用 master 打的包连测试环境新后端，打开抽奖页确认不闪退");
        compat.getHits().add(h);
        compat.getChecked().add("app-esports-android（客户端）");
        compat.getUnchecked().add("app-esports-ios（客户端）：本地还没有这个仓库");
        t.setCompat(compat);

        Assessment a = TestAssessmentBuilder.build("v", t, List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");

        assertEquals("HIGH", a.getRiskLevel());
        assertTrue(a.getRiskReasons().stream().anyMatch(s -> s.contains("线上老版本 App")), a.getRiskReasons().toString());
        assertTrue(a.getMustRegress().get(0).startsWith("【老版本兼容】线上老版本 app-esports-android"), a.getMustRegress().toString());
        assertTrue(a.getCompatRisks().stream().anyMatch(s -> s.contains("DrawResp.kt:2")), a.getCompatRisks().toString());
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("强更")), a.getQuestions().toString());
        assertTrue(a.getLimits().stream().anyMatch(s -> s.contains("app-esports-ios")), a.getLimits().toString());

        assertEquals("RISK", stat(a, "app").level());
        assertEquals(1, stat(a, "api").count());
        assertEquals("RISK", stat(a, "db").level());
        assertTrue(a.getReleaseOrder().get(0).startsWith("【上线前】必须 · 先执行单子里的 SQL"), a.getReleaseOrder().toString());
        assertEquals("BEFORE", a.getReleasePlan().get(0).phase());
        assertTrue(a.getReleasePlan().get(0).must());
        assertTrue(a.getReleaseOrder().stream().anyMatch(s -> s.contains("没法跟着发")), a.getReleaseOrder().toString());

        ReleaseBatchDtos.EndGroup app = group(a, "app");
        assertTrue(app.getItems().stream().anyMatch(i -> i.what().contains("不闪退")), app.getItems().toString());
        assertTrue(app.getItems().stream().anyMatch(i -> i.what().contains("app-esports-ios")), "没拿到代码的客户端要列出来");
        assertTrue(group(a, "db").getItems().stream().anyMatch(i -> i.what().contains("SQL")));
        assertTrue(group(a, "h5").getItems().isEmpty());
        assertTrue(group(a, "h5").getNote().contains("没看到"));
    }

    /** 老数据没有调用方检查结果：不能说成「没有不兼容改动」 */
    @Test
    void 有不兼容改动但没跑调用方检查_App那组提示重新分析() {
        Target be = target("app-activity", "BACKEND", "dyl/x");
        Report r = new Report();
        EndpointChange e = new EndpointChange();
        e.setChangeType("REMOVED");
        e.setHttpMethod("GET");
        e.setPath("/old");
        r.getEndpoints().add(e);
        Assessment a = TestAssessmentBuilder.build("v", ticket("下线老接口", be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");
        assertEquals("RISK", stat(a, "app").level());
        assertTrue(stat(a, "app").detail().contains("还没检查"), stat(a, "app").detail());
        assertTrue(group(a, "app").getItems().stream().anyMatch(i -> i.what().startsWith("重新分析")));
    }

    @Test
    void 只有实现改动_App组说明不用专门测_安全组不编造() {
        Target be = target("app-activity", "BACKEND", "dyl/x");
        Report r = new Report();
        EndpointChange e = new EndpointChange();
        e.setChangeType("MODIFIED");
        e.setRisk("IMPL");
        e.setHttpMethod("GET");
        e.setPath("/a");
        r.getEndpoints().add(e);
        Assessment a = TestAssessmentBuilder.build("v", ticket("优化查询", be),
                List.of(new TestAssessmentBuilder.ProjectReport(be, r)), "2026-10-04");
        assertTrue(group(a, "app").getNote().contains("不用专门测"), group(a, "app").getNote());
        assertTrue(group(a, "security").getItems().isEmpty());
        assertTrue(a.getReleaseOrder().isEmpty(), a.getReleaseOrder().toString());
    }

    @Test
    void 没有后端_不给风险等级_并提示确认() {
        Target cl = target("app-esports-android", "CLIENT", "dev20260924");
        Target miss = new Target();
        miss.setRawProject("pwd-game-mall");
        miss.setBranch("a/b");
        Assessment a = TestAssessmentBuilder.build("v", ticket("安卓改版", cl, miss), List.of(), "2026-10-04");
        assertNull(a.getRiskLevel());
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("pwd-game-mall")));
        assertTrue(a.getQuestions().stream().anyMatch(s -> s.contains("没认出后端分支")));
        assertTrue(a.getClients().get(0).contains("dev20260924"));
    }
}
