package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.inspect.UiHierarchyBuilder;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 在某个元素的范围内滑动（金刚位、横向卡片流、区域内上下滚动）。
 *
 * <p><b>为什么不能用整屏的 {@code SWIPE}</b>：金刚位是首页上一条横向图标带，整屏左右滑会被
 * 外层的 ViewPager 抢走，结果是切到了下一个页签，而不是把这条带子往左推。
 * 想滑动某个区域，起点、终点和 Y 坐标必须<b>全部落在这个区域内</b>。
 *
 * <p>三个细节决定了它到底好不好用，都不是能省的：
 * <ul>
 *   <li><b>两端留白</b>：从区域最边上 1px 起手，很容易被系统的边缘手势吃掉
 *       （Android 的侧滑返回、iOS 的边缘返回）。默认两端各留 10%。</li>
 *   <li><b>横滑时 Y 锁在区域垂直中心</b>（纵滑同理锁 X）。偏一点就滑到隔壁控件上去了，
 *       而现象是「滑了但没反应」。</li>
 *   <li><b>区域太窄直接报错</b>，不是滑一个 3px 的距离然后报成功。
 *       「关键字返回成功但界面什么都没发生」是最难查的一类问题——
 *       你会先去怀疑产品、怀疑元素、怀疑设备，最后才想到滑动距离根本不够。</li>
 * </ul>
 */
@Slf4j
@Component
public class SwipeInElementHandler implements KeywordHandler {

    /** 滑动距离小于这个值就没意义了，与其假装成功不如直接说清楚 */
    private static final int MIN_SPAN_PX = 60;

    /** 滑固定次数 */
    private static final String STOP_FIXED = "fixed";
    /** 滑到指定元素出现为止 */
    private static final String STOP_UNTIL = "until";
    private static final List<String> STOP_MODES = List.of(STOP_FIXED, STOP_UNTIL);

    @Override
    public String code() {
        return "SWIPE_IN_ELEMENT";
    }

    @Override
    public String nameZh() {
        return "区域内滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "在指定元素的范围内滑动（金刚位、横向卡片流等）。整屏滑动会被外层容器抢走时用它";
    }

