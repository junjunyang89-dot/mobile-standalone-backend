package com.mobile.standalone.service;

import com.mobile.standalone.dto.FailureDiagnosisDtos.Artifact;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Category;
import com.mobile.standalone.dto.FailureDiagnosisDtos.Context;
import com.mobile.standalone.dto.FailureDiagnosisDtos.ElementRef;
import com.mobile.standalone.dto.FailureDiagnosisDtos.LogLine;
import com.mobile.standalone.dto.FailureDiagnosisDtos.PageSource;

import java.util.List;

/**
 * 把归因上下文渲染成可整块粘贴的文本。
 * <p>
 * 当前用途是人工贴进 Cursor 之类的工具里判一判，但它不是临时方案——
 * 将来直连模型 API 时发的就是这份内容。手工阶段调顺的措辞，接 API 时能原样带走。
 * <p>
 * 有两处是刻意这么写的：
 * <ul>
 *   <li>证据缺口单独成节且放在证据之前——先说清楚缺什么，再看剩下的证据，
 *       否则读的人（无论是模型还是同事）会默认手上这份是完整的。</li>
 *   <li>结尾允许回答「六类都不合适」。分类是我拍的，真实失败长什么样还没验证过，
 *       只给六选一的话分错了也看不出来。</li>
 * </ul>
 */
public final class FailureDiagnosisPromptRenderer {

    private FailureDiagnosisPromptRenderer() {
    }

    /** 给人看：截图列本地路径，自己打开或拖进对话框 */
    public static String render(Context ctx) {
        return render(ctx, true);
    }

    /**
     * @param imagesAvailable 判断方能不能真的看到截图。
     *                        为 false 时必须说清楚——否则模型会照着「请看截图」的措辞
     *                        脑补出一段它根本没看过的画面描述，而那种幻觉在归因里最难识别。
     */
    public static String render(Context ctx, boolean imagesAvailable) {
        StringBuilder sb = new StringBuilder();

        sb.append("下面是一次 APP UI 自动化测试失败的现场证据，请判断这次失败该找谁修。\n");
        sb.append("完整要求在最后一节，先看证据。\n\n");

        appendTask(sb, ctx);
        appendGaps(sb, ctx);
        appendFailedStep(sb, ctx);
        appendLogs(sb, ctx);
        appendElements(sb, ctx);
        appendPageSource(sb, ctx);
        appendScreenshots(sb, ctx, imagesAvailable);
        appendQuestion(sb, ctx);

        return sb.toString();
    }

    private static void appendTask(StringBuilder sb, Context ctx) {
        sb.append("## 任务\n\n");
        kv(sb, "任务", join(ctx.getTaskName(), ctx.getTaskId()));
        kv(sb, "流程", join(ctx.getFlowName(), ctx.getFlowId()));
        kv(sb, "平台 / 设备", join(ctx.getPlatform(), ctx.getDeviceId()));
        // 测试环境的资讯、赛事等数据跟生产完全不同，钉着具体文案的定位在这边本来就会失效——
        // 不说明环境，很容易把「数据不同」误判成「功能坏了」
        kv(sb, "执行环境", ctx.getEnvironment());
        kv(sb, "任务状态", ctx.getTaskStatus());
        kv(sb, "失败原因（落库的简短文案）", ctx.getErrorMessage());
        sb.append('\n');
    }

    /**
     * 缺口放在证据之前。看到「有截图有控件树」和看到「截图已被清理」，
     * 对同一份剩余证据的解读是不一样的。
     */
    private static void appendGaps(StringBuilder sb, Context ctx) {
        if (ctx.getGaps().isEmpty()) {
            return;
        }
        sb.append("## ⚠ 证据缺口（这次拿不到的东西）\n\n");
        for (String gap : ctx.getGaps()) {
            sb.append("- ").append(gap).append('\n');
        }
        sb.append("\n以上缺失项不要当成「不存在」来推理。\n\n");
    }

