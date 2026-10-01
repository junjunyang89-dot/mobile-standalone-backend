package com.mobile.standalone.service.inspect;

import java.util.Locale;

/**
 * H5 到底渲染出来了没有——以及<b>白屏是谁的锅</b>。
 *
 * <p>背景：线上出过「某些机型上 H5 页面打开就是白屏，同一个地址在浏览器里是好的」。
 * 这类问题的根在 X5 内核（TBS）——内核是<b>运行时下载</b>的，所以同一个包今天好明天坏，
 * 触发条件是「机型 × 内核版本」的组合。
 *
 * <h3>为什么不能用像素判白屏</h3>
 *
 * 平台里原有的 {@code isMostlyBlackOrWhite} 是「整屏 80% 以上是黑或白」。对这个问题两头都不准：
 * <ul>
 *   <li><b>误报</b>：合法的白底页面（登录页、空列表）长得一模一样</li>
 *   <li><b>漏报</b>：白屏时状态栏 + 标题栏 + 底部导航还在，白的那块占不到 80%</li>
 * </ul>
 * 白屏的本质是<b>那块 WebView 里没东西</b>，不是「屏幕偏白」。所以判据应该问 DOM，不是问像素。
 *
 * <h3>这个类最有价值的地方：把白屏分成两类</h3>
 *
 * <ul>
 *   <li><b>DOM 是空的</b> → 页面没加载出来。这是 H5 / 网络 / 接口那边的事</li>
 *   <li><b>DOM 有内容、画面却是空的</b> → 内容在，只是没画出来。
 *       这是<b>内核渲染层</b>的问题，X5 的典型症状</li>
 * </ul>
 *
 * 这两类现象在截图上完全一样，但<b>要找的人不是同一拨</b>。
 * 分不开的话，每次白屏都要两边扯皮一轮——而这正是这类问题发现慢的原因。
 */
public final class H5RenderVerdict {

    private H5RenderVerdict() {
    }

    /** 多少个字符算「有内容」。太小会把「加载中…」这种骨架文案当成渲染成功 */
    public static final int DEFAULT_MIN_TEXT = 8;

    public enum Level {
        /** 有内容，画面也正常 */
        RENDERED,
        /** DOM 空 —— 页面没加载出来，找 H5 / 接口 */
        BLANK_DOM,
        /** DOM 有内容但画面空 —— 没画出来，找内核（X5 典型） */
        BLANK_RENDER,
        /**
         * 探针没跑成，<b>不是「通过」</b>。
         *
         * <p>最常见的原因是 release 包没开 WebView 调试通道，切不进 WEBVIEW context。
         * 这时候只能说「没验成」，绝不能说「渲染正常」——
         * 后者会让一条什么都没验的断言常年挂绿灯。
         */
        UNVERIFIABLE
    }

    /**
     * 探针读回来的东西。
     *
     * @param textLength   用 {@code innerText} 的长度，<b>不是 textContent</b>。
     *                     innerText 只算<b>渲染出来</b>的文字；textContent 连
     *                     {@code display:none} 的都算——用它的话，
     *                     一个 DOM 齐全但整体没显示的白屏页会被判成正常。
     * @param mediaCount   已经真正加载出来的图/画布/视频。img 只数 {@code naturalWidth > 0} 的，
     *                     因为一页全是加载失败的图，看上去就是白屏
     * @param regionBlank  WebView 那块区域在截图上是不是空白；拿不到就传 null
     */
    public record Probe(boolean contextAvailable, String contextMessage,
                        String readyState, Boolean bodyPresent,
                        Integer textLength, Integer mediaCount, Integer elementCount,
                        String url, String error) {
    }

    /**
     * @param level   判定
     * @param summary 一句话结论
     * @param advice  下一步该找谁 / 该看什么。白屏这种事最费时间的是扯皮，不是定位
     */
    public record Verdict(Level level, String summary, String advice) {
        public boolean passed() {
            return level == Level.RENDERED;
        }
    }

