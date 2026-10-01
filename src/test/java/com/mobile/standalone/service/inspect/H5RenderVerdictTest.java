package com.mobile.standalone.service.inspect;

import com.mobile.standalone.service.inspect.H5RenderVerdict.Level;
import com.mobile.standalone.service.inspect.H5RenderVerdict.Probe;
import com.mobile.standalone.service.inspect.H5RenderVerdict.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H5 白屏判定。
 *
 * <p>这组测试守三条：
 * <ol>
 *   <li><b>验不了绝不能算通过</b>——那是一条常年绿灯却什么都没验的断言，
 *       比没有这条断言更糟</li>
 *   <li><b>DOM 空 和 DOM 有内容但没画出来，必须分开</b>——
 *       两者截图长得一样，但要找的人不是同一拨</li>
 *   <li><b>骨架文案不算渲染成功</b></li>
 * </ol>
 */
class H5RenderVerdictTest {

    private static Probe ok(int text, int media, int els) {
        return new Probe(true, null, "complete", true, text, media, els,
                "https://m.pwesports.cn/gameMall.html", null);
    }

    // ---------- 验不了 ----------

    /**
     * release 包没开 WebView 调试通道时切不进 context。
     *
     * <p>这时候<b>只能说「没验成」</b>。算通过的话，所有 H5 流程都会挂着一条绿的
     * 「已渲染」断言，而它从来没有真的看过一眼页面——
     * 这正是 X5 白屏能在线上活那么久的原因之一。
     */
    @Test
    void 切不进context不算通过() {
        Verdict v = H5RenderVerdict.judge(
                new Probe(false, "只有原生 context（NATIVE_APP），读不到页面内容", null, null,
                        null, null, null, null, null),
                8, null);
        assertEquals(Level.UNVERIFIABLE, v.level());
        assertFalse(v.passed());
        assertTrue(v.advice().contains("setWebContentsDebuggingEnabled"), v.advice());
    }

    @Test
    void 探针报错也不算通过() {
        Verdict v = H5RenderVerdict.judge(
                new Probe(true, null, null, null, null, null, null, null, "SecurityError: blocked"),
                8, null);
        assertEquals(Level.UNVERIFIABLE, v.level());
        assertTrue(v.summary().contains("SecurityError"), v.summary());
    }

    @Test
    void 没有探针结果也不算通过() {
        assertEquals(Level.UNVERIFIABLE, H5RenderVerdict.judge(null, 8, null).level());
    }

    // ---------- DOM 空：找 H5 / 接口 ----------

    @Test
    void DOM空判白屏() {
        Verdict v = H5RenderVerdict.judge(ok(0, 0, 3), 8, null);
        assertEquals(Level.BLANK_DOM, v.level());
        assertFalse(v.passed());
    }

    @Test
    void body都没有() {
        Verdict v = H5RenderVerdict.judge(
                new Probe(true, null, "loading", false, 0, 0, 0, "about:blank", null), 8, null);
        assertEquals(Level.BLANK_DOM, v.level());
        assertTrue(v.summary().contains("document.body"), v.summary());
    }

    /** 节点都没几个 = 页面根本没下来；节点不少却空 = 数据没回来。两种查法不一样 */
    @Test
    void 按DOM节点数给不同的查法() {
        String few = H5RenderVerdict.judge(ok(0, 0, 2), 8, null).advice();
        String many = H5RenderVerdict.judge(ok(0, 0, 300), 8, null).advice();
        assertTrue(few.contains("根本没加载"), few);
        assertTrue(many.contains("数据没回来") || many.contains("接口"), many);
        assertFalse(few.equals(many), "两种情况给了同一句建议");
    }

    /** 还在 loading 时该说「等久一点」，不是让人去查接口 */
    @Test
    void 还在loading时先让人加超时() {
        Verdict v = H5RenderVerdict.judge(
                new Probe(true, null, "loading", true, 0, 0, 50, "https://x/y", null), 8, null);
        assertEquals(Level.BLANK_DOM, v.level());
        assertTrue(v.advice().contains("超时"), v.advice());
    }

