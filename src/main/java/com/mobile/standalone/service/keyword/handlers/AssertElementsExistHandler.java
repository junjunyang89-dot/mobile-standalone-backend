package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenVisibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次判定一批元素在不在屏上。
 *
 * <p><b>为什么不是「写 5 条等待元素」</b>：一条一条写，第 1 条不过就中断了，
 * 你只知道「第 1 个没出现」，剩下 4 个到底在不在没人告诉你。而排查时最想知道的恰恰是
 * 「是全军覆没（页面根本没加载 / 滑错了地方），还是只少了一个（那一个的定位过期了）」——
 * 这两种情况的下一步动作完全不同。所以这里<b>把该看的都看完再报</b>，失败文案里点名到具体元素。
 *
 * <p>三种判定，对应三种真实问题：
 * <ul>
 *   <li><b>都要在（all）</b>：这一屏该有的东西齐不齐。适合「进到某页后校验页面完整性」。</li>
 *   <li><b>有一个就行（any）</b>：AB 实验、分版本文案、随机运营位——内容不固定但「总得有点东西」。
 *       横滑条滑到某一段时也该用它：一条横滑条上同时可见的就那么几个，
 *       用 all 去要求「整批都在」，滑到后面前面的已经出屏了，必然失败。</li>
 *   <li><b>都不在（none）</b>：校验「东西确实消失了」。比如退出登录后个人信息不该还在、
 *       删除后列表项不该还在——这类断言漏写，是「删除功能坏了但用例全绿」的常见成因。</li>
 * </ul>
 *
 * <p><b>{@code visibleOnly} 默认开着，这不是可有可无的开关</b>：横向滑动条（HorizontalScrollView、
 * 部分 RecyclerView）会把屏幕外的子控件也留在控件树里，坐标是负数或超出屏宽。
 * 只判断「控件树里有没有」的话，断言在人眼根本看不到那个图标时照样通过——
 * 用它来验证滑动效果，结果是滑没滑都绿。
 */
@Component
public class AssertElementsExistHandler implements KeywordHandler {

    private static final String MODE_ALL = "all";
    private static final String MODE_ANY = "any";
    private static final String MODE_NONE = "none";
    private static final List<String> MODES = List.of(MODE_ALL, MODE_ANY, MODE_NONE);

    /** 轮询下限，避免空转把设备打满 */
    private static final long MIN_POLL_MS = 200L;

    @Override
    public String code() {
        return "ASSERT_ELEMENTS_EXIST";
    }

    @Override
    public String nameZh() {
        return "批量元素断言";
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "一次判定一批元素在不在屏上（都要在 / 有一个就行 / 都不在）。"
                + "失败时点名到具体是哪几个，不会第一个不过就中断";
    }

    @Override
    public int sortOrder() {
        return 46;
    }

    /** 元素来自 elementKeys 列表，不用步骤自带的那个 */
    @Override
    public boolean needsElement() {
        return false;
    }

    @Override
    public String shortLabel() {
        return "批量断言";
    }

    /**
     * 不参与外层重试：这个关键字自己就带等待窗口（timeoutSec），
     * 再叠一层重试只会在真失败时把耗时翻倍，却拿不到任何新信息——
     * 该等的第一轮已经等过了。
     */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.elements("elementKeys", "要判定的元素")
                        .withHelp("按填写顺序判定并报告；失败文案里会点名到具体哪几个没满足"),
                ParamDef.enumOf("mode", "判定方式", MODES, MODE_ALL)
                        .withHelp("all＝都要在；any＝有一个就行（横滑条、AB 实验用这个）；none＝都不能在"),
                ParamDef.number("timeoutSec", "等待秒", 5)
                        .withHelp("给页面渲染留的时间；一满足立刻继续，不会等满。"
                                + "元素越多单轮探测越慢，这里不建议给很大的值"),
                ParamDef.number("pollIntervalMs", "轮询间隔毫秒", 500),
                ParamDef.bool("visibleOnly", "只认屏幕内可见的", true)
                        .withHelp("横滑条会把屏幕外的图标也留在控件树里。关掉它，"
                                + "人眼看不到的元素也算「存在」，用来验证滑动效果就会滑没滑都通过"),
                ParamDef.bool("failOnMiss", "不满足是否失败", true)
                        .withHelp("关掉就只记一条告警继续跑——用于「顺带看一眼」的巡检点，不想因此中断主流程")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> keys = ctx.strList("elementKeys");
        if (keys.isEmpty()) {
            throw ctx.fail("批量元素断言",
                    "一个元素都没选，这条断言什么也没判。要么选上元素，要么删掉这一步——"
                            + "留一条永远通过的断言比没有断言更糟，它会让人以为这里已经校验过了",
                    null, "批量元素断言未配置元素");
        }
        String mode = normalizeMode(ctx.str("mode"));
        long timeoutMs = Math.max(0L, ctx.longParam("timeoutSec", 5) * 1000L);
        long pollMs = Math.max(MIN_POLL_MS, ctx.longParam("pollIntervalMs", 500));
        boolean visibleOnly = ctx.boolParam("visibleOnly", true);
        boolean failOnMiss = ctx.boolParam("failOnMiss", true);