    private static void appendFailedStep(StringBuilder sb, Context ctx) {
        sb.append("## 失败步骤\n\n");
        LogLine f = ctx.getFailedStep();
        if (f == null) {
            sb.append("本次执行日志里没有失败步骤。\n\n");
            return;
        }
        kv(sb, "步骤", f.getStep());
        kv(sb, "时间", f.getTimestamp());
        kv(sb, "报错", f.getMessage());
        kv(sb, "定位串", f.getSelector());
        kv(sb, "元素 key", f.getElementKey());
        sb.append('\n');
    }

    private static void appendLogs(StringBuilder sb, Context ctx) {
        if (ctx.getLogWindow().isEmpty()) {
            return;
        }
        sb.append("## 执行日志（失败点前后）\n\n```\n");
        for (LogLine l : ctx.getLogWindow()) {
            sb.append(mark(l.getStatus())).append(' ')
                    .append(nz(l.getStep()));
            if (l.getMessage() != null && !l.getMessage().isBlank()) {
                sb.append("  ").append(oneLine(l.getMessage()));
            }
            sb.append('\n');
        }
        sb.append("```\n\n");
        sb.append("说明：`x` 是失败，`!` 是中立事件（重试过程中的失败、用户点停止，都不算不通过），`.` 是通过。\n\n");
    }

    private static void appendElements(StringBuilder sb, Context ctx) {
        if (ctx.getElements().isEmpty()) {
            return;
        }
        sb.append("## 失败步骤引用的元素\n\n");
        for (ElementRef e : ctx.getElements()) {
            sb.append("### ").append(nz(e.getNameEn()));
            if (e.getNameZh() != null) {
                sb.append("（").append(e.getNameZh()).append("）");
            }
            sb.append('\n');
            if (e.getMissingReason() != null) {
                sb.append("- **元素表里查不到**：").append(e.getMissingReason()).append('\n');
                sb.append("- 元素被停用或改名，本身就可能是失败原因。\n\n");
                continue;
            }
            kvItem(sb, "主定位", join2(e.getLocatorType(), e.getLocatorValue()));
            if (!e.getLocatorFallbacks().isEmpty()) {
                kvItem(sb, "备用定位", String.join(" ｜ ", e.getLocatorFallbacks()));
            }
            kvItem(sb, "长什么样", e.getVisualHint());
            kvItem(sb, "所属页面", e.getPageId());
            kvItem(sb, "状态 / 来源", join2(e.getStatus(), e.getSource()));
            kvItem(sb, "上次验证通过", e.getLastVerifiedAt());
            sb.append('\n');
        }
    }

    private static void appendPageSource(StringBuilder sb, Context ctx) {
        PageSource ps = ctx.getPageSource();
        if (ps == null) {
            return;
        }
        sb.append("## 失败瞬间的页面控件树\n\n");
        sb.append("已剔除无文案无 id 的纯布局容器，保留 ")
                .append(ps.getKeptNodes()).append('/').append(ps.getTotalNodes()).append(" 个节点");
        if (ps.isTruncated()) {
            sb.append("，**并且因过大被截断，页面下半部分的控件可能不在其中**");
        }
        sb.append("。\n\n");
        if (!ps.getVisibleTexts().isEmpty()) {
            sb.append("页面上的可见文案：").append(String.join(" / ", ps.getVisibleTexts())).append("\n\n");
        }
        sb.append("```\n").append(ps.getOutline()).append("\n```\n\n");
        sb.append("标记含义：`clickable` 可点击，`disabled` 存在但点不动，")
                .append("`not-displayed` 在控件树里但用户看不见（被遮挡或滚出屏幕）。\n\n");
    }

