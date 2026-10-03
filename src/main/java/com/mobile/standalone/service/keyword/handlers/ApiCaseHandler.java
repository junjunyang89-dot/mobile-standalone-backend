package com.mobile.standalone.service.keyword.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.dto.ApiTestDtos.CaseResult;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.service.ApiTestRunnerService.FlowCaseResult;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import com.mobile.standalone.util.TestEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI 流程 ↔ 接口测试基建的桥。
 * <p>
 * 不在步骤里内联 URL，只引用接口管理里已有的用例 id。环境取流程当前环境，
 * 鉴权走 {@link com.mobile.standalone.service.apitest.ApiAuthService}，断言/DB/extract
 * 复用 {@link ApiTestRunnerService}——和接口测试页点「执行」是同一条路。
 * <p>
 * 三种 mode 对应三种用法，执行内核相同，失败语义一致（非 PASSED 就让本步失败）：
 * <ul>
 *   <li>{@code SETUP} 前置造数</li>
 *   <li>{@code ASSERT} 中途断言（点完领取后查订单是否落库）</li>
 *   <li>{@code EXTRACT} 取值回填（抽订单号/战队名给后续 UI 步骤用 ${xxx}）</li>
 * </ul>
 * 时序：{@code retryTimes}/{@code retryDelayMs} 轮询，盖最终一致性窗口。
 */
