package com.mobile.standalone.service.keyword;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.driver.PlatformDriver;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个关键字的执行上下文：步骤数据 + 运行环境 + 回调能力。
 * <p>
 * 关键字实现只拿这一个入参，不再像原来的 switch 那样每个分支都要透传十几个参数。
 */
@Getter
@Builder
public class KeywordContext {

    /**
     * {@code ${name}} 与 {@code {{name}}} 都认。
     * <p>
     * 接口用例那边用的是 {@code {{name}}}（Postman 习惯），流程这边历史上是 {@code ${name}}。
     * 同一个产品里两套语法，人一定会写混——与其让他们记，不如两个都收。
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}|\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** 步骤解析出的关键字 code（已大写，别名已归一到主 code 之前的原始值） */
    private final String rawCode;
    /** 步骤引用的元素 key，可为空 */
    private final String elementKey;
    /** 步骤自带参数 */
    private final Map<String, Object> stepParams;

    private final PlatformDriver driver;
    private final Device device;
    private final String deviceId;
    /** 任务平台名，如 ANDROID / POG_IOS */
    private final String platform;
    /** 应用字典 ID */
    private final String uiAppId;
    /** 流程级执行参数（含变量替换用的账号、验证码等） */
    private final Map<String, String> execParams;

    private final List<ExecutionLogEntryDto> logs;
    private final Task task;
    /** 组件调用嵌套深度 */
    private final int depth;
    /** 步骤大纲编号，如 "3.2" */
    private final String pathNo;
    /** 性能采样模式：跳过逐步截图，断言失败不中断 */
    private final boolean performanceMode;

    private final RunState state;
    private final FlowRuntime runtime;

    /** 流程级可变状态：提前返回标记与判定 */
    public static class RunState {
        public boolean earlyReturn;
        public String returnMessage;
        /** 提前返回时这条流程算成功还是失败，由触发返回的关键字声明 */
        public FlowOutcome returnOutcome = FlowOutcome.FAIL;
        /** 本次执行是否已保存过失败现场：异常逐层上抛，只在最内层存一次就够 */
        public boolean failureDumped;

        /**
         * 本次执行用到了哪些元素、成没成。执行结束后由
         * {@code UiElementHealthService.flush} 一次性落库。
         * <p>
         * 攒着不立即写，是因为这条链路上每多一次 DB 往返都直接加在用例耗时上；
         * 而且同一个元素在一条流程里常被点很多次，逐次写等于把一次执行放大成几十条 UPDATE。
         */
        public final java.util.List<com.mobile.standalone.service.UiElementHealthService.ElementUsage>
                elementUsages = new java.util.ArrayList<>();

        public RunState(boolean earlyReturn, String returnMessage) {
            this.earlyReturn = earlyReturn;
            this.returnMessage = returnMessage;
        }

        /** 记一次提前返回 */
        public void returnEarly(String message, FlowOutcome outcome) {
            this.earlyReturn = true;
            this.returnMessage = message != null ? message : "";
            this.returnOutcome = outcome != null ? outcome : FlowOutcome.FAIL;
        }
    }

    // —— 平台判断 ——

    public boolean isIos() {
        return platform != null && platform.toUpperCase(Locale.ROOT).contains("IOS");
    }

    public boolean isHarmony() {
        return platform != null && platform.toUpperCase(Locale.ROOT).contains("HARMONY");
    }

    /** Android 家族（含 Pog-Android），不含鸿蒙 */
    public boolean isAndroidLike() {
        return platform != null
                && platform.toUpperCase(Locale.ROOT).contains("ANDROID")
                && !isHarmony();
    }

    // —— 步骤参数读取 ——

    public String str(String key) {
        return asString(stepParams.get(key));
    }

    /** 步骤参数取不到时回退到流程执行参数 */
    public String strOrExec(String key) {
        return firstNonBlank(str(key), execParams != null ? execParams.get(key) : null);
    }

    public long longParam(String key, long defaultVal) {
        return asLong(stepParams.get(key), defaultVal);
    }

    public boolean boolParam(String key, boolean defaultVal) {
        Object o = stepParams.get(key);
        if (o instanceof Boolean b) return b;
        if (o == null) return defaultVal;
        String s = String.valueOf(o).trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        return defaultVal;
    }

    public Object rawParam(String key) {
        return stepParams.get(key);
    }

    /**
     * 读一个「字符串列表」参数（元素组、标签组这类）。
     * <p>
     * 前端存进来的是数组，但同一个字段有太多别的来路：导入的流程 JSON、别人手改的库里的记录、
     * 复制粘贴出来的一行逗号分隔。收窄成「只认数组」的话，这些都会变成一句
     * 「参数格式错误」，而人看着界面上明明填着东西，根本不知道错在哪。
     * 所以数组、单个字符串、换行/逗号/顿号/分号分隔的一行，全都收。
     * <p>
     * 顺带做变量替换与去重，保持顺序——顺序是人填的判定优先级，不能打乱。
     */
    public List<String> strList(String key) {
        Object raw = stepParams.get(key);
        List<String> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        collectStrings(raw, out);
        List<String> result = new java.util.ArrayList<>();
        for (String item : out) {
            String v = substitute(item);
            if (v == null) continue;
            v = v.trim();
            if (!v.isEmpty() && seen.add(v)) {
                result.add(v);
            }
        }
        return result;
    }

