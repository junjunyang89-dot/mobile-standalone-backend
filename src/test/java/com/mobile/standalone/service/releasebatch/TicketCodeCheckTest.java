package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.DdlChange;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import com.mobile.standalone.service.releasebatch.TicketCodeCheck.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketCodeCheckTest {

    private static Target backend() {
        Target t = new Target();
        t.setRawProject("app-comment-service");
        t.setProjectCode("app-comment-service");
        t.setProjectName("app-comment-service");
        t.setKind("BACKEND");
        t.setBranch("dyl/score");
        t.setAnalyzable(true);
        return t;
    }

    private static List<Item> of(List<Item> items, String topic, String status) {
        return items.stream().filter(i -> topic.equals(i.topic()) && status.equals(i.status())).toList();
    }

    private static List<TestAssessmentBuilder.ProjectReport> one(Target t, Report r) {
        return List.of(new TestAssessmentBuilder.ProjectReport(t, r));
    }

    @Test
    void 单子加了字段_代码里用上了算对上_没用上要找开发() {
        Target be = backend();
        Ticket t = new Ticket();
        t.getTargets().add(be);
        t.setDbChanges("ALTER TABLE t_comment ADD COLUMN hot_score int;\nALTER TABLE t_comment ADD COLUMN spare_flag int;");
        Report r = new Report();
        r.setChangedFiles(2);

        assertTrue(TicketCodeCheck.wordsToSearch(t).containsAll(List.of("hot_score", "hotScore", "spare_flag", "spareFlag")));

        List<Item> items = TicketCodeCheck.check(t, one(be, r), Map.of("hotScore", 3));
        assertEquals(1, of(items, "字段", TicketCodeCheck.MATCH).size());
        Item missing = of(items, "字段", TicketCodeCheck.TICKET_ONLY).get(0);
        assertTrue(missing.ticket().contains("spare_flag"), missing.toString());
        assertTrue(of(items, "表", TicketCodeCheck.TICKET_ONLY).get(0).ticket().contains("t_comment"));
    }

    @Test
    void 没拿到代码_字段一律标待确认_不能当没问题() {
        Ticket t = new Ticket();
        t.setDbChanges("ALTER TABLE t_comment ADD COLUMN hot_score int;");
        List<Item> items = TicketCodeCheck.check(t, List.of(), null);
        assertEquals(1, of(items, "字段", TicketCodeCheck.CHECK).size());
        assertTrue(of(items, "字段", TicketCodeCheck.MATCH).isEmpty());
    }

    @Test
    void 代码改了表结构单子没写SQL_标代码改了单子没写() {
        Target be = backend();
        Ticket t = new Ticket();
        t.getTargets().add(be);
        Report r = new Report();
        r.setChangedFiles(1);
        TableChange tc = new TableChange();
        tc.setTable("t_comment");
        tc.setDdl(true);
        tc.getDdlDetails().add(new DdlChange("t_comment", "ADD_COLUMN", "hot_score", "int"));
        r.getTables().add(tc);

        Item i = of(TicketCodeCheck.check(t, one(be, r), Map.of()), "表", TicketCodeCheck.CODE_ONLY).get(0);
        assertTrue(i.code().contains("hot_score"), i.toString());
        assertTrue(i.ticket().contains("没写 SQL"), i.toString());
    }

    @Test
    void 配置_代码改了单子没写_单子写了代码搜不到() {
        Target be = backend();
        Ticket t = new Ticket();
        t.getTargets().add(be);
        t.setConfigChanges("Apollo 新增 comment.hot.threshold=100，详见 https://wiki.x.com/a.b");
        Report r = new Report();
        r.setChangedFiles(1);
        MethodLogicDiff.Change cfg = TestAssessmentBuilderTest.logic("CONFIG", "weights", "配置默认值改了",
                "1,1", "1,1,1", "配 3 个");
        cfg.setRef("comment.score.factors");
        r.getLogicChanges().add(cfg);

        List<Item> items = TicketCodeCheck.check(t, one(be, r), Map.of());
        assertTrue(of(items, "配置", TicketCodeCheck.CODE_ONLY).get(0).ticket().contains("comment.score.factors"));
        List<Item> ticketOnly = of(items, "配置", TicketCodeCheck.TICKET_ONLY);
        assertEquals(1, ticketOnly.size(), items.toString());
        assertTrue(ticketOnly.get(0).ticket().contains("comment.hot.threshold"));

        List<Item> found = TicketCodeCheck.check(t, one(be, r), Map.of("comment.hot.threshold", 1));
        assertEquals(1, of(found, "配置", TicketCodeCheck.MATCH).size());
    }

    @Test
    void 定时任务_代码改了单子没提_单子提了代码没改() {
        Target be = backend();
        Ticket t = new Ticket();
        t.getTargets().add(be);
        Report r = new Report();
        r.setChangedFiles(1);
        r.getLogicChanges().add(TestAssessmentBuilderTest.logic("SCHEDULE", "refreshHot", "定时任务的执行时间改了",
                "0 0 * * * ?", "0 */10 * * * ?", "手动触发一次"));
        assertEquals(1, of(TicketCodeCheck.check(t, one(be, r), Map.of()), "定时任务", TicketCodeCheck.CODE_ONLY).size());

        Ticket t2 = new Ticket();
        t2.getTargets().add(be);
        t2.setNotes("xxl-job 热度刷新任务改成每 10 分钟一次");
        Report empty = new Report();
        empty.setChangedFiles(1);
        assertEquals(1, of(TicketCodeCheck.check(t2, one(be, empty), Map.of()), "定时任务", TicketCodeCheck.TICKET_ONLY).size());

        OtherChange job = new OtherChange();
        job.setKind("SCHEDULE");
        job.setFileName("HotJob.java");
        empty.getOtherChanges().add(job);
        assertEquals(1, of(TicketCodeCheck.check(t2, one(be, empty), Map.of()), "定时任务", TicketCodeCheck.MATCH).size());
    }

    @Test
    void 项目_分支没改动或已合进基线_都要提出来() {
        Target be = backend();
        Ticket t = new Ticket();
        t.getTargets().add(be);
        Report r = new Report();
        assertEquals(1, of(TicketCodeCheck.check(t, one(be, r), Map.of()), "项目", TicketCodeCheck.TICKET_ONLY).size());
        r.setAlreadyInBase(true);
        r.setChangedFiles(3);
        assertEquals(1, of(TicketCodeCheck.check(t, one(be, r), Map.of()), "项目", TicketCodeCheck.CHECK).size());
    }

    @Test
    void 配置key_不把网址和文件名当配置() {
        var keys = TicketCodeCheck.configKeys("改 order.pay.timeout；见 http://a.b.com/x 和 application.yml、v1.2.3");
        assertEquals(List.of("order.pay.timeout"), List.copyOf(keys));
    }

    @Test
    void 搜代码_整词匹配_跳过单测和target(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("src/main/java/A.java");
        Path test = dir.resolve("src/test/java/ATest.java");
        Path target = dir.resolve("target/classes/B.java");
        for (Path p : List.of(main, test, target)) {
            Files.createDirectories(p.getParent());
        }
        Files.writeString(main, "int hotScore; int hotScoreMax;");
        Files.writeString(test, "hotScore");
        Files.writeString(target, "hotScore");
        Map<String, Integer> n = CodeWordSearch.countFiles(List.of(dir), java.util.Set.of("hotScore", "nothing"));
        assertEquals(1, n.get("hotScore"));
        assertFalse(n.getOrDefault("nothing", 0) > 0);
    }
}
