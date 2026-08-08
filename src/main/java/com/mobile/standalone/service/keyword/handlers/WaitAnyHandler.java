package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.FlowOutcome;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 竞速等待：同时盯住多个元素，谁先出现就走谁的分支。
 * <p>
 * 用来替代「靠超时判断走哪条路」的写法。举例：首次启动会弹「温馨提示」，二次启动直接进首页——
 * 用 {@code IF_ELEMENT_EXISTS} 表达的话，是「等温馨提示 3 秒，没等到就当作二次启动」，
 * 于是设备一慢就误判：弹窗第 4 秒才出来，流程已经按二次启动去点侧边栏，被弹窗挡住而失败，
 * 而且快机器上跑又是好的——典型的偶现失败。
 * <p>
 * 本关键字对应的是人的真实做法：盯着屏幕，看哪个先出现。命中即返回，不付固定超时代价；
 * 慢设备上把整体超时放大也不会误判，因为等的是「任意一个」而不是「某一个」。
 */
@Component
public class WaitAnyHandler implements KeywordHandler {

    /** 单次探测的最小间隔，避免空转把设备打满 */
    private static final long MIN_POLL_MS = 100L;

    @Override
    public String code() {
        return "WAIT_ANY";
    }

    @Override
    public String nameZh() {
        return "等待任一元素";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "同时等待多个元素，谁先出现走谁的分支；命中即继续，不必为没出现的那条路径干等超时。"
                + "适合「首启有弹窗、二启直接进首页」这类多入口场景";
    }

    @Override
    public int sortOrder() {
        return 54;
    }

    @Override
    public boolean customUi() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "竞速等待";
    }

    /** 不重试：命中分支的步骤已经执行过，重试会重复执行 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.number("timeoutSec", "整体超时秒", 20)
                        .withHelp("等待所有候选的总时长；命中任意一个即刻继续，不会等满"),
                ParamDef.number("pollIntervalMs", "轮询间隔毫秒", 300),
                ParamDef.steps("candidates", "候选分支")
                        .withHelp("每个候选含元素与命中后要执行的步骤，按填写顺序判定优先级"),
                ParamDef.steps("noneSteps", "都没出现时步骤"),
                ParamDef.string("noneMessage", "都没出现时返回提示"),
                ParamDef.enumOf("endStatus", "都没出现时判定",
                        FlowOutcome.optionNames(), FlowOutcome.FAIL.name())
                        .withHelp("仅在走「返回提示」结束流程时生效")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<Candidate> candidates = parseCandidates(ctx);
        if (candidates.isEmpty()) {
            throw ctx.fail("竞速等待", "WAIT_ANY 至少需要一个候选分支", null);
        }

        long timeoutMs = Math.max(1000L, ctx.longParam("timeoutSec", 20) * 1000L);
        long pollMs = Math.max(MIN_POLL_MS, ctx.longParam("pollIntervalMs", 300));

        ctx.ok("竞速等待", "同时等待 " + candidates.size() + " 个候选（"
                + describeCandidates(candidates) + "），最多 " + (timeoutMs / 1000) + " 秒");

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        Candidate winner = null;

        while (winner == null && System.currentTimeMillis() < deadline) {
            ctx.ensureNotCancelled();
            for (Candidate c : candidates) {
                // 按填写顺序判优先级：同一轮内多个都在屏上时，先写的胜出
                if (isPresent(ctx, c)) {
                    winner = c;
                    break;
                }
            }
            if (winner == null) {
                ctx.sleep(pollMs);
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;

        if (winner != null) {
            ctx.ok("竞速命中", winner.label() + "（" + winner.elementKey() + "）在 " + elapsed + "ms 后出现");
            ctx.runNested(winner.steps());
            return;
        }

        onNoneAppeared(ctx, candidates, elapsed);
    }

    /** 全部候选都没出现：优先跑兜底步骤，其次按判定提前返回，最后才算硬失败 */
    private void onNoneAppeared(KeywordContext ctx, List<Candidate> candidates, long elapsed) throws Exception {
        String detail = "等待 " + elapsed + "ms，候选均未出现（" + describeCandidates(candidates) + "）";

        List<Map<String, Object>> noneSteps = ctx.getRuntime().asStepList(ctx.rawParam("noneSteps"));
        if (noneSteps != null && !noneSteps.isEmpty()) {
            ctx.warn("竞速超时", detail + "，执行兜底步骤", null);
            ctx.runNested(noneSteps);
            return;
        }

        String noneMessage = ctx.substituted("noneMessage");
        if (noneMessage != null && !noneMessage.isBlank()) {
            FlowOutcome outcome = FlowOutcome.from(ctx.str("endStatus"));
            ctx.getState().returnEarly(noneMessage, outcome);
            String msg = noneMessage + "（" + outcome.getDisplayName() + "）";
            if (outcome == FlowOutcome.FAIL) {
                ctx.warn("返回提示", msg, null);
            } else {
                ctx.ok("返回提示", msg);
            }
            return;
        }

        throw ctx.fail("竞速等待", detail, null, "等待任一元素超时：" + describeCandidates(candidates));
    }

    private boolean isPresent(KeywordContext ctx, Candidate candidate) {
        for (String selector : candidate.selectors()) {
            try {
                if (ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                    return true;
                }
            } catch (Exception e) {
                // 探测期间会话可能抖动，不能因为一次异常就判定整条分支不成立
                ctx.warn("竞速探测异常", candidate.elementKey() + " 探测失败: " + e.getMessage(), selector);
            }
        }
        return false;
    }

    /**
     * 解析候选分支。定位在开跑前一次性解析完：元素没维护好要立刻报错，
     * 而不是白等一个超时之后才说「都没出现」。
     */
    private List<Candidate> parseCandidates(KeywordContext ctx) throws Exception {
        List<Map<String, Object>> raw = ctx.getRuntime().asStepList(ctx.rawParam("candidates"));
        List<Candidate> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> node = raw.get(i);
            String elementKey = KeywordContext.asString(node.get("elementKey"));
            if (elementKey == null || elementKey.isBlank()) {
                throw ctx.fail("竞速等待", "第 " + (i + 1) + " 个候选缺少元素", null);
            }
            String label = KeywordContext.firstNonBlank(
                    KeywordContext.asString(node.get("label")), elementKey);
            List<String> selectors = ctx.getRuntime().requireSelectors(
                    ctx.getPlatform(), ctx.getUiAppId(), elementKey, ctx.getExecParams(), ctx.getLogs(),
                    ctx.sn("竞速等待·候选" + (i + 1)));
            out.add(new Candidate(elementKey, label, selectors,
                    ctx.getRuntime().asStepList(node.get("steps"))));
        }
        return out;
    }

    private static String describeCandidates(List<Candidate> candidates) {
        return String.join(" / ", candidates.stream().map(Candidate::label).toList());
    }

    private record Candidate(
            String elementKey,
            String label,
            List<String> selectors,
            List<Map<String, Object>> steps
    ) {
    }
}