    /** 数组套数组也认：嵌套只是数据来路不同，不该让人去猜格式 */
    private static void collectStrings(Object raw, List<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> it) {
            for (Object o : it) {
                collectStrings(o, out);
            }
            return;
        }
        if (raw instanceof Object[] arr) {
            for (Object o : arr) {
                collectStrings(o, out);
            }
            return;
        }
        for (String piece : String.valueOf(raw).split("[\\n\\r,;，、；]+")) {
            if (!piece.isBlank()) {
                out.add(piece);
            }
        }
    }

    /** 变量替换 + 内置变量展开（${date}、${timestamp} 等） */
    public String substituted(String key) {
        return substitute(str(key));
    }

    public String substitute(String template) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : m.group(2);
            String value = execParams != null ? execParams.get(key) : null;
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return BuiltinVariables.expand(sb.toString());
    }

    // —— 日志 ——

    /** 步骤名：带大纲编号，如「3.2 · 点击」 */
    public String sn(String title) {
        if (pathNo == null || pathNo.isBlank()) {
            return title != null ? title : "";
        }
        if (title == null || title.isBlank()) {
            return pathNo;
        }
        return pathNo + " · " + title;
    }

    public void ok(String title, String detail) {
        logs.add(ExecutionLogEntryDto.success(sn(title), detail));
    }

    /** 记一条失败日志但不中断：用于「这条路走不通，换下一条」这类可恢复的情况 */
    public void warn(String title, String detail, String locator) {
        logs.add(ExecutionLogEntryDto.fail(sn(title), detail, locator, elementKey));
    }

    /** 记失败日志并中断流程 */
    public UiFlowExecutionException fail(String title, String detail, String locator)
            throws UiFlowExecutionException {
        return fail(title, detail, locator, detail);
    }

    /**
     * 记失败日志并中断流程，异常文案与日志文案不同。
     * <p>
     * 日志给人看细节（试了几个定位、实际文案是什么），异常文案会落进 execution.error_message
     * 与任务列表，保持简短。
     */
    public UiFlowExecutionException fail(String title, String detail, String locator, String errorMessage)
            throws UiFlowExecutionException {
        logs.add(ExecutionLogEntryDto.fail(sn(title), detail, locator, elementKey));
        throw new UiFlowExecutionException(errorMessage, logs);
    }

    // —— 常用回调的快捷方式 ——

    public List<String> selectors(String stepName) throws UiFlowExecutionException {
        return runtime.requireSelectors(platform, uiAppId, elementKey, execParams, logs, sn(stepName));
    }

    public String selector(String stepName) throws UiFlowExecutionException {
        return selectors(stepName).get(0);
    }

    public void screenshot(String label) {
        runtime.captureScreenshot(driver, deviceId, task, logs, sn(label));
    }

    /** 等页面稳定后拍一张最终态 */
    public void screenshotWhenSettled(String label) {
        runtime.captureSettledScreenshot(driver, deviceId, task, logs, stepParams, sn(label));
    }

    public void sleep(long ms) throws Exception {
        runtime.sleepCancellable(ms, task, logs);
    }

    public void ensureNotCancelled() throws UiFlowExecutionException {
        runtime.ensureNotCancelled(task, logs);
    }

    public String packageName() {
        return runtime.resolvePackage(uiAppId, platform, stepParams, execParams);
    }

    public void runNested(List<Map<String, Object>> steps) throws Exception {
        runtime.runNestedSteps(steps, this, depth);
    }

    public void runNested(List<Map<String, Object>> steps, int childDepth) throws Exception {
        runtime.runNestedSteps(steps, this, childDepth);
    }

    /** 组件调用：子流程可能绑定了别的应用字典 */
    public KeywordContext withUiAppId(String childUiAppId) {
        return toBuilder().uiAppId(childUiAppId).build();
    }

    public KeywordContextBuilder toBuilder() {
        return KeywordContext.builder()
                .rawCode(rawCode)
                .elementKey(elementKey)
                .stepParams(stepParams)
                .driver(driver)
                .device(device)
                .deviceId(deviceId)
                .platform(platform)
                .uiAppId(uiAppId)
                .execParams(execParams)
                .logs(logs)
                .task(task)
                .depth(depth)
                .pathNo(pathNo)
                .performanceMode(performanceMode)
                .state(state)
                .runtime(runtime);
    }

    // —— 静态工具 ——

    public static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static long asLong(Object o, long defaultVal) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return defaultVal;
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
