package com.mobile.standalone.service.compose;

import com.mobile.standalone.service.compose.FlowDraftValidator.ElementRef;
import com.mobile.standalone.service.compose.FlowDraftValidator.KeywordRef;
import com.mobile.standalone.service.compose.FlowDraftValidator.Result;
import com.mobile.standalone.service.compose.FlowDraftValidator.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 编出来的流程，哪些必须打回、哪些替它补上。
 *
 * <p>这组测试守的是一句话：<b>人打开草稿时，不该再当一次校验器</b>。
 * 元素不存在、关键字编的、参数缺了、断言在自我循环——这些如果漏过去，
 * 人就得逐条查，而那正是这个功能想省掉的事。
 */
class FlowDraftValidatorTest {

    private static Map<String, ElementRef> elements() {
        Map<String, ElementRef> m = new LinkedHashMap<>();
        m.put("btn_follow", new ElementRef("btn_follow", "page_video_detail", "id:com.x:id/btn_follow"));
        m.put("comment_input", new ElementRef("comment_input", "page_video_detail", "id:com.x:id/comment_et"));
        m.put("comment_first", new ElementRef("comment_first", "page_video_detail", "id:com.x:id/c_first"));
        m.put("video_card", new ElementRef("video_card", "page_home", "id:com.x:id/video_card"));
        m.put("tab_dynamic", new ElementRef("tab_dynamic", "page_home", "xpath://*[@text='${tab_name}']"));
        m.put("mine_entry", new ElementRef("mine_entry", "page_mine", "id:com.x:id/mine"));
        return m;
    }

    private static Map<String, KeywordRef> keywords() {
        Map<String, KeywordRef> m = new LinkedHashMap<>();
        m.put("CLICK", new KeywordRef("CLICK", true,
                Set.of("elementKey", "clickMethod", "waitAfterMs"), Set.of()));
        m.put("INPUT", new KeywordRef("INPUT", true,
                Set.of("elementKey", "text"), Set.of("text")));
        m.put("WAIT_ELEMENT", new KeywordRef("WAIT_ELEMENT", true,
                Set.of("elementKey", "waitType", "timeoutSec"), Set.of()));
        m.put("ASSERT_TEXT", new KeywordRef("ASSERT_TEXT", true,
                Set.of("elementKey", "expected", "mode"), Set.of("expected")));
        m.put("ASSERT_NO_ERROR", new KeywordRef("ASSERT_NO_ERROR", false,
                Set.of("errorTexts", "failOnMatch"), Set.of()));
        m.put("SWIPE", new KeywordRef("SWIPE", false,
                Set.of("direction", "durationMs", "times", "intervalMs"), Set.of()));
        m.put("SWIPE_PAIR", new KeywordRef("SWIPE_PAIR", false,
                Set.of("axis", "upTimes", "downTimes", "orderVertical"), Set.of()));
        m.put("CLICK_EACH", new KeywordRef("CLICK_EACH", true,
                Set.of("elementKey", "varName", "sequence", "clickMethod"), Set.of("sequence")));
        m.put("IF_ELEMENT_EXISTS", new KeywordRef("IF_ELEMENT_EXISTS", true,
                Set.of("elementKey", "timeoutSec", "thenSteps", "elseSteps", "elseMessage", "endStatus"),
                Set.of(), Set.of("thenSteps", "elseSteps")));
        return m;
    }

    private static Step step(String code, String el, Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new Step(code, el, p);
    }

    private static Result check(List<Step> steps, List<String> pages) {
        return FlowDraftValidator.check(new ArrayList<>(steps), elements(), keywords(),
                pages, Set.of());
    }

    // ---------- 必须打回 ----------

    @Test
    void 编了不存在的关键字() {
        Result r = check(List.of(step("TAP_ELEMENT", "btn_follow")), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().get(0).contains("不存在的关键字"));
    }

    @Test
    void 引用了库里没有的元素() {
        Result r = check(List.of(step("CLICK", "btn_follow_v2")), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().get(0).contains("元素库里没有"));
    }

    @Test
    void 需要元素却没给() {
        Result r = check(List.of(step("CLICK", null)), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().get(0).contains("需要元素"));
    }

    @Test
    void 缺必填参数() {
        Result r = check(List.of(step("INPUT", "comment_input")), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().get(0).contains("缺必填参数：text"));
    }

