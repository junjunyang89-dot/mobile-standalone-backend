package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.AppiumDriverAdapter;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 清理已知弹窗：把配好的弹窗列表扫一遍，出现哪个关哪个，直到屏幕干净。
 * <p>
 * 解决的是分支指数爆炸：用嵌套 IF 表达「4 个可选弹窗任意组合」需要 2^4 条路径，
 * 而且每条业务流程都要重抄一遍。这里改成「N 次独立检查 + 循环」——
 * 新增一种弹窗只改一处，所有调用方自动受益。
 * <p>
 * 循环而非单趟，是因为弹窗会串：关掉隐私协议后可能才弹出升级提示。
 * 单趟扫描依赖弹窗顺序恰好和配置顺序一致，循环则不依赖。
 * <p>
 * 没有弹窗是正常情况，不算失败——这是它和「等待元素」的根本区别。
 * <p>
 * 但「现在没有」不等于「不会有」：应用刚启动时弹窗往往还没渲染出来，这时扫一遍必然扑空，
 * 紧接着弹窗才弹出来挡住后续步骤。所以强烈建议配上锚点元素（{@code untilElementKey}），
 * 一直清到应用真的到达已知状态为止；二次启动时锚点一开始就在屏上，几乎零开销。
 */
@Component
public class DismissPopupsHandler implements KeywordHandler {

    @Override
    public String code() {
        return "DISMISS_POPUPS";
    }

    @Override
    public String nameZh() {
        return "清理已知弹窗";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "按配置的弹窗列表逐个探测，出现就关掉。建议填「清到出现此元素为止」（如首页标志），"
                + "这样不会因为弹窗还没渲染出来就误判「没有弹窗」。没有弹窗属于正常情况不算失败。"
                + "建议放进一个组件流程，各业务流程开头调用一次";
    }

    @Override
    public int sortOrder() {
        return 53;
    }

    @Override
    public boolean customUi() {
        return true;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "清弹窗";
    }

    /** 不重试：本身就在循环重试，且点击弹窗有副作用 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.steps("popups", "弹窗列表")
                        .withHelp("每项是一个「关闭它的元素」，如同意按钮、我知道了、关闭 X"),
                ParamDef.element("untilElementKey", "清到出现此元素为止").optional()
                        .withHelp("强烈建议填：如首页标志元素。填了就一直清到它出现，"
                                + "不会因为弹窗还没渲染出来就误判「没有弹窗」。留空则只扫一遍就走"),
                ParamDef.number("untilTimeoutSec", "等待该元素的超时秒", 20),
                ParamDef.number("pollIntervalMs", "轮询间隔毫秒", 300),
                ParamDef.number("maxRounds", "最多循环轮数", 3)
                        .withHelp("仅在不填锚点元素时生效：弹窗会串，循环到某一轮没关掉任何东西就提前结束"),
                ParamDef.bool("failIfStillPresent", "轮数用尽仍有弹窗时判失败", false)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<Popup> popups = parsePopups(ctx);
        if (popups.isEmpty()) {
            throw ctx.fail("清理已知弹窗", "未配置任何弹窗", null);
        }
        String anchorKey = ctx.str("untilElementKey");
        if (anchorKey != null && !anchorKey.isBlank()) {
            dismissUntilAnchor(ctx, popups, anchorKey.trim());
            return;
        }
        sweepOnce(ctx, popups);
    }

    /**
     * 清到锚点元素出现为止。
     * <p>
     * 解决「扫得太早」：应用刚启动、弹窗还没渲染出来时扫一遍必然什么都找不到，
     * 于是判定「没有弹窗」继续往下走，紧接着弹窗才弹出来把后续步骤全挡掉。
     * 有了锚点就不靠猜——一直清到应用真的到达已知状态（如首页）为止。
     * <p>
     * 二次启动也不慢：先查锚点，首页已经在屏上就直接返回，一个弹窗都不用探。
     */
    private void dismissUntilAnchor(KeywordContext ctx, List<Popup> popups, String anchorKey) throws Exception {
        List<String> anchorSelectors = ctx.getRuntime().requireSelectors(
                ctx.getPlatform(), ctx.getUiAppId(), anchorKey, ctx.getExecParams(), ctx.getLogs(),
                ctx.sn("清理已知弹窗·锚点"));

        long timeoutMs = Math.max(1000L, ctx.longParam("untilTimeoutSec", 20) * 1000L);
        long pollMs = Math.max(100L, ctx.longParam("pollIntervalMs", 300));
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        Set<String> dismissed = new LinkedHashSet<>();

        ctx.ok("清理已知弹窗", "清到「" + anchorKey + "」出现为止，最多 " + (timeoutMs / 1000) + " 秒");

        while (System.currentTimeMillis() < deadline) {
            ctx.ensureNotCancelled();
            // 先看锚点：已经到达目标状态就没必要再探弹窗，二次启动几乎零开销
            if (anyPresent(ctx, anchorSelectors, anchorKey)) {
                long elapsed = System.currentTimeMillis() - startedAt;
                ctx.ok("清理已知弹窗", dismissed.isEmpty()
                        ? "无弹窗，" + elapsed + "ms 后已到达「" + anchorKey + "」"
                        : "已关闭 " + dismissed.size() + " 个：" + String.join("、", dismissed)
                                + "，" + elapsed + "ms 后到达「" + anchorKey + "」");
                return;
            }
            for (Popup popup : popups) {
                if (isPresent(ctx, popup) && dismiss(ctx, popup)) {
                    dismissed.add(popup.label());
                }
            }
            // 控件树/找元素已挂：继续轮询只会空耗几分钟超时，直接失败并提示换机
            String deadHint = uiAutomationDeadHint(ctx);
            if (deadHint != null) {
                throw ctx.fail("清理已知弹窗", deadHint, anchorKey,
                        "设备与自动化引擎不匹配，请换机后重跑");
            }
            ctx.sleep(pollMs);
        }

        // 「未发现任何已知弹窗」这句话会把人引到弹窗配置上去查，但真实原因常常是
        // 驱动压根看不到屏幕（控件树取不到）——那种情况下所有探测都返回 false，
        // 看起来跟「屏幕上确实没弹窗」一模一样。实测被这句话误导过一次，必须区分开。
        String blind = screenUnavailableReason(ctx);
        String detail;
        if (blind != null) {
            detail = deviceMismatchDetail(ctx, blind, startedAt, anchorKey, dismissed);
        } else {
            detail = "等待 " + (System.currentTimeMillis() - startedAt) + "ms 仍未出现「" + anchorKey + "」"
                    + (dismissed.isEmpty() ? "，期间未发现任何已知弹窗" : "，期间已关闭：" + String.join("、", dismissed));
        }
        throw ctx.fail("清理已知弹窗", detail, anchorKey, "清理弹窗后仍未到达「" + anchorKey + "」");
    }

