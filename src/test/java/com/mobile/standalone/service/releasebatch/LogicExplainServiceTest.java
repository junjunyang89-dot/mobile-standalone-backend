package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicExplain;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicItem;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicExplainServiceTest {

    private static LogicItem item(String member, String kind, String before, String after) {
        LogicItem li = new LogicItem();
        li.setProject("评论服务");
        li.setClassName("CommonServiceImpl");
        li.setMember(member);
        li.setKind(kind);
        li.setTitle(kind);
        li.setBefore(before);
        li.setAfter(after);
        return li;
    }

    @Test
    void 解析_带代码块和多余文字也能取出JSON() {
        LogicExplain ex = LogicExplainService.parse("""
                好的，结果如下：
                ```json
                {"title":"评论得分算法重构","oldRule":["综合互动分 = 统一的对数公式"],
                 "newRule":["拆成点赞分 + 回复分","新增信用贡献、质量贡献权重"],
                 "factors":[{"name":"点赞分","change":"新增","detail":"ln(1+点赞数)"},{"name":"","change":"新增"}],
                 "impact":"影响 doComment 返回的得分","focus":["核对 commentScoreFactorList 要配 7 个值"],
                 "vsTicket":"","unsure":"质量分的取值范围看不出来"}
                ```
                """);
        assertEquals("评论得分算法重构", ex.getTitle());
        assertEquals(1, ex.getOldRule().size());
        assertEquals(2, ex.getNewRule().size());
        assertEquals(1, ex.getFactors().size(), "名字为空的因子丢掉");
        assertNull(ex.getVsTicket(), "空字符串当没有");
        assertEquals("质量分的取值范围看不出来", ex.getUnsure());
    }

    @Test
    void 解析_没给新旧规则算失败_不存一份空翻译() {
        assertThrows(IllegalStateException.class, () -> LogicExplainService.parse("{\"title\":\"x\",\"oldRule\":[],\"newRule\":[]}"));
        assertThrows(IllegalStateException.class, () -> LogicExplainService.parse("抱歉，我看不懂"));
    }

    @Test
    void 按方法取改动_指纹随改动变() {
        Assessment a = new Assessment();
        a.getLogicDetails().add(item("calculateCommentScore", "FORMULA", "a = log(x)", "a = log(1 + x)"));
        a.getLogicDetails().add(item("popQueue", "METHOD_CHANGED", "x", "y"));
        String key = LogicExplainService.groupKey("评论服务", "CommonServiceImpl", "calculateCommentScore");
        List<LogicItem> items = LogicExplainService.itemsOf(a, key);
        assertEquals(1, items.size());
        String fp = LogicExplainService.fingerprint(items);
        assertEquals(fp, LogicExplainService.fingerprint(LogicExplainService.itemsOf(a, key)));
        items.get(0).setAfter("a = log(2 + x)");
        assertNotEquals(fp, LogicExplainService.fingerprint(items));
        assertEquals("", LogicExplainService.fingerprint(List.of()));
    }

    @Test
    void 提示词_带上需求_接口_两版完整方法() {
        Ticket t = new Ticket();
        t.setKey("APP-1");
        t.setSummary("评论得分优化");
        t.setNotes("点赞改成取对数");
        LogicItem li = item("calculateCommentScore", "FORMULA", "a = log(x)", "a = log(1 + x)");
        li.getEndpoints().add("POST /steamcn/comment/doComment");
        String p = LogicExplainService.prompt(t, "CommonServiceImpl", "calculateCommentScore", List.of(li),
                new LogicExplainService.Sources("double old() {}", "double now() {}", "读了两版"));
        assertTrue(p.contains("评论得分优化") && p.contains("点赞改成取对数"));
        assertTrue(p.contains("POST /steamcn/comment/doComment"));
        assertTrue(p.contains("改前的完整方法") && p.contains("double old()"));
        assertTrue(p.contains("改后的完整方法") && p.contains("double now()"));

        String noCode = LogicExplainService.prompt(t, "CommonServiceImpl", "popQueue", List.of(item("popQueue", "METHOD_CHANGED", "x", "y")),
                new LogicExplainService.Sources(null, null, "没拿到代码"));
        assertTrue(noCode.contains("没连到接口"));
        assertTrue(noCode.contains("没拿到完整方法"));
    }

    @Test
    void 取方法源码_重载都带上_没有返回null() {
        String src = """
                public class CommonServiceImpl {
                    private int a;
                    /** 注释去掉 */
                    @Override
                    public double score(int x) { return Math.log(1 + x); }
                    public double score(int x, int y) { return x + y; }
                }
                """;
        String s = MethodLogicDiff.memberSource(src, "score");
        assertTrue(s.contains("Math.log(1 + x)") && s.contains("return x + y"));
        assertTrue(s.contains("@Override"));
        assertTrue(!s.contains("注释去掉"));
        assertNull(MethodLogicDiff.memberSource(src, "missing"));
    }
}
