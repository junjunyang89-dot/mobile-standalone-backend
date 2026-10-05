package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Batch;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseBatchServiceTest {

    private static ApiProject project(String code, String kind) {
        ApiProject p = new ApiProject();
        p.setCode(code);
        p.setName(code);
        p.setKind(kind);
        return p;
    }

    @Test
    void 粘贴文本_按单号分段_标题取表格第一格() {
        String text = """
                APP-9202\t活动页新增抽奖入口\t张三\t李四\t测试中
                app-engine、app-activity：dyl/20260903/yd
                Android：dev20260924

                APP-9078 H5 改版
                app-swift-v3 : cj/app-9078
                """;
        List<Ticket> ts = ReleaseBatchService.parseTickets(text);
        assertEquals(2, ts.size());
        assertEquals("APP-9202", ts.get(0).getKey());
        assertEquals("活动页新增抽奖入口", ts.get(0).getSummary());
        assertTrue(ts.get(0).getNotes().contains("dyl/20260903/yd"));
        assertFalse(ts.get(0).getNotes().contains("cj/app-9078"));
        assertEquals("H5 改版", ts.get(1).getSummary());
    }

    @Test
    void 认项目_后端可分析_前端客户端不分析_没找到留空() {
        List<ApiProject> projects = List.of(
                project("app-engine", "BACKEND"),
                project("app-activity", "BACKEND"),
                project("app-swift-v3", "FRONTEND"),
                project("app-esports-android", "CLIENT"));
        List<Target> ts = ReleaseBatchService.resolveTargets("""
                app-engnie、app-activity：dyl/20260903/yd
                app-swift-v3 : cj/app-9078
                Android：dev20260924
                pwd-game-mall：zj/2026-09-01/mall
                """, projects);
        Target engine = ts.stream().filter(t -> "app-engnie".equals(t.getRawProject())).findFirst().orElseThrow();
        assertEquals("app-engine", engine.getProjectCode());
        assertEquals("FUZZY", engine.getMatchHow());
        assertTrue(engine.isAnalyzable());
        Target swift = ts.stream().filter(t -> "app-swift-v3".equals(t.getProjectCode())).findFirst().orElseThrow();
        assertFalse(swift.isAnalyzable());
        Target android = ts.stream().filter(t -> "app-esports-android".equals(t.getProjectCode())).findFirst().orElseThrow();
        assertEquals("dev20260924", android.getBranch());
        Target miss = ts.stream().filter(t -> "pwd-game-mall".equals(t.getRawProject())).findFirst().orElseThrow();
        assertNull(miss.getProjectCode());
    }

    @Test
    void 本地对比的占位单号不算真实单号() {
        assertTrue(ReleaseBatchService.isIssueKey("APP-9078"));
        assertFalse(ReleaseBatchService.isIssueKey("本地-1004-1830"));
        assertFalse(ReleaseBatchService.isIssueKey(null));
    }

    @Test
    void 批次JSON_来回不丢() {
        Batch b = new Batch();
        b.setVersion("10.9 国内H5");
        b.setCreatedAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        Ticket t = new Ticket();
        t.setKey("APP-1");
        Target x = new Target();
        x.setRawProject("app-engine");
        x.setAnalyzable(true);
        t.getTargets().add(x);
        t.setAssessment(TestAssessmentBuilder.build("10.9 国内H5", t, List.of(), "2026-10-04"));
        t.getAssessment().getManual().put("oldLogic", "旧的");
        b.getTickets().add(t);

        Batch back = ReleaseBatchService.read(ReleaseBatchService.write(b));
        assertEquals(b.getCreatedAt(), back.getCreatedAt());
        Ticket bt = back.getTickets().get(0);
        assertTrue(bt.getTargets().get(0).isAnalyzable());
        assertEquals("旧的", bt.getAssessment().getManual().get("oldLogic"));
        assertEquals(t.getAssessment().getChangeTypes().size(), bt.getAssessment().getChangeTypes().size());
        assertEquals("新增功能", bt.getAssessment().getChangeTypes().get(0).label());
    }

    @Test
    void 每个项目自己的基线_存得住_换了基线不复用旧结果() {
        Batch b = new Batch();
        Ticket t = new Ticket();
        t.setKey("APP-1");
        Target x = new Target();
        x.setRawProject("app-engine");
        x.setProjectCode("app-engine");
        x.setBranch("dyl/20260903/yd");
        x.setBaseBranch("release/10.8");
        t.getTargets().add(x);
        b.getTickets().add(t);

        Target back = ReleaseBatchService.read(ReleaseBatchService.write(b)).getTickets().get(0).getTargets().get(0);
        assertEquals("release/10.8", back.getBaseBranch());

        Target master = new Target();
        master.setProjectCode("app-engine");
        master.setBranch("dyl/20260903/yd");
        assertFalse(ReleaseBatchService.targetKey(x).equals(ReleaseBatchService.targetKey(master)));
    }
}