    /** 不配锚点时的老行为：扫一遍（含串弹窗循环）就走，成本低但不能应对「弹窗还没弹出来」 */
    private void sweepOnce(KeywordContext ctx, List<Popup> popups) throws Exception {
        int maxRounds = (int) Math.min(10, Math.max(1, ctx.longParam("maxRounds", 3)));

        Set<String> dismissed = new LinkedHashSet<>();
        int rounds = 0;
        boolean anyThisRound;

        do {
            ctx.ensureNotCancelled();
            rounds++;
            anyThisRound = false;
            for (Popup popup : popups) {
                if (!isPresent(ctx, popup)) {
                    continue;
                }
                if (dismiss(ctx, popup)) {
                    dismissed.add(popup.label());
                    anyThisRound = true;
                }
            }
            // 这一轮什么都没关掉，说明屏幕已经干净（或剩下的关不掉），不必再转
        } while (anyThisRound && rounds < maxRounds);

        if (dismissed.isEmpty()) {
            ctx.ok("清理已知弹窗", "无已知弹窗需要处理（探测 " + popups.size() + " 项）");
            return;
        }

        List<String> remaining = stillPresent(ctx, popups);
        if (remaining.isEmpty()) {
            ctx.ok("清理已知弹窗",
                    "已关闭 " + dismissed.size() + " 个：" + String.join("、", dismissed)
                            + "（用了 " + rounds + " 轮）");
            return;
        }

        String detail = "已关闭：" + String.join("、", dismissed)
                + "；" + rounds + " 轮后仍存在：" + String.join("、", remaining);
        if (ctx.boolParam("failIfStillPresent", false)) {
            throw ctx.fail("清理已知弹窗", detail, null, "弹窗未能全部关闭: " + String.join("、", remaining));
        }
        ctx.warn("清理已知弹窗", detail + "（已按配置继续执行）", null);
    }