    private static void appendScreenshots(StringBuilder sb, Context ctx, boolean imagesAvailable) {
        List<Artifact> shots = ctx.getScreenshots();
        if (shots.isEmpty()) {
            return;
        }
        if (!imagesAvailable) {
            // 路径仍然列出来，人工复核时要用；但必须先讲清楚「你看不到这些图」
            sb.append("## 截图（**本次未随判断一并提供，你看不到这些图**）\n\n");
            sb.append("以下只是文件路径，供人工复核时打开。**请仅依据上面的控件树与日志作判断，"
                    + "不要描述或推测截图内容。**\n\n");
            for (Artifact a : shots) {
                sb.append("- `").append(a.getPath()).append('`');
                if (a.getStep() != null) {
                    sb.append("  ← ").append(a.getStep());
                }
                sb.append('\n');
            }
            sb.append('\n');
            return;
        }
        sb.append("## 截图（按时间正序，请把文件拖进来一起看）\n\n");
        for (int i = 0; i < shots.size(); i++) {
            Artifact a = shots.get(i);
            sb.append(i + 1).append(". `").append(a.getPath()).append('`');
            if (a.getStep() != null) {
                sb.append("  ← ").append(a.getStep());
            }
            if (i == shots.size() - 1) {
                sb.append("  **（最后一张）**");
            }
            if (Boolean.TRUE.equals(a.getSettled())) {
                sb.append("  [页面已稳定]");
            }
            sb.append('\n');
        }
        sb.append("\n没标「页面已稳定」的可能拍在加载中或转场动画里，判读时别当成最终态。\n");
        // 早先这里写的是「失败瞬间」，实测跟控件树能差三十秒——步骤截图拍在动作开始前，
        // 控件树存在最终失败之后，中间隔着重试和逐个尝试备用定位
        sb.append("注意：截图拍在动作**开始前**，控件树是最终失败**之后**存的，两者不是同一时刻。"
                + "以控件树为准判断失败当时的页面状态。\n\n");
    }

    private static void appendQuestion(StringBuilder sb, Context ctx) {
        sb.append("---\n\n## 请回答\n\n");
        sb.append("从下面六类里选一类，并说明依据：\n\n");
        sb.append("| 分类 | 含义 | 对应动作 |\n|---|---|---|\n");
        for (Category c : Category.values()) {
            sb.append("| `").append(c.name()).append("` | ").append(c.label())
                    .append(" | ").append(c.action()).append(" |\n");
        }
        sb.append('\n');

        sb.append("判断时请注意：\n\n");
        // 这两条是最容易判错的地方，值得单独点出来
        sb.append("1. 控件树里**有**对应控件、但元素的定位串对不上 → 多半是 `ELEMENT_BROKEN`；"
                + "控件树里**压根没有** → 更可能是没走到这个页面（`FLOW_OUTDATED`）或功能本身坏了。\n");
        sb.append("2. 元素带 `not-displayed` 说明它在树里但用户看不见（被弹窗盖住 / 滚出屏幕），"
                + "这跟「没渲染」是两回事，修法也不同。\n");
        if (!ctx.getGaps().isEmpty()) {
            sb.append("3. 上面列了证据缺口。**证据不足以支撑结论时请直接答 `UNKNOWN`**，"
                    + "不要用剩下的证据凑一个像模像样的判断——归因错了比没有归因更麻烦。\n");
        } else {
            sb.append("3. 证据不足以支撑结论时请直接答 `UNKNOWN`，不要硬凑。\n");
        }
        sb.append("4. **如果这六类都不合适，请直接说不合适、并描述你认为的真实类别。**"
                + " 这套分类是拍脑袋定的，还没拿真实失败验证过，分错了我需要知道。\n\n");

        sb.append("按这个格式回答：\n\n");
        sb.append("```\n");
        sb.append("category:   <六选一，或「都不合适：你的描述」>\n");
        sb.append("confidence: <0-100，你对这个结论的把握>\n");
        sb.append("reason:     <依据。指出你是看哪条日志/哪个控件/哪张图得出的>\n");
        sb.append("suggestion: <具体要改什么。若是 ELEMENT_BROKEN，请给出你认为正确的定位串>\n");
        sb.append("```\n");
    }

    // ==================== 小工具 ====================

    private static void kv(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("- **").append(key).append("**：").append(value).append('\n');
    }

    private static void kvItem(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("- ").append(key).append("：`").append(value).append("`\n");
    }

    /** 失败一眼可见，中立事件也要能跟通过区分开 */
    private static String mark(String status) {
        if ("fail".equalsIgnoreCase(status)) {
            return "x";
        }
        if ("neutral".equalsIgnoreCase(status)) {
            return "!";
        }
        return ".";
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String join(String main, String id) {
        if (main == null || main.isBlank()) {
            return id;
        }
        return id == null || id.isBlank() ? main : main + "（" + id + "）";
    }

    private static String join2(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return b == null || b.isBlank() ? a : a + " = " + b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