    /** 「加载中…」这种骨架文案不能算渲染成功 */
    @Test
    void 骨架文案不算渲染成功() {
        assertEquals(Level.BLANK_DOM, H5RenderVerdict.judge(ok(4, 0, 40), 8, null).level());
    }

    // ---------- DOM 有内容但没画出来：找内核 ----------

    /**
     * <b>这条是这个类存在的理由。</b>
     *
     * <p>内容在 DOM 里、画面却是空的 —— 取到了但没画出来，这是内核渲染层的问题。
     * 和「DOM 空」在截图上完全一样，但要找的人不是同一拨。
     * 分不开的话，每次白屏都要 H5 和客户端扯皮一轮。
     */
    @Test
    void DOM有内容但画面空判成渲染层问题() {
        Verdict v = H5RenderVerdict.judge(ok(200, 6, 400), 8, Boolean.TRUE);
        assertEquals(Level.BLANK_RENDER, v.level());
        assertFalse(v.passed());
        assertTrue(v.advice().contains("内核"), v.advice());
        assertTrue(v.advice().contains("TBS") || v.advice().contains("系统内核"), v.advice());
    }

    /** 拿不到区域截图时传 null，不能因此判成渲染层问题 */
    @Test
    void 不知道画面是否空白时不乱扣帽子() {
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(200, 6, 400), 8, null).level());
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(200, 6, 400), 8, Boolean.FALSE).level());
    }

    /** DOM 本来就空的时候，画面空白是必然结果，不该再归到内核头上 */
    @Test
    void DOM空时不归到内核() {
        Verdict v = H5RenderVerdict.judge(ok(0, 0, 3), 8, Boolean.TRUE);
        assertEquals(Level.BLANK_DOM, v.level(), "DOM 空却被判成了内核渲染问题");
    }

    // ---------- 正常 ----------

    @Test
    void 有文字就算渲染出来了() {
        Verdict v = H5RenderVerdict.judge(ok(120, 0, 200), 8, null);
        assertEquals(Level.RENDERED, v.level());
        assertTrue(v.passed());
        assertNotNull(v.summary());
    }

    /** 纯图片页面：一个字都没有，但图加载出来了，就是渲染成功 */
    @Test
    void 纯图片页面也算渲染出来了() {
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(0, 4, 80), 8, null).level());
    }

    /**
     * minTextLength=0 是给纯图页留的口子，但<b>不能退化成永远为真</b>。
     *
     * <p>第一版这里有两个毛病：0 被当成「没传」silently 换成了默认值 8（文档里写的口子根本不生效），
     * 而直接让 0 生效又会让 {@code text >= 0} 恒真——
     * 这条断言就变成了一条永远绿的摆设，而那比没有断言更糟：它让人以为这一页被看过了。
     *
     * <p>现在 0 的含义是「一个字就够」：纯图页靠图过，完全空白的页仍然不过。
     */
    @Test
    void 阈值调到0时纯图页能过但全空不能过() {
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(0, 3, 80), 0, null).level(),
                "纯图片页面被判成了白屏");
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(1, 0, 80), 0, null).level());
        assertEquals(Level.BLANK_DOM, H5RenderVerdict.judge(ok(0, 0, 80), 0, null).level(),
                "一个字没有、一张图没有，却判成了渲染成功——这条断言成了摆设");
    }

    /** 负数才走默认值 */
    @Test
    void 负数走默认阈值() {
        assertEquals(Level.BLANK_DOM, H5RenderVerdict.judge(ok(4, 0, 80), -1, null).level());
        assertEquals(Level.RENDERED, H5RenderVerdict.judge(ok(20, 0, 80), -1, null).level());
    }

    /** 失败时必须把原始数字写出来，否则人还得自己再去测一遍 */
    @Test
    void 失败结论里要有可查的数字() {
        Verdict v = H5RenderVerdict.judge(ok(2, 0, 37), 8, null);
        assertTrue(v.summary().contains("2"), v.summary());
        assertTrue(v.summary().contains("37"), v.summary());
        assertTrue(v.summary().contains("gameMall"), v.summary());
    }
}