    @Test
    void 用了勾选范围之外页面的元素() {
        // 只勾了详情页，却用了「我的」页的元素——多半是 AI 把流程编到别的地方去了
        Result r = check(List.of(step("CLICK", "mine_entry")), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().get(0).contains("不在这次勾选的页面范围里"));
    }

    /** 这条是这个校验器存在的核心理由之一 */
    @Test
    void 断言上一步刚点过的元素要打回() {
        Result r = check(List.of(
                step("CLICK", "btn_follow"),
                step("ASSERT_TEXT", "btn_follow", "expected", "关注", "mode", "equals")
        ), List.of("page_video_detail"));
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("验不到任何东西")),
                "点完 A 再断言 A 还在是同义反复，必须挡住");
    }

    @Test
    void 断言别的元素是正常的() {
        Result r = check(List.of(
                step("CLICK", "btn_follow"),
                step("ASSERT_TEXT", "comment_first", "expected", "已关注", "mode", "equals")
        ), List.of("page_video_detail"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    /** 断言前面隔着等待时，不该误判成自我循环 */
    @Test
    void 中间隔了等待就不算自我循环() {
        Result r = check(List.of(
                step("CLICK", "btn_follow"),
                step("WAIT_ELEMENT", "btn_follow", "waitType", "Visible", "timeoutSec", 10),
                step("ASSERT_TEXT", "btn_follow", "expected", "已关注", "mode", "equals")
        ), List.of("page_video_detail"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    @Test
    void 一步都没生成() {
        Result r = check(List.of(), List.of("page_home"));
        assertFalse(r.usable());
    }

    // ---------- 自动补，不打回 ----------

    @Test
    void 点击后面自动补等待() {
        Result r = check(List.of(
                step("CLICK", "video_card"),
                step("INPUT", "comment_input", "text", "测试评论")
        ), List.of("page_home", "page_video_detail"));

        assertTrue(r.usable(), String.valueOf(r.problems()));
        assertEquals(3, r.steps().size(), "应该在点击和输入之间插一步等待");
        assertEquals("WAIT_ELEMENT", r.steps().get(1).keywordCode());
        // 等的是下一步要用的元素，不是刚点过的那个——刚点过的往往正要消失
        assertEquals("comment_input", r.steps().get(1).elementKey());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("跟渲染赛跑")));
    }

    @Test
    void 已经有等待就不重复补() {
        Result r = check(List.of(
                step("CLICK", "video_card"),
                step("WAIT_ELEMENT", "comment_input", "waitType", "Visible", "timeoutSec", 10),
                step("INPUT", "comment_input", "text", "测试评论")
        ), List.of("page_home", "page_video_detail"));
        assertEquals(3, r.steps().size());
    }

    @Test
    void 最后一步是点击时不补() {
        Result r = check(List.of(
                step("INPUT", "comment_input", "text", "x"),
                step("CLICK", "btn_follow")
        ), List.of("page_video_detail"));
        assertEquals(2, r.steps().size());
    }

    // ---------- 提示，不打回 ----------

    @Test
    void 定位里的变量没给值要提示() {
        Result r = check(List.of(step("CLICK", "tab_dynamic")), List.of("page_home"));
        assertTrue(r.usable(), "变量缺值不该挡住草稿生成，人补上就行");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("tab_name")));
    }

    @Test
    void 变量给了就不提示() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(step("CLICK", "tab_dynamic"))),
                elements(), keywords(), List.of("page_home"), Set.of("tab_name"));
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("tab_name")));
    }

    @Test
    void 多余参数只提示不打回() {
        Result r = check(List.of(step("CLICK", "btn_follow", "timeoutMs", 3000)),
                List.of("page_video_detail"));
        assertTrue(r.usable());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("用不上的参数")));
    }

    @Test
    void 不限定页面时不查页面范围() {
        Result r = check(List.of(step("CLICK", "mine_entry")), List.of());
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    // ---------- 该用聚合关键字却排成重复步骤 ----------

    /** 人说「上滑2次下滑4次」，模型排成六步 SWIPE——这正是要挡的 */
    @Test
    void 连着一样的滑动要打回() {
        Result r = check(List.of(
                step("SWIPE", null, "direction", "up"),
                step("SWIPE", null, "direction", "up")
        ), List.of("page_home"));
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("SWIPE_PAIR")),
                "应该提示用 SWIPE_PAIR 一步写完");
    }

    @Test
    void 连着点同一个元素要打回() {
        Result r = check(List.of(
                step("CLICK", "video_card"),
                step("CLICK", "video_card")
        ), List.of("page_home"));
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("CLICK_EACH")));
    }

    /** 方向不同就是两件事，不该误判 */
    @Test
    void 方向不同的滑动不算重复() {
        Result r = check(List.of(
                step("SWIPE", null, "direction", "up"),
                step("SWIPE", null, "direction", "down")
        ), List.of("page_home"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    /** 点不同元素是正常流程，不能拦 */
    @Test
    void 点不同元素不算重复() {
        Result r = check(List.of(
                step("CLICK", "video_card"),
                step("CLICK", "btn_follow")
        ), List.of("page_home", "page_video_detail"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    @Test
    void 只有一步滑动不算重复() {
        Result r = check(List.of(step("SWIPE", null, "direction", "up")), List.of("page_home"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    // ---------- 条件分支的子步骤 ----------

    private static Map<String, Object> nested(String key, List<Map<String, Object>> children) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, children);
        return p;
    }

    private static Map<String, Object> child(String code, String el) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keywordCode", code);
        if (el != null) m.put("elementKey", el);
        m.put("params", new LinkedHashMap<String, Object>());
        return m;
    }

    /**
     * 外层全是「判断/等待/断言」，看着清清白白，
     * 真正干活的点击藏在 thenSteps 里引用了不存在的元素——只查外层就放过去了。
     */
    @Test
    void 子步骤里的坏元素也要查出来() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(new Step("IF_ELEMENT_EXISTS", "video_card",
                        nested("thenSteps", List.of(child("CLICK", "btn_not_exist")))))),
                elements(), keywords(), List.of("page_home"), Set.of());
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("thenSteps") && p.contains("元素库里没有")),
                "问题要指明是哪个子步骤参数里的：" + r.problems());
    }

    @Test
    void 子步骤里编的关键字也要查出来() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(new Step("IF_ELEMENT_EXISTS", "video_card",
                        nested("elseSteps", List.of(child("TAP_IT", "video_card")))))),
                elements(), keywords(), List.of("page_home"), Set.of());
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("elseSteps")));
    }

    @Test
    void 子步骤都正常时不报错() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(new Step("IF_ELEMENT_EXISTS", "video_card",
                        nested("thenSteps", List.of(child("CLICK", "btn_follow")))))),
                elements(), keywords(), List.of("page_home", "page_video_detail"), Set.of());
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    /**
     * 判断完什么也不做 —— 实测里 AI 就是这么编的：
     * 九步里三个「元素存在判断」，thenSteps 全是空的，一个点击都没有。
     */
    @Test
    void 空的条件分支要打回() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(new Step("IF_ELEMENT_EXISTS", "video_card",
                        new LinkedHashMap<>()))),
                elements(), keywords(), List.of("page_home"), Set.of());
        assertFalse(r.usable());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("一个子步骤都没有")),
                String.valueOf(r.problems()));
    }

    @Test
    void 只有elseSteps也算有内容() {
        Result r = FlowDraftValidator.check(
                new ArrayList<>(List.of(new Step("IF_ELEMENT_EXISTS", "video_card",
                        nested("elseSteps", List.of(child("BACK", null)))))),
                elements(), keywords(), List.of("page_home"), Set.of());
        // BACK 不在这个测试的关键字表里，所以会因为别的原因报错，
        // 但不该是「一个子步骤都没有」
        assertTrue(r.problems().stream().noneMatch(p -> p.contains("一个子步骤都没有")),
                String.valueOf(r.problems()));
    }

    @Test
    void 子步骤为空或格式怪不炸() {
        assertTrue(FlowDraftValidator.asSteps(null).isEmpty());
        assertTrue(FlowDraftValidator.asSteps("不是列表").isEmpty());
        assertTrue(FlowDraftValidator.asSteps(List.of()).isEmpty());
        assertTrue(FlowDraftValidator.asSteps(List.of("字符串", 123)).isEmpty());
    }

    @Test
    void 不需要元素的关键字不用给元素() {
        Result r = check(List.of(step("ASSERT_NO_ERROR", null)), List.of("page_home"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
    }

    // ==================== 文案变量：人工流程里最常用的写法 ====================

    /**
     * 同一个元素配文案变量，是人工流程里用得最多的写法：
     * 「CS商城 / 刀塔商城」共用一个 {@code mall_category_tab} 元素，
     * 点哪个就在这一步 params 里给变量赋值。
     *
     * <p>改之前这种步骤会被挑<b>两处毛病</b>：
     * 说这个参数「用不上、执行时会被忽略」，又说这个变量「没给值」——两条都是错的。
     * 照着人的正确写法编出来的草稿反被挑毛病，人只会以为是 AI 编错了。
     */
    @Test
    void 文案变量不算多余参数也不算缺变量() {
        Result r = check(List.of(step("CLICK", "tab_dynamic", "tab_name", "刀塔商城")),
                List.of("page_home"));
        assertTrue(r.usable(), String.valueOf(r.problems()));
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("用不上的参数")),
                String.valueOf(r.notes()));
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("没给值")),
                String.valueOf(r.notes()));
    }

    /** 真没给值的变量还是要提示——放过文案变量不能把这条一起放过 */
    @Test
    void 变量一个都没给时照样提示() {
        Result r = check(List.of(step("CLICK", "tab_dynamic")), List.of("page_home"));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("tab_name")),
                String.valueOf(r.notes()));
    }

    /** 名字对不上的参数仍然是多余的：放过的只是「定位里确实有这个变量」的那些 */
    @Test
    void 名字对不上的参数仍然提示() {
        Result r = check(List.of(step("CLICK", "tab_dynamic", "tab_name", "刀塔商城",
                        "tabName", "写错了")),
                List.of("page_home"));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("tabName")),
                String.valueOf(r.notes()));
    }

    /** 子步骤数组不能被当成「填了变量」——那样真正缺的变量会被漏报 */
    @Test
    void 子步骤数组不算填了变量() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tab_name", List.of(Map.of("keywordCode", "CLICK", "elementKey", "btn_follow")));
        Result r = FlowDraftValidator.check(List.of(new Step("CLICK", "tab_dynamic", p)),
                elements(), keywords(), List.of("page_home"), Set.of());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("tab_name")),
                String.valueOf(r.notes()));
    }

    // ==================== 判断不成立时的结局 ====================

    /**
     * elseSteps 空着时，流程按 endStatus 结束，默认 FAIL＝整条失败。
     * 对必现页面这是对的，对可选弹窗就是错的——弹窗那天没出现，用例报失败。
     * 这类误报最难查：今天红明天绿，看起来像偶发。
     */
    @Test
    void 判断没有不存在分支时提示默认会判失败() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("thenSteps", List.of(Map.of("keywordCode", "CLICK", "elementKey", "btn_follow")));
        Result r = FlowDraftValidator.check(List.of(new Step("IF_ELEMENT_EXISTS", "video_card", p)),
                elements(), keywords(), List.of("page_home", "page_video_detail"), Set.of());
        assertTrue(r.usable(), String.valueOf(r.problems()));
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("endStatus")),
                String.valueOf(r.notes()));
    }

    /** 明确写了 endStatus 就是想清楚了，别再唠叨 */
    @Test
    void 写了endStatus就不提示() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("endStatus", "PASS");
        p.put("thenSteps", List.of(Map.of("keywordCode", "CLICK", "elementKey", "btn_follow")));
        Result r = FlowDraftValidator.check(List.of(new Step("IF_ELEMENT_EXISTS", "video_card", p)),
                elements(), keywords(), List.of("page_home", "page_video_detail"), Set.of());
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("endStatus")),
                String.valueOf(r.notes()));
    }

    /** 有 elseSteps 的（人工流程里那种「不存在→失败结束」）也不用提示 */
    @Test
    void 有不存在分支时不提示() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("thenSteps", List.of(Map.of("keywordCode", "CLICK", "elementKey", "btn_follow")));
        p.put("elseSteps", List.of(Map.of("keywordCode", "ASSERT_NO_ERROR")));
        Result r = FlowDraftValidator.check(List.of(new Step("IF_ELEMENT_EXISTS", "video_card", p)),
                elements(), keywords(), List.of("page_home", "page_video_detail"), Set.of());
        assertTrue(r.notes().stream().noneMatch(n -> n.contains("endStatus")),
                String.valueOf(r.notes()));
    }
}