@Component
@RequiredArgsConstructor
public class ApiCaseHandler implements KeywordHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> MODES = List.of("SETUP", "ASSERT", "EXTRACT");

    private final ApiTestRunnerService runnerService;

    @Override
    public String code() {
        return "API_CASE";
    }

    @Override
    public String nameZh() {
        return "接口用例";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "引用接口管理里的用例并执行（鉴权/环境变量/jsonPath/DB 断言/extract 全复用）。"
                + "可前置造数、中途断言数据层、或取值回填到 ${变量}。别在步骤里手写 URL";
    }

    @Override
    public int sortOrder() {
        return 24;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    /** 本关键字自带轮询重试；外层再套 retryTimes 会把整轮轮询再跑一遍 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public String shortLabel() {
        return "接口";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.requiredString("caseId", "接口用例 ID")
                        .withHelp("到「功能测试 → 接口测试」搜索用例，复制它的 id。按 id 引用，不在这里写 URL"),
                ParamDef.enumOf("mode", "用途", MODES, "ASSERT")
                        .withHelp("SETUP=前置造数；ASSERT=中途断言数据层；EXTRACT=取值回填给后续 UI 步骤"),
                ParamDef.text("runtimeVars", "传入变量 JSON")
                        .withHelp("合并进接口用例的 {{变量}} 上下文，如 {\"matchId\":\"${matchId}\"}。"
                                + "流程里的 ${xxx} 会先展开。密钥继续放环境变量，别写进流程"),
                ParamDef.text("extract", "额外提取 JSON")
                        .withHelp("可选，与用例自带 extractJson 合并（本步优先）。"
                                + "例 {\"teamA\":\"$.result.match.team1DTO.name\",\"teamB\":\"$.result.match.team2DTO.name\"}"),
                ParamDef.number("retryTimes", "失败再试几次", 2)
                        .withHelp("点完 UI 后库可能还没落稳。默认再试 2 次（共 3 次）；造数/取值也可酌情加大"),
                ParamDef.number("retryDelayMs", "重试间隔毫秒", 800)
                        .withHelp("最终一致性窗口，别设成 0"),
                ParamDef.string("environment", "环境覆盖")
                        .withHelp("一般留空：自动用流程当前环境（与 UI 包一致）。"
                                + "只有排障时才手动填 TEST/PROD")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String caseId = ctx.substituteAll(ctx.str("caseId"));
        if (caseId == null || caseId.isBlank()) {
            throw ctx.fail("接口用例", "请填写接口用例 ID（从接口测试页复制）", null);
        }
        String mode = KeywordContext.firstNonBlank(ctx.str("mode"), "ASSERT").trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw ctx.fail("接口用例", "mode 只能是 SETUP / ASSERT / EXTRACT，当前: " + mode, null);
        }

        String env = KeywordContext.firstNonBlank(
                ctx.substituteAll(ctx.str("environment")),
                ctx.getExecParams() != null ? ctx.getExecParams().get("environment") : null);
        env = TestEnvironment.normalize(env);

        Map<String, String> seed = new LinkedHashMap<>();
        if (ctx.getExecParams() != null) {
            seed.putAll(ctx.getExecParams());
        }
        seed.putAll(parseRuntimeVars(ctx.substituteAll(ctx.str("runtimeVars")), ctx));

        String extractOverride = ctx.substituteAll(ctx.str("extract"));
        int retries = (int) Math.max(0, Math.min(10, ctx.longParam("retryTimes", 2)));
        long delayMs = Math.max(0L, Math.min(30_000L, ctx.longParam("retryDelayMs", 800)));

        FlowCaseResult last = null;
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i <= retries; i++) {
            ctx.ensureNotCancelled();
            if (i > 0 && delayMs > 0) {
                ctx.sleep(delayMs);
            }
            try {
                last = runnerService.runCaseForFlow(caseId.trim(), env, seed, extractOverride);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw ctx.fail("接口用例", e.getMessage(), caseId, e.getMessage());
            }
            CaseResult cr = last.result();
            String st = cr.getStatus() == null ? "?" : cr.getStatus();
            attempts.add((i + 1) + ":" + st);
            if ("PASSED".equals(st)) {
                break;
            }
            // BLOCKED（登录失败/网关挂）再撞也没用，别空耗重试预算
            if ("BLOCKED".equals(st)) {
                break;
            }
        }

        CaseResult cr = last.result();
        String status = cr.getStatus() == null ? "?" : cr.getStatus();
        if (!"PASSED".equals(status)) {
            String err = cr.getError() != null ? cr.getError() : status;
            throw ctx.fail("接口用例",
                    modeLabel(mode) + "失败 · " + safeName(cr) + " · " + status + " · " + err
                            + " · 环境 " + env
                            + (cr.getUrl() != null ? " · " + cr.getUrl() : "")
                            + " · 尝试 " + String.join(",", attempts),
                    caseId,
                    "接口用例失败: " + safeName(cr));
        }

        Map<String, String> extracted = last.extracted() != null ? last.extracted() : Map.of();
        if ("EXTRACT".equals(mode) && extracted.isEmpty()) {
            throw ctx.fail("接口用例",
                    "取值回填模式但没有抽到任何变量。给用例配 extractJson，或在本步「额外提取」里写 "
                            + "{\"teamA\":\"$.path\"}",
                    caseId, "接口用例未提取到变量");
        }
        for (Map.Entry<String, String> e : extracted.entrySet()) {
            ctx.putVar(e.getKey(), e.getValue());
        }

        String detail = modeLabel(mode) + " · " + safeName(cr)
                + " · " + cr.getMethod() + " " + (cr.getUrl() == null ? "" : cr.getUrl())
                + " · HTTP " + cr.getHttpStatus()
                + " · 环境 " + env
                + " · " + (cr.getDurationMs() == null ? "?" : cr.getDurationMs()) + "ms";
        if (!extracted.isEmpty()) {
            List<String> parts = new ArrayList<>();
            extracted.forEach((k, v) -> parts.add(k + "=" + truncate(v, 40)));
            detail += " · 回填 " + String.join(", ", parts);
        }
        if (attempts.size() > 1) {
            detail += " · 尝试 " + String.join(",", attempts);
        }
        ctx.ok("接口用例", detail);
    }

    private static String modeLabel(String mode) {
        return switch (mode) {
            case "SETUP" -> "前置造数";
            case "EXTRACT" -> "取值回填";
            default -> "中途断言";
        };
    }

    private static String safeName(CaseResult cr) {
        if (cr.getName() != null && !cr.getName().isBlank()) {
            return cr.getName();
        }
        return cr.getId() != null ? cr.getId() : "?";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Map<String, String> parseRuntimeVars(String raw, KeywordContext ctx) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = JSON.readTree(trimmed);
            if (node == null || !node.isObject()) {
                throw ctx.fail("接口用例", "传入变量必须是 JSON 对象", null);
            }
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isNull()) {
                    continue;
                }
                out.put(e.getKey().trim(), e.getValue().asText());
            }
            return out;
        } catch (com.mobile.standalone.service.UiFlowExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw ctx.fail("接口用例", "传入变量不是合法 JSON：" + e.getMessage(), null);
        }
    }
}