    /**
     * 探一下驱动还能不能看到屏幕。
     *
     * @return null 表示能看到；非 null 是拿不到控件树的原因
     */
    private String screenUnavailableReason(KeywordContext ctx) {
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium != null) {
            String last = appium.lastPageSourceFailure();
            if (last != null && !last.isBlank()) {
                return last;
            }
        }
        try {
            String src = ctx.getDriver().getPageSource(ctx.getDeviceId());
            if (src != null && !src.isBlank()) {
                return null;
            }
            if (appium != null) {
                String last = appium.lastPageSourceFailure();
                if (last != null && !last.isBlank()) {
                    return last;
                }
            }
            return "驱动返回空控件树";
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int nl = msg.indexOf('\n');
            return nl > 0 ? msg.substring(0, nl) : msg;
        }
    }

    /** 已确认 UiAutomator 挂掉时的换机提示；未挂则返回 null */
    private String uiAutomationDeadHint(KeywordContext ctx) {
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        if (appium == null || !appium.isUiAutomationLikelyDead()) {
            return null;
        }
        return appium.deviceMismatchHint(ctx.getDeviceId(), appium.lastPageSourceFailure());
    }

    private String deviceMismatchDetail(KeywordContext ctx, String blind,
                                        long startedAt, String anchorKey, Set<String> dismissed) {
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        String tech = "等待 " + (System.currentTimeMillis() - startedAt) + "ms 仍未出现「" + anchorKey + "」"
                + (dismissed.isEmpty() ? "，期间未发现任何已知弹窗" : "，期间已关闭：" + String.join("、", dismissed))
                + "；控件树不可用（" + blind + "）";
        if (appium != null && AppiumDriverAdapter.looksLikeUiAutomationDead(blind)) {
            return appium.deviceMismatchHint(ctx.getDeviceId(), tech);
        }
        return tech + "。⚠ 「没发现弹窗」很可能只是看不见屏幕，而不是屏幕上真的没有"
                + "——请先确认应用已经起来、Appium 会话正常；若反复复现请换机";
    }

    private boolean anyPresent(KeywordContext ctx, List<String> selectors, String label) {
        for (String selector : selectors) {
            try {
                if (ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                    return true;
                }
            } catch (Exception e) {
                ctx.warn("锚点探测异常", label + ": " + e.getMessage(), selector);
            }
        }
        return false;
    }

    private List<String> stillPresent(KeywordContext ctx, List<Popup> popups) {
        List<String> remaining = new ArrayList<>();
        for (Popup popup : popups) {
            if (isPresent(ctx, popup)) {
                remaining.add(popup.label());
            }
        }
        return remaining;
    }

    private boolean isPresent(KeywordContext ctx, Popup popup) {
        for (String selector : popup.selectors()) {
            try {
                if (ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                    return true;
                }
            } catch (Exception e) {
                // 探测失败不代表弹窗不存在，但也不该中断清理；记一笔继续
                ctx.warn("弹窗探测异常", popup.label() + ": " + e.getMessage(), selector);
            }
        }
        return false;
    }

    /** 尽力关闭：多个定位依次试，失败只记录不中断——清理弹窗是尽力而为，不该拖垮主流程 */
    private boolean dismiss(KeywordContext ctx, Popup popup) {
        AppiumDriverAdapter appium = ctx.getRuntime().appiumDriverAdapter();
        boolean hdc = ctx.isHarmony();
        for (String selector : popup.selectors()) {
            try {
                boolean ok;
                if (hdc) {
                    ok = ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
                } else {
                    ok = appium != null
                            ? appium.clickElementPreferCoordinates(
                                    ctx.getDeviceId(), selector, "AUTO", popup.elementKey())
                            : ctx.getDriver().clickElement(ctx.getDeviceId(), selector);
                }
                if (ok) {
                    ctx.ok("关闭弹窗", popup.label() + "（" + popup.elementKey() + "）");
                    return true;
                }
            } catch (Exception e) {
                ctx.warn("关闭弹窗失败", popup.label() + ": " + e.getMessage(), selector);
            }
        }
        ctx.warn("关闭弹窗失败", popup.label() + " 在屏上但点不掉", popup.elementKey());
        return false;
    }

    private List<Popup> parsePopups(KeywordContext ctx) throws Exception {
        List<Map<String, Object>> raw = ctx.getRuntime().asStepList(ctx.rawParam("popups"));
        List<Popup> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> node = raw.get(i);
            String elementKey = KeywordContext.asString(node.get("elementKey"));
            if (elementKey == null || elementKey.isBlank()) {
                throw ctx.fail("清理已知弹窗", "第 " + (i + 1) + " 项弹窗缺少元素", null);
            }
            String label = KeywordContext.firstNonBlank(
                    KeywordContext.asString(node.get("label")), elementKey);
            List<String> selectors = ctx.getRuntime().requireSelectors(
                    ctx.getPlatform(), ctx.getUiAppId(), elementKey, ctx.getExecParams(), ctx.getLogs(),
                    ctx.sn("清理已知弹窗·" + label));
            out.add(new Popup(elementKey, label, selectors));
        }
        return out;
    }

    private record Popup(String elementKey, String label, List<String> selectors) {
    }
}
