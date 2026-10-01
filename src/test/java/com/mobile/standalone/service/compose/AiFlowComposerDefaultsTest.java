package com.mobile.standalone.service.compose;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成出来的草稿，环境和轨道默认落在哪。
 *
 * <p>这两个值决定这条流程以后会被<b>哪些批次捞走</b>，默认值选反了不会当场报错——
 * 而是过一阵子在某个批次里冒出一条没人预期的流程。所以默认值要单独钉住。
 */
class AiFlowComposerDefaultsTest {

    /**
     * 空值必须是 TEST，不是 PROD。
     *
     * <p>踩过一次：直接用 {@code TestEnvironment.normalize} 会得到 PROD——
     * 它对空值返回 PROD 是为了兼容没有这个字段的存量任务。
     * 但这里是一条刚生成、一次都没跑过的草稿，默认落到生产是反的。
     */
    @Test
    void 没选环境时默认测试环境() {
        assertEquals("TEST", AiFlowComposer.normalizeEnvironment(null));
        assertEquals("TEST", AiFlowComposer.normalizeEnvironment(""));
        assertEquals("TEST", AiFlowComposer.normalizeEnvironment("  "));
    }

    @Test
    void 选了就按选的来() {
        assertEquals("PROD", AiFlowComposer.normalizeEnvironment("PROD"));
        assertEquals("TEST", AiFlowComposer.normalizeEnvironment("test"));
    }

    @Test
    void 没选轨道时只进自动化轨() {
        assertEquals("AUTOMATION", AiFlowComposer.normalizeCatalog(null));
        assertEquals("AUTOMATION", AiFlowComposer.normalizeCatalog(""));
    }

    @Test
    void 认得出的轨道原样保留() {
        assertEquals("ONLINE", AiFlowComposer.normalizeCatalog("online"));
        assertEquals("SHARED", AiFlowComposer.normalizeCatalog("SHARED"));
        assertEquals("PERFORMANCE", AiFlowComposer.normalizeCatalog("performance"));
    }

    /** 写错一个值不该让它悄悄进线上回归那一侧 */
    @Test
    void 认不出的归到最保守的那个() {
        assertEquals("AUTOMATION", AiFlowComposer.normalizeCatalog("线上"));
        assertEquals("AUTOMATION", AiFlowComposer.normalizeCatalog("REGRESSION"));
    }

    /**
     * builder 能少传字段而不炸。
     *
     * <p>这个参数表被追着加过三次字段，每次按位置传参的调用方都编不过，
     * 而报错长得像类型错误、看不出是有人加了字段。
     * 改成 builder 之后再加字段不会打断任何调用方——这条测试钉住这一点。
     */
    @Test
    void builder允许只传必要的字段() {
        AiFlowComposer.Request r = AiFlowComposer.Request.builder()
                .appId("APP")
                .platform("ANDROID")
                .intent("随便逛逛")
                .build();
        assertEquals("APP", r.appId());
        assertEquals("ANDROID", r.platform());
        assertEquals("随便逛逛", r.intent());
        // 没传的就是 null，交给各自的 normalize 兜底
        assertNull(r.environment());
        assertNull(r.catalog());
        assertNull(r.releaseStage());
        assertNull(r.pageIds());
    }

    // ==================== 解析：嵌套子步骤不能被拍平 ====================

    /**
     * <b>这条测试守的是一个真实事故。</b>
     *
     * <p>原来这里是一行 {@code asText()}，而 Jackson 对数组和对象节点的 {@code asText()}
     * 返回<b>空字符串</b>。于是模型哪怕把 thenSteps 完全写对，到了这里也会被拍成 ""，
     * 落库的流程里判断分支是空的——表现就是「AI 编的流程根本跑不起来」。
     *
     * <p>最坑的是它<b>看起来是对的</b>：外层大纲步数一步不少，
     * 只有点进判断步骤才会发现里面什么都没有。
     */
    @Test
    void 解析要保住判断分支里的子步骤() {
        String raw = """
                [
                  {"keywordCode":"IF_ELEMENT_EXISTS","elementKey":"tab_mall",
                   "params":{"timeoutSec":5,
                             "thenSteps":[{"keywordCode":"CLICK","elementKey":"tab_mall","params":{}}],
                             "elseSteps":[{"keywordCode":"FINISH_FAILED","params":{"message":"没有商城入口"}}]}}
                ]
                """;
        List<FlowDraftValidator.Step> steps = AiFlowComposer.parseSteps(raw);
        assertEquals(1, steps.size());

        Object then = steps.get(0).params().get("thenSteps");
        assertTrue(then instanceof List, "thenSteps 被拍平成了 " + then);
        assertEquals(1, FlowDraftValidator.asSteps(then).size());
        assertEquals("CLICK", FlowDraftValidator.asSteps(then).get(0).keywordCode());

        Object els = steps.get(0).params().get("elseSteps");
        assertEquals(1, FlowDraftValidator.asSteps(els).size());
        assertEquals("FINISH_FAILED", FlowDraftValidator.asSteps(els).get(0).keywordCode());
    }