    @Override
    public int sortOrder() {
        // 紧挨着「滑动」(60)，两个关键字在面板上相邻，选的时候能一眼看出区别
        return 61;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "区域内滑动";
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        // 与 SWIPE 一致：滑完要等页面稳定再拍，否则截到的是滑动中途的残影
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "滑动区域元素")
                        .withHelp("选金刚位那一条的容器，不是里面某个图标——滑的是这个范围"),
                ParamDef.enumOf("direction", "方向", SwipeHandler.DIRECTIONS, "left")
                        .withHelp("left＝内容往左走（看后面的），right＝往回看前面的"),
                ParamDef.enumOf("stopMode", "停止条件", STOP_MODES, "fixed")
                        .withHelp("fixed＝滑固定次数；until＝一直滑到指定元素露出来为止。"
                                + "想确认「滑到位了没有」就用 until——它本身就是断言，不用再补一条"),
                ParamDef.number("times", "次数", 1)
                        .showWhen("stopMode", "fixed"),
                ParamDef.element("untilElementKey", "滑到它出现为止")
                        .optional()
                        .showWhen("stopMode", "until")
                        .withHelp("目标元素露出屏幕就停。只认屏幕内可见的——"
                                + "横滑条会把屏幕外的图标也留在控件树里，光看「存在吗」会一次都不滑"),
                ParamDef.number("maxAttempts", "最多滑几次", 10)
                        .showWhen("stopMode", "until")
                        .withHelp("兜底上限，正常滑到就停。滑到头（内容不再变化）也会提前停，不会耗满"),
                ParamDef.bool("failIfNotFound", "没滑到是否失败", true)
                        .showWhen("stopMode", "until"),
                ParamDef.number("durationMs", "单次滑动毫秒", 500)
                        .withHelp("太快会被识别成快速甩动直接滑过头，太慢会被当成长按，500 上下比较稳"),
                ParamDef.number("intervalMs", "滑动间隔毫秒", 300),
                ParamDef.number("marginPercent", "两端留白百分比", 10)
                        .withHelp("从区域最边上起手容易被系统边缘手势吃掉，默认两端各留 10%"),
                ParamDef.bool("verifyChanged", "校验内容是否真的变了", true)
                        .withHelp("读这块区域的文案做前后对比，没变就告警并说明可能原因。"
                                + "固定次数模式下只在开始前、结束后各读一次；"
                                + "「滑到出现为止」模式下每轮读一次，用来判断是不是已经滑到头了——"
                                + "关掉它，滑到头之后还会继续空滑到次数用完"),
                ParamDef.bool("requireScrollable", "要求元素可滚动", true)
                        .withHelp("元素 scrollable=false 时直接失败。这种元素吃不下横滑，"
                                + "手势会穿透给父容器（首页那种外层是页签 ViewPager 的，结果就是切页签）。"
                                + "个别自绘轮播不上报 scrollable 但确实能滑，那种情况才关掉它")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("区域内滑动");
        String direction = normalize(ctx.str("direction"));
        int duration = (int) Math.max(100, ctx.longParam("durationMs", 500));
        int times = (int) Math.min(30, Math.max(1, ctx.longParam("times", 1)));
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));
        int marginPercent = (int) Math.min(45, Math.max(0, ctx.longParam("marginPercent", 10)));

        // 只取一次范围：滑动的是区域里的内容，容器本身不动。
        // 每次循环重取的话，中途布局一抖就会「元素找不到」，把一次本来能跑完的滑动搞挂
        int[] box = ctx.getDriver().getElementBounds(ctx.getDeviceId(), selector);
        if (box == null || box.length < 4) {
            throw ctx.fail("区域内滑动",
                    "拿不到元素范围，无法确定在哪滑：" + ctx.getElementKey()
                            + "（元素没找到，或当前驱动不支持取范围）",
                    selector, "拿不到滑动区域: " + ctx.getElementKey());
        }
        int left = Math.min(box[0], box[2]);
        int top = Math.min(box[1], box[3]);
        int right = Math.max(box[0], box[2]);
        int bottom = Math.max(box[1], box[3]);
        int width = right - left;
        int height = bottom - top;

        boolean horizontal = "left".equals(direction) || "right".equals(direction);
        int span = horizontal ? width : height;
        if (span < MIN_SPAN_PX) {
            throw ctx.fail("区域内滑动",
                    "区域" + (horizontal ? "宽" : "高") + "只有 " + span + "px，滑不动。"
                            + "多半是选错了元素——要选整条容器，不是里面的某个图标"
                            + "（区域: [" + left + "," + top + "][" + right + "," + bottom + "]）",
                    selector, "滑动区域太" + (horizontal ? "窄" : "矮"));
        }

        // 元素吃不吃得下横滑，滑之前就该知道。
        //
        // Android 的规则：可滑动的子控件<b>只有在它自己确实能滚的时候</b>才会调
        // requestDisallowInterceptTouchEvent 拦住父容器。滚不动，事件就归父 ViewPager——
        // 于是「往后滑」翻到了下一个页签，「往回滑」因为没有上一页而毫无反应。
        // 两种现象看着完全不同，其实是同一个原因，光看现象很难想到这一层。
        String scrollable = safeAttr(ctx, selector, "scrollable");
        log.info("[区域内滑动] element={} bounds=[{},{}][{},{}] {}×{} scrollable={} direction={}",
                ctx.getElementKey(), left, top, right, bottom, width, height, scrollable, direction);
        if (ctx.boolParam("requireScrollable", true) && "false".equalsIgnoreCase(scrollable)) {
            throw ctx.fail("区域内滑动",
                    "元素 " + ctx.getElementKey() + " 的 scrollable=false，它吃不下横向滑动。"
                            + "手势会穿透给父容器——外层是页签 ViewPager 的话，结果就是切到了别的页签，"
                            + "而不是滚动这一条。"
                            + "用 scripts/find_scroll_container.py --text <这一条上的某个文字> "
                            + "找出真正可滑的那一层再绑。"
                            + "（当前区域 [" + left + "," + top + "][" + right + "," + bottom + "]，"
                            + width + "×" + height + "）",
                    selector, "元素不可滚动: " + ctx.getElementKey());
        }

        // 绑到整屏容器时告警。这是这个关键字最容易踩的坑：
        // 首页那种「外层游戏页签 ViewPager + 中间金刚位 + 下面内容页签」三层横滑套在一起，
        // 选错一层就等于整屏滑，结果是切到了下一个游戏页签而不是滚动金刚位。
        // 不失败只告警——确实有「就是要在一个占满屏的区域里滑」的正当场景
        warnIfWholeScreen(ctx, left, top, right, bottom, width, height);

        int margin = span * marginPercent / 100;
        // 横滑锁 Y、纵滑锁 X：偏一点就滑到隔壁控件上，而现象是「滑了但没反应」
        int centerX = left + width / 2;
        int centerY = top + height / 2;
        int x1;
        int y1;
        int x2;
        int y2;
        switch (direction) {
            case "right" -> {
                x1 = left + margin;
                x2 = right - margin;
                y1 = centerY;
                y2 = centerY;
            }
            case "up" -> {
                x1 = centerX;
                x2 = centerX;
                y1 = bottom - margin;
                y2 = top + margin;
            }
            case "down" -> {
                x1 = centerX;
                x2 = centerX;
                y1 = top + margin;
                y2 = bottom - margin;
            }
            // left：内容往左走，手指从右往左
            default -> {
                x1 = right - margin;
                x2 = left + margin;
                y1 = centerY;
                y2 = centerY;
            }
        }

        // 「滑了但没动」是这个关键字最容易掉进去的坑，而它<b>不会报错</b>：
        // Appium 的手势发出去了就算成功，至于列表有没有滚，它不关心。
        // 于是日志一片绿，人只能靠肉眼比对截图才发现什么都没发生。
        boolean verifyChanged = ctx.boolParam("verifyChanged", true);
        Region region = new Region(left, top, right, bottom);
        Gesture gesture = new Gesture(x1, y1, x2, y2, duration);

        if (STOP_UNTIL.equalsIgnoreCase(ctx.str("stopMode"))) {
            swipeUntilVisible(ctx, selector, direction, intervalMs, verifyChanged, region, gesture);
            return;
        }
        swipeFixedTimes(ctx, selector, direction, times, intervalMs, verifyChanged, region, gesture);
    }

    /** 固定次数：滑完就走，只在开始前和结束后各读一次区域文案，判断到底动没动 */
    private void swipeFixedTimes(KeywordContext ctx, String selector, String direction,
                                 int times, int intervalMs, boolean verifyChanged,
                                 Region region, Gesture gesture) throws Exception {
        String before = verifyChanged ? regionSignature(ctx, region) : null;

        for (int i = 1; i <= times; i++) {
            ctx.ensureNotCancelled();
            doSwipe(ctx, selector, direction, gesture, i);
            if (i < times && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
        String detail = ctx.getElementKey() + " 内 " + direction + " ×" + times + coords(gesture);

        if (before != null) {
            String after = regionSignature(ctx, region);
            if (after != null && before.equals(after)) {
                ctx.warn("区域内滑动",
                        detail + " —— 但这块区域的内容一个字没变，等于白滑。" + stuckReasons(region),
                        selector);
                return;
            }
        }
        ctx.ok("区域内滑动", detail);
    }

    /**
     * 一直滑到目标元素露出来为止。
     *
     * <p>这才是「怎么确认滑动成功了」的正确问法。「滑 3 次，然后断言元素在不在」看着等价，
     * 其实同时踩两个坑：3 次不够就红了，而这不是缺陷，是次数猜少了；3 次多了又会滑过头，
     * 目标被推出屏幕，同样红。次数本来就不该由人来猜——页面上有几个图标、屏幕多宽、
     * 上次滑到哪，都不是写用例的人能预判的。
     * 交给程序去滑到为止，这一步<b>本身就是断言</b>：出现了才算成功，不用再补一条。
     *
     * <p>两处细节决定它准不准：
     * <ul>
     *   <li><b>「出现」必须限定在屏幕内</b>。横滑条会把屏幕外的图标原样留在控件树里，
     *       只问「存在吗」的话第一次判断就是 true，一次都不会滑，然后报成功。</li>
     *   <li><b>滑到头要立刻停</b>。到底之后再滑，区域内容不会再变化。不停的话要白跑完
     *       maxAttempts 轮，最后只给一句「没找到」，而真正的原因——「已经到头了，后面没有这个东西」
     *       ——反倒没人说出来。</li>
     * </ul>
     */
    private void swipeUntilVisible(KeywordContext ctx, String selector, String direction,
                                   int intervalMs, boolean detectStuck,
                                   Region region, Gesture gesture) throws Exception {
        String targetKey = ctx.str("untilElementKey");
        if (targetKey == null || targetKey.isBlank()) {
            throw ctx.fail("区域内滑动",
                    "停止条件选了「滑到元素出现为止」，却没指定是哪个元素——"
                            + "这样它只会一次都不滑就结束。要么选上目标元素，要么把停止条件改回「固定次数」",
                    selector, "区域内滑动缺少目标元素");
        }
        int maxAttempts = (int) Math.min(50, Math.max(1, ctx.longParam("maxAttempts", 10)));
        boolean failIfNotFound = ctx.boolParam("failIfNotFound", true);

        // 目标元素的定位先解析出来：定位没维护好要现在就报，
        // 而不是滑满 10 次之后说「没出现」——那会把配置问题伪装成产品问题
        List<String> targetSelectors = ctx.getRuntime().requireSelectors(
                ctx.getPlatform(), ctx.getUiAppId(), targetKey, ctx.getExecParams(), ctx.getLogs(),
                ctx.sn("区域内滑动·目标元素"));
        int[] screen = ScreenVisibility.screenSize(ctx);

        if (ScreenVisibility.anyOnScreen(ctx, targetSelectors, screen)) {
            ctx.ok("区域内滑动", targetKey + " 已经在屏上，无需滑动");
            return;
        }

        String prev = detectStuck ? regionSignature(ctx, region) : null;
        for (int i = 1; i <= maxAttempts; i++) {
            ctx.ensureNotCancelled();
            doSwipe(ctx, selector, direction, gesture, i);
            if (intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
            if (ScreenVisibility.anyOnScreen(ctx, targetSelectors, screen)) {
                ctx.ok("区域内滑动",
                        "滑 " + i + " 次后 " + targetKey + " 出现在屏幕内（"
                                + ctx.getElementKey() + " 内 " + direction + coords(gesture) + "）");
                return;
            }
            if (prev != null) {
                String now = regionSignature(ctx, region);
                if (now != null && prev.equals(now)) {
                    endStuck(ctx, selector, targetKey, i, failIfNotFound, region);
                    return;
                }
                prev = now;
            }
        }

        String detail = "在 " + ctx.getElementKey() + " 内 " + direction + " 滑了 " + maxAttempts
                + " 次，" + targetKey + " 始终没出现在屏幕内" + coords(gesture)
                + "。每次滑动内容都在变，说明手势是生效的——"
                + "要么这一条里根本没有这个元素（先确认它是不是在别的地方），"
                + "要么次数不够，把「最多滑几次」调大再看";
        if (!failIfNotFound) {
            ctx.warn("区域内滑动", detail + "（已设为不中断）", selector);
            return;
        }
        throw ctx.fail("区域内滑动", detail, selector, "区域内滑动未找到: " + targetKey);
    }

    /** 滑到头了：区域内容不再变化，再滑多少次都是一样的结果，没必要耗满次数 */
    private void endStuck(KeywordContext ctx, String selector, String targetKey,
                          int attempts, boolean failIfNotFound, Region region) throws Exception {
        String detail = "滑到第 " + attempts + " 次时这块区域的内容不再变化——已经到头了，"
                + "而 " + targetKey + " 没有出现，再滑也不会变。" + stuckReasons(region);
        if (!failIfNotFound) {
            ctx.warn("区域内滑动", detail + "（已设为不中断）", selector);
            return;
        }
        throw ctx.fail("区域内滑动", detail, selector, "区域内滑到头仍未找到: " + targetKey);
    }

    private void doSwipe(KeywordContext ctx, String selector, String direction,
                         Gesture gesture, int index) throws Exception {
        if (!ctx.getDriver().swipe(ctx.getDeviceId(), gesture.x1(), gesture.y1(),
                gesture.x2(), gesture.y2(), gesture.durationMs())) {
            throw ctx.fail("区域内滑动",
                    "滑动失败: " + direction + " #" + index + coords(gesture),
                    selector, "区域内滑动失败: " + ctx.getElementKey());
        }
    }

    /**
     * 「滑了但内容没变」的三种成因，按实际踩到的频率排。
     * <p>
     * 排序本身就是信息：不排的话人会从最后一条（绑错元素）开始查，那是最费时间的一条，
     * 而实际上最常见的是第一条——方向填反了，往一个已经到底的方向滑。
     */
    private static String stuckReasons(Region region) {
        return "三种可能，按概率排："
                + "① 方向反了：列表已经在这一端到底了（金刚位这类默认停在最左，"
                + "要往后看得用 left，用 right 是往回滑，本来就无路可走）；"
                + "② 这一条根本不滚动：内容没超过一屏，比如正好 5 个图标铺满；"
                + "③ 绑错了元素，滑的不是这一条。"
                + "区域: [" + region.left() + "," + region.top() + "]["
                + region.right() + "," + region.bottom() + "]";
    }

    private static String coords(Gesture gesture) {
        return "（" + gesture.x1() + "," + gesture.y1() + " → "
                + gesture.x2() + "," + gesture.y2() + "）";
    }

    private static String regionSignature(KeywordContext ctx, Region region) {
        return regionSignature(ctx, region.left(), region.top(), region.right(), region.bottom());
    }

    /**
     * 这块区域里现在有哪些文案，按控件树顺序拼起来。
     * <p>
     * 用它前后对比来判断「到底滑动了没有」。只取中心点落在区域内的节点——
     * 用相交判断会把横跨区域边界的父容器也算进来，那东西滑不滑都在，对比出来永远相同。
     * <p>
     * 拿不到就返回 null，调用方跳过校验：<b>校验不了不该让滑动失败</b>。
     */
    private static String regionSignature(KeywordContext ctx, int left, int top, int right, int bottom) {
        try {
            String xml = ctx.getDriver().getPageSource(ctx.getDeviceId());
            if (xml == null || xml.isBlank()) {
                return null;
            }
            UiHierarchyBuilder.HierarchyNode root =
                    UiHierarchyBuilder.build(UiHierarchyBuilder.parseDocument(xml), 1.0, 1.0);
            StringBuilder sb = new StringBuilder();
            collectRegionTexts(root, left, top, right, bottom, sb);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectRegionTexts(UiHierarchyBuilder.HierarchyNode node,
                                           int left, int top, int right, int bottom,
                                           StringBuilder sb) {
        if (node == null) {
            return;
        }
        java.util.Map<String, Integer> b = node.getBounds();
        if (b != null && b.get("left") != null) {
            int cx = (b.get("left") + b.get("right")) / 2;
            int cy = (b.get("top") + b.get("bottom")) / 2;
            if (cx >= left && cx < right && cy >= top && cy < bottom) {
                String txt = node.getText();
                if (txt == null || txt.isBlank()) {
                    txt = node.getContentDesc();
                }
                if (txt != null && !txt.isBlank()) {
                    sb.append(txt.trim()).append('|');
                }
            }
        }
        for (UiHierarchyBuilder.HierarchyNode c : node.getChildren()) {
            collectRegionTexts(c, left, top, right, bottom, sb);
        }
    }

    /**
     * 区域几乎等于整屏时提醒一句。
     * <p>
     * 「滑了但滑错了对象」不会报错，只会安静地做错事——它比直接失败难查得多，
     * 因为日志上一片绿，你只能靠肉眼看截图才发现页面跳到别处去了。
     */
    private static void warnIfWholeScreen(KeywordContext ctx, int left, int top, int right, int bottom,
                                          int width, int height) {
        try {
            var appium = ctx.getRuntime().appiumDriverAdapter();
            if (appium == null) {
                return;
            }
            int[] screen = appium.getWindowSize(ctx.getDeviceId());
            if (screen == null || screen.length < 2 || screen[0] <= 0 || screen[1] <= 0) {
                return;
            }
            boolean fullWidth = width >= screen[0] * 0.9;
            boolean fullHeight = height >= screen[1] * 0.9;
            if (fullWidth && fullHeight) {
                ctx.warn("区域内滑动",
                        "这个区域是 " + width + "×" + height + "，几乎就是整屏（" + screen[0] + "×" + screen[1]
                                + "）——和直接用「滑动」没区别。"
                                + "如果本意是滑某一条（比如首页金刚位），多半绑到了外层页面容器，"
                                + "要绑那一条自己的可横滑容器（控件树里 scrollable=true、高度只有一行图标那么高的那个节点）。"
                                + "区域: [" + left + "," + top + "][" + right + "," + bottom + "]",
                        null);
            }
        } catch (Exception e) {
            // 只是提个醒，取不到屏幕尺寸不该让滑动跑不了
            ctx.getRuntime();
        }
    }

    /** 取属性，取不到就返回 null——属性读不到不该让滑动跑不了 */
    private static String safeAttr(KeywordContext ctx, String selector, String name) {
        try {
            return ctx.getDriver().getElementAttribute(ctx.getDeviceId(), selector, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return SwipeHandler.DIRECTIONS.contains(v) ? v : "left";
    }

    /** 滑动区域，绝对屏幕坐标 */
    private record Region(int left, int top, int right, int bottom) {
    }

    /** 一次滑动的起点、终点与时长 */
    private record Gesture(int x1, int y1, int x2, int y2, int durationMs) {
    }
}
