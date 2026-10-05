package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.DtoChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.NewColumn;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ConfigPresence;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Link;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.ReleaseStep;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 测试负责发版：上线前先执行什么 SQL、先配什么，多个后端谁先发，发完要去调度中心做什么 */
class ReleasePlanTest {

    private static Target backend(String code) {
        Target t = new Target();
        t.setRawProject(code);
        t.setProjectCode(code);
        t.setProjectName(code);
        t.setKind("BACKEND");
        t.setBranch("feature/x");
        t.setAnalyzable(true);
        return t;
    }

    private static Ticket ticket(Target... targets) {
        Ticket t = new Ticket();
        t.setKey("APP-1");
        t.setSummary("评论得分调整");
        t.getTargets().addAll(List.of(targets));
        return t;
    }

    private static Assessment build(Ticket t, List<TestAssessmentBuilder.ProjectReport> rs) {
        return TestAssessmentBuilder.build("10.9", t, rs, "2026-10-05");
    }

    private static List<ReleaseStep> steps(Assessment a, String phase) {
        return a.getReleasePlan().stream().filter(s -> phase.equals(s.phase())).toList();
    }

    private static ReleaseStep find(Assessment a, String part) {
        return a.getReleasePlan().stream().filter(s -> s.what().contains(part)).findFirst()
                .orElseThrow(() -> new AssertionError(part + " 没找到：\n" + a.getReleaseOrder()));
    }

    private static Report scoreDetailReport() {
        Report r = new Report();
        TableChange tc = new TableChange();
        tc.setTable("comment_score");
        NewColumn c = new NewColumn();
        c.setColumn("score_detail");
        c.setFile("src/main/java/a/model/CommentScore.java");
        tc.getNewColumns().add(c);
        r.getTables().add(tc);
        DtoChange entity = new DtoChange();
        entity.setType("CommentScore");
        entity.setFile("src/main/java/a/model/CommentScore.java");
        FieldChange f = new FieldChange();
        f.setChangeType("ADDED");
        f.setName("scoreDetail");
        entity.getFields().add(f);
        r.getOrphanDtos().add(entity);
        return r;
    }

    // ==================== SQL 先上 ====================