    /** 再嵌一层也要活着——判断里套判断是人工流程里常见的写法 */
    @Test
    void 嵌套两层也不丢() {
        String raw = """
                [{"keywordCode":"IF_ELEMENT_EXISTS","elementKey":"a","params":{"thenSteps":[
                    {"keywordCode":"IF_ELEMENT_EXISTS","elementKey":"b","params":{"thenSteps":[
                        {"keywordCode":"CLICK","elementKey":"c","params":{}}]}}]}}]
                """;
        List<FlowDraftValidator.Step> steps = AiFlowComposer.parseSteps(raw);
        List<FlowDraftValidator.Step> lv1 = FlowDraftValidator.asSteps(steps.get(0).params().get("thenSteps"));
        assertEquals(1, lv1.size());
        List<FlowDraftValidator.Step> lv2 = FlowDraftValidator.asSteps(lv1.get(0).params().get("thenSteps"));
        assertEquals("CLICK", lv2.get(0).keywordCode());
        assertEquals("c", lv2.get(0).elementKey());
    }

    /** 标量还是原来的样子：数字别变成字符串，布尔别变成 "true" */
    @Test
    void 标量类型保持原样() {
        List<FlowDraftValidator.Step> steps = AiFlowComposer.parseSteps(
                "[{\"keywordCode\":\"SWIPE_PAIR\",\"params\":"
                        + "{\"upTimes\":2,\"axis\":\"vertical\",\"verifyChanged\":true}}]");
        Map<String, Object> p = steps.get(0).params();
        assertTrue(p.get("upTimes") instanceof Number, "upTimes 变成了 " + p.get("upTimes"));
        assertEquals("vertical", p.get("axis"));
        assertEquals(Boolean.TRUE, p.get("verifyChanged"));
    }

    // ==================== 范例：不能把自己的产物喂回去 ====================

    /**
     * AI 草稿的描述前缀是唯一的排除依据，改了它就等于打开了自我强化的闭环：
     * 模型编错 → 进库 → 下次当「人工范例」喂回去 → 错误被当成惯例。
     * 这种退化没有任何报错，只会表现为「越编越差」。
     */
    @Test
    void AI草稿的描述前缀和排除依据是同一个() {
        assertTrue(("由 AI 按描述编排：浏览商城").startsWith(AiFlowComposer.AI_DRAFT_MARK));
        assertFalse(("人工编写的浏览商城流程").startsWith(AiFlowComposer.AI_DRAFT_MARK));
    }

    /** 范例只保留三个字段：库里那份带着界面状态，原样贴进去模型会以为那些也要输出 */
    @Test
    void 范例渲染只留三个字段() {
        AiFlowComposer c = new AiFlowComposer(null, null, null, null);
        String slim = c.slimSteps("""
                [{"id":"s1","expanded":true,"keywordCode":"CLICK","elementKey":"tab_mall",
                  "params":{"mall_category_tab":"刀塔商城","thenSteps":[]}}]
                """);
        assertTrue(slim.contains("keywordCode"));
        assertTrue(slim.contains("mall_category_tab"), slim);
        assertFalse(slim.contains("expanded"), slim);
        assertFalse(slim.contains("\"id\""), slim);
        // 空的子步骤数组不用留，占位而已
        assertFalse(slim.contains("thenSteps"), slim);
    }

    @Test
    void 范例渲染保留嵌套分支() {
        AiFlowComposer c = new AiFlowComposer(null, null, null, null);
        String slim = c.slimSteps("""
                [{"keywordCode":"IF_ELEMENT_EXISTS","elementKey":"a","params":{"thenSteps":[
                   {"keywordCode":"CLICK","elementKey":"b","params":{}}]}}]
                """);
        assertTrue(slim.contains("thenSteps"), slim);
        assertTrue(slim.contains("\"CLICK\""), slim);
    }

    /** 没有范例时就是空串，不能凭空多出一个标题 */
    @Test
    void 没有范例时不输出小节() {
        AiFlowComposer c = new AiFlowComposer(null, null, null, null);
        assertEquals("", c.renderExamples(null));
        assertEquals("", c.renderExamples(List.of()));
    }
}