        // 定位在开跑前一次性解析完：元素没维护好要立刻报错，
        // 而不是白等一个超时之后才说「没出现」——那会把配置问题伪装成产品问题
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            targets.add(new Target(key, ctx.getRuntime().requireSelectors(
                    ctx.getPlatform(), ctx.getUiAppId(), key, ctx.getExecParams(), ctx.getLogs(),
                    ctx.sn("批量元素断言·元素" + (i + 1)))));
        }

        int[] screen = visibleOnly ? ScreenVisibility.screenSize(ctx) : null;
        if (visibleOnly && screen == null) {
            ctx.warn("批量元素断言",
                    "取不到屏幕尺寸，这次只能判断「控件树里有没有」，无法排除屏幕外的元素。"
                            + "横滑条场景下结论会偏松",
                    null);
        }

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        List<String> present = List.of();
        List<String> missing = List.of();

        while (true) {
            ctx.ensureNotCancelled();
            present = new ArrayList<>();
            missing = new ArrayList<>();
            for (Target t : targets) {
                if (isPresent(ctx, t, screen)) {
                    present.add(t.key());
                } else {
                    missing.add(t.key());
                }
            }
            if (satisfied(mode, present, missing)) {
                break;
            }
            if (System.currentTimeMillis() + pollMs >= deadline) {
                break;
            }
            ctx.sleep(pollMs);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        String scope = visibleOnly && screen != null ? "屏幕内可见" : "控件树内存在";

        if (satisfied(mode, present, missing)) {
            ctx.ok("批量元素断言", passDetail(mode, targets.size(), present, elapsed, scope));
            return;
        }

        String detail = failDetail(mode, targets.size(), present, missing, elapsed, scope);
        if (!failOnMiss) {
            ctx.warn("批量元素断言", detail + "（已设为不中断）", null);
            return;
        }
        throw ctx.fail("批量元素断言", detail, null, shortError(mode, present, missing));
    }

    private static boolean satisfied(String mode, List<String> present, List<String> missing) {
        return switch (mode) {
            case MODE_ANY -> !present.isEmpty();
            case MODE_NONE -> present.isEmpty();
            default -> missing.isEmpty();
        };
    }

    private static String passDetail(String mode, int total, List<String> present, long elapsed, String scope) {
        String base = "（" + scope + "，耗时 " + elapsed + "ms）";
        return switch (mode) {
            case MODE_ANY -> "有一个就行：" + total + " 个里出现了 " + present.size()
                    + " 个 —— " + join(present) + base;
            case MODE_NONE -> "都不在：" + total + " 个元素确认都不在屏上" + base;
            default -> "都要在：" + total + " 个元素全部就位 —— " + join(present) + base;
        };
    }

    /**
     * 失败文案的重点是让人不用再复现一遍就知道下一步查什么，
     * 所以「在的」和「不在的」都要列出来——只列缺的那部分，看不出是整页没加载还是个别元素挂了。
     */
    private static String failDetail(String mode, int total, List<String> present, List<String> missing,
                                     long elapsed, String scope) {
        String tail = "（" + scope + "，等了 " + elapsed + "ms）";
        return switch (mode) {
            case MODE_ANY -> "有一个就行，但 " + total + " 个元素一个都没出现：" + join(missing) + tail
                    + "。整批都不在，先怀疑页面没到位或滑错了地方，而不是逐个去查元素定位";
            case MODE_NONE -> "本该都不在，却仍有 " + present.size() + " 个在屏上：" + join(present)
                    + tail + "。多半是「删除 / 退出 / 清空」这类操作没真正生效";
            default -> {
                String head = "都要在，但缺了 " + missing.size() + "/" + total + " 个：" + join(missing) + tail;
                if (present.isEmpty()) {
                    yield head + "。一个都没出现，先查页面是不是根本没到该到的位置";
                }
                yield head + "；已在屏上的：" + join(present)
                        + "。既然大部分都在，页面是对的，问题更可能出在缺的那几个自己身上"
                        + "（定位过期、或它们确实在屏幕外没滑到）";
            }
        };
    }

    /** 落进 error_message 与任务列表的短文案，保持一行能看完 */
    private static String shortError(String mode, List<String> present, List<String> missing) {
        return switch (mode) {
            case MODE_ANY -> "批量元素断言：候选元素均未出现";
            case MODE_NONE -> "批量元素断言：应消失的元素仍在 " + join(present);
            default -> "批量元素断言：缺少元素 " + join(missing);
        };
    }

    private boolean isPresent(KeywordContext ctx, Target target, int[] screen) {
        for (String selector : target.selectors()) {
            try {
                if (!ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                    continue;
                }
                if (screen == null || ScreenVisibility.centerWithin(ctx, selector, screen)) {
                    return true;
                }
            } catch (Exception e) {
                // 探测期间会话可能抖动，一次异常不该被当成「元素不存在」写进失败文案，
                // 但也不能当成存在——记一笔，按这一条定位没命中继续试下一条
                ctx.warn("批量元素断言", target.key() + " 探测异常: " + e.getMessage(), selector);
            }
        }
        return false;
    }

    private static String join(List<String> keys) {
        return keys.isEmpty() ? "（无）" : String.join("、", keys);
    }

    private static String normalizeMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return MODES.contains(v) ? v : MODE_ALL;
    }

    private record Target(String key, List<String> selectors) {
    }
}