    @Test
    void 代码用了新字段_分支和单子都没SQL_高风险并且第一步就是找开发要SQL() {
        Target be = backend("comment-service");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be, scoreDetailReport())));

        assertEquals("HIGH", a.getRiskLevel());
        assertTrue(a.getRiskReasons().stream().anyMatch(s -> s.contains("comment_score.score_detail") && s.contains("没找到加字段的 SQL")),
                a.getRiskReasons().toString());
        ReleaseStep first = a.getReleasePlan().get(0);
        assertEquals("BEFORE", first.phase());
        assertTrue(first.must());
        assertTrue(first.what().contains("comment_score 加 score_detail"), first.what());
        assertTrue(first.why().contains("Unknown column"), first.why());
        assertTrue(a.getTicketChecks().stream().anyMatch(c -> "字段".equals(c.topic()) && TicketCodeCheck.CODE_ONLY.equals(c.status())));
    }

    @Test
    void 表实体加字段不算公共返回体() {
        Target be = backend("comment-service");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be, scoreDetailReport())));
        assertTrue(a.getRiskReasons().stream().noneMatch(s -> s.contains("公共返回体")), a.getRiskReasons().toString());
        assertTrue(a.getPublicItems().stream().noneMatch(s -> s.contains("CommentScore")), a.getPublicItems().toString());
        assertTrue(a.getHiddenRisks().stream().anyMatch(s -> s.startsWith("表实体 CommentScore")), a.getHiddenRisks().toString());
    }

    @Test
    void 单子里写了加字段的SQL_不再报没SQL_改成先执行单子SQL() {
        Target be = backend("comment-service");
        Ticket t = ticket(be);
        t.setDbChanges("ALTER TABLE comment_score ADD COLUMN score_detail varchar(1024);");
        Assessment a = build(t, List.of(new TestAssessmentBuilder.ProjectReport(be, scoreDetailReport())));

        assertTrue(a.getRiskReasons().stream().noneMatch(s -> s.contains("没找到加字段的 SQL")), a.getRiskReasons().toString());
        ReleaseStep sql = find(a, "先执行单子里的 SQL");
        assertTrue(sql.must());
        assertTrue(sql.what().contains("comment_score.score_detail"), sql.what());
        assertTrue(a.getTicketChecks().stream().noneMatch(c -> "字段".equals(c.topic()) && TicketCodeCheck.CODE_ONLY.equals(c.status())));
    }

    @Test
    void 分支里有SQL单子没写_提示先执行分支SQL并补进单子() {
        Target be = backend("comment-service");
        Report r = scoreDetailReport();
        r.getTables().get(0).getNewColumns().get(0).setDdlInBranch(true);
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be, r)));
        ReleaseStep s = find(a, "先执行分支里改表结构的 SQL");
        assertTrue(s.must());
        assertTrue(s.why().contains("补进单子"), s.why());
    }

    // ==================== 配置先配 ====================

    private static Report withConfig(ConfigPresence p) {
        Report r = new Report();
        r.getConfigPresence().add(p);
        return r;
    }

    private static ConfigPresence presence(String key, boolean noDefault, Integer need, Map<String, String> profiles) {
        ConfigPresence p = new ConfigPresence();
        p.setKey(key);
        p.setNoDefault(noDefault);
        p.setNeedCount(need);
        p.setProfiles(new LinkedHashMap<>(profiles));
        return p;
    }

    @Test
    void 列表配置prod只有6个值_代码要7个_上线前必须补() {
        Target be = backend("comment-service");
        Map<String, String> prof = new LinkedHashMap<>();
        prof.put("dev", "1,2,3,4,5,6,7");
        prof.put("prod", "1,2,3,4,5,6");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be,
                withConfig(presence("comment.score.factor.list", false, 7, prof)))));
        ReleaseStep s = find(a, "comment.score.factor.list 上线前要补到 7 个值");
        assertTrue(s.must());
        assertTrue(s.what().contains("prod 只有 6 个"), s.what());
        assertFalse(s.what().contains("dev"), s.what());
    }

    @Test
    void 没默认值的新配置prod没配_上线前必须配_不然启动失败() {
        Target be = backend("comment-service");
        Map<String, String> prof = new LinkedHashMap<>();
        prof.put("dev", "true");
        prof.put("prod", null);
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be,
                withConfig(presence("feature.on", true, null, prof)))));
        ReleaseStep s = find(a, "prod 环境的配置文件里没有 feature.on");
        assertTrue(s.must());
        assertTrue(s.why().contains("启动失败"), s.why());
    }

    @Test
    void 仓库里没有配置文件_提示去配置中心配_不说配好了() {
        Target be = backend("comment-service");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be,
                withConfig(presence("feature.on", true, null, Map.of())))));
        ReleaseStep s = find(a, "配置中心");
        assertTrue(s.must());
        assertTrue(s.why().contains("没法自动检查"), s.why());
    }

    @Test
    void 密码类配置的值不展示() {
        Target be = backend("comment-service");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be,
                withConfig(presence("third.api.secret", false, null, Map.of("dev", "abc123", "prod", "xyz789"))))));
        ReleaseStep s = find(a, "third.api.secret 各环境都配了");
        assertFalse(s.what().contains("abc123") || s.what().contains("xyz789"), s.what());
    }

    // ==================== 多个后端谁先发 ====================

    private static Link backLink(String from, String to, String change, String risk, String call) {
        Link l = new Link();
        l.setFromNode(from + ":BACK");
        l.setToNode(to + ":BACK");
        l.setFromName(from);
        l.setToName(to);
        l.setMethod("POST");
        l.setPath("/score/detail");
        l.setEndpointChange(change);
        l.setEndpointRisk(risk);
        l.setCallChange(call);
        l.setLevel(CrossProjectLinker.JOINT);
        return l;
    }

    private static List<TestAssessmentBuilder.ProjectReport> reports(Target... ts) {
        List<TestAssessmentBuilder.ProjectReport> out = new ArrayList<>();
        for (Target t : ts) {
            out.add(new TestAssessmentBuilder.ProjectReport(t, new Report()));
        }
        return out;
    }

    @Test
    void 后端调另一个后端新加的接口_被调方先发() {
        Target a1 = backend("app-activity");
        Target c1 = backend("comment-service");
        Ticket t = ticket(a1, c1);
        t.setLinks(List.of(backLink("app-activity", "comment-service", "ADDED", "NEW", "CHANGED_FILE")));
        Assessment a = build(t, reports(a1, c1));
        ReleaseStep s = find(a, "后端按这个顺序发");
        assertTrue(s.what().endsWith("comment-service → app-activity"), s.what());
        assertTrue(s.must());
        assertTrue(s.why().contains("404"), s.why());
    }

    @Test
    void 三个后端链式依赖_拓扑排出顺序() {
        Target x = backend("svc-a");
        Target y = backend("svc-b");
        Target z = backend("svc-c");
        Ticket t = ticket(x, y, z);
        t.setLinks(List.of(backLink("svc-a", "svc-b", "ADDED", "NEW", "CHANGED_FILE"),
                backLink("svc-b", "svc-c", "ADDED", "NEW", "CHANGED_FILE")));
        Assessment a = build(t, reports(x, y, z));
        assertTrue(find(a, "后端按这个顺序发").what().endsWith("svc-c → svc-b → svc-a"));
    }

    @Test
    void 互相依赖_只能同时发() {
        Target x = backend("svc-a");
        Target y = backend("svc-b");
        Ticket t = ticket(x, y);
        t.setLinks(List.of(backLink("svc-a", "svc-b", "ADDED", "NEW", "CHANGED_FILE"),
                backLink("svc-b", "svc-a", "ADDED", "NEW", "CHANGED_FILE")));
        Assessment a = build(t, reports(x, y));
        assertTrue(find(a, "只能同时发").must());
    }

    @Test
    void 不兼容改动调用方没跟着改_必须先确认() {
        Target x = backend("svc-a");
        Target y = backend("svc-b");
        Ticket t = ticket(x, y);
        t.setLinks(List.of(backLink("svc-a", "svc-b", "MODIFIED", "BREAKING", "UNCHANGED")));
        Assessment a = build(t, reports(x, y));
        ReleaseStep s = find(a, "svc-b 发版后，svc-a 调");
        assertTrue(s.must());
    }

    @Test
    void 两个后端之间认不出依赖_明说认不出_不说没有先后() {
        Target x = backend("svc-a");
        Target y = backend("svc-b");
        Assessment a = build(ticket(x, y), reports(x, y));
        ReleaseStep s = find(a, "改了 2 个后端");
        assertFalse(s.must());
        assertTrue(s.why().contains("认不出"), s.why());
    }

    @Test
    void 两个服务改同一张表的关联不算调用依赖() {
        Target x = backend("svc-a");
        Target y = backend("svc-b");
        Ticket t = ticket(x, y);
        Link l = backLink("svc-a", "svc-b", null, null, null);
        l.setPath("表 comment_score");
        l.setLevel(CrossProjectLinker.INFO);
        t.setLinks(List.of(l));
        Assessment a = build(t, reports(x, y));
        assertTrue(a.getReleasePlan().stream().noneMatch(s -> s.what().contains("后端按这个顺序发")), a.getReleaseOrder().toString());
    }

    @Test
    void 新消费者和新生产者在不同后端_消费方先发() {
        Target p = backend("svc-producer");
        Target c = backend("svc-consumer");
        Report pr = new Report();
        MethodLogicDiff.Change send = new MethodLogicDiff.Change();
        send.setKind("MQ");
        send.setTitle("新增发消息");
        send.setRef("comment_scored");
        send.setClassName("ScoreService");
        send.setMember("calc");
        pr.getLogicChanges().add(send);
        Report cr = new Report();
        MethodLogicDiff.Change listen = new MethodLogicDiff.Change();
        listen.setKind("MQ");
        listen.setTitle("开始消费消息");
        listen.setRef("comment_scored");
        listen.setClassName("ScoreListener");
        listen.setMember("onScored");
        cr.getLogicChanges().add(listen);
        Assessment a = build(ticket(p, c), List.of(new TestAssessmentBuilder.ProjectReport(p, pr),
                new TestAssessmentBuilder.ProjectReport(c, cr)));
        assertTrue(find(a, "后端按这个顺序发").what().endsWith("svc-consumer → svc-producer"));
        assertTrue(find(a, "Kafka 上先建好 topic").what().contains("comment_scored"));
    }

    // ==================== 发版后 ====================

    @Test
    void 新的XXL_JOB任务_发版后到调度中心建任务() {
        Target be = backend("comment-service");
        Report r = new Report();
        MethodLogicDiff.Change job = new MethodLogicDiff.Change();
        job.setKind("METHOD_ADDED");
        job.setTitle("新增方法");
        job.setClassName("ScoreJob");
        job.setMember("recalcDetail");
        job.setJobHandler("recalcScoreDetail");
        r.getLogicChanges().add(job);
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be, r)));
        List<ReleaseStep> after = steps(a, "AFTER");
        assertEquals(1, after.size(), a.getReleaseOrder().toString());
        assertTrue(after.get(0).what().contains("JobHandler 填 recalcScoreDetail"), after.get(0).what());
        assertTrue(after.get(0).why().contains("提前建任务会报找不到 handler"));
        assertTrue(a.getReleaseOrder().stream().anyMatch(s -> s.startsWith("【发版后】必须")));
    }

    @Test
    void 什么都没有_清单为空() {
        Target be = backend("comment-service");
        Assessment a = build(ticket(be), List.of(new TestAssessmentBuilder.ProjectReport(be, new Report())));
        assertTrue(a.getReleasePlan().isEmpty(), a.getReleaseOrder().toString());
    }
}