    public static Verdict judge(Probe p, int minTextLength, Boolean regionBlank) {
        // 负数才用默认值；0 是调用方明确表达「这页没文字」，得让它生效。
        //
        // 但 0 不能退化成「只要有 body 就算过」——那样这条断言什么都验不到，
        // 而一条永远为真的断言比没有断言更糟：它让人以为这一页被看过了。
        // 所以下面用 max(minText, 1)：0 的含义是「一个字就够」，不是「不用有东西」。
        int minText = minTextLength >= 0 ? minTextLength : DEFAULT_MIN_TEXT;
        int textFloor = Math.max(minText, 1);

        if (p == null) {
            return new Verdict(Level.UNVERIFIABLE, "没拿到任何探针结果", "先确认这一步之前页面已经打开");
        }
        if (!p.contextAvailable()) {
            String why = blank(p.contextMessage())
                    ? "切不进 WebView context" : p.contextMessage();
            return new Verdict(Level.UNVERIFIABLE,
                    "没能读到 WebView 里的内容：" + why,
                    "这不算通过，只是没验成。最常见的原因是这个包没开 WebView 调试通道——"
                            + "测试包里把 setWebContentsDebuggingEnabled(true) 打开就能验了");
        }
        if (!blank(p.error())) {
            return new Verdict(Level.UNVERIFIABLE,
                    "探针脚本执行出错：" + p.error(),
                    "这不算通过。把这条错误发给我，多半是页面里有东西挡住了脚本执行");
        }

        int text = p.textLength() == null ? 0 : p.textLength();
        int media = p.mediaCount() == null ? 0 : p.mediaCount();
        int els = p.elementCount() == null ? 0 : p.elementCount();
        boolean hasBody = !Boolean.FALSE.equals(p.bodyPresent());
        String state = p.readyState() == null ? "" : p.readyState().toLowerCase(Locale.ROOT);
        String where = blank(p.url()) ? "" : "（" + p.url() + "）";

        boolean hasContent = hasBody && (text >= textFloor || media > 0);

        if (!hasContent) {
            StringBuilder sb = new StringBuilder("WebView 里没有渲染出内容");
            sb.append(where).append("：");
            if (!hasBody) {
                sb.append("document.body 都不存在");
            } else {
                sb.append("可见文字 ").append(text).append(" 字")
                  .append("（要求 ≥ ").append(textFloor).append("）")
                  .append("、已加载的图/视频 ").append(media).append(" 个")
                  .append("、DOM 节点 ").append(els).append(" 个");
            }
            if (!state.isEmpty()) {
                sb.append("，readyState=").append(state);
            }
            // DOM 节点都没几个 → 页面压根没下来；节点不少却没内容 → 大概率是数据没回来
            String advice = els <= 5
                    ? "页面根本没加载出来。先看这个地址在手机浏览器里打不打得开、接口有没有返回——"
                            + "这一类不是内核的问题"
                    : "DOM 结构在但内容是空的，多半是数据没回来或渲染逻辑报错了。"
                            + "先让 H5 那边看接口和控制台";
            if ("loading".equals(state)) {
                advice = "页面还在 loading。先把这一步的超时调大一点；仍然超时再按上面查";
            }
            return new Verdict(Level.BLANK_DOM, sb.toString(), advice);
        }

        if (Boolean.TRUE.equals(regionBlank)) {
            // 这是最值钱的一条：内容在 DOM 里，但画面上什么都没有
            return new Verdict(Level.BLANK_RENDER,
                    "DOM 里有内容（可见文字 " + text + " 字、图/视频 " + media
                            + " 个），但 WebView 那块区域在截图上是空白的" + where,
                    "内容取到了、只是没画出来，这是<b>内核渲染层</b>的问题，不是 H5 的。"
                            + "先记下这台机器的 TBS 内核版本（debug 面板里有），"
                            + "再用系统内核跑同一个页面对比——如果系统内核正常，就是 X5 的锅");
        }

        return new Verdict(Level.RENDERED,
                "渲染正常：可见文字 " + text + " 字、图/视频 " + media + " 个" + where, null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
