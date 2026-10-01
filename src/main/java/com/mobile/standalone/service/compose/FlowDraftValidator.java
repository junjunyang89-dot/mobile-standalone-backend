package com.mobile.standalone.service.compose;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 AI 编出来的流程草稿。
 *
 * <p><b>为什么这件事值得单独做一个类</b>：AI 编排和 AI 写断言不是一类问题。
 * 断言对不对，没有客观判据，只能靠人；而流程编排的产物<b>大部分对错是可以查出来的</b>——
 * 引用的元素在不在库里、关键字存不存在、必填参数齐没齐，全都是查一下就知道的事。
 *
 * <p>所以这里的定位不是「再加一道保险」，是<b>把根本不该出现在人面前的草稿挡回去</b>。
 * 人打开草稿时看到的每一条，都应该是「元素都在、参数都齐、没有自我循环断言」的。
 * 否则人就得逐条当校验器用，那正是这个功能想省掉的事。
 *
 * <p>纯函数，不碰库也不碰模型：调用方把元素和关键字查好传进来。
 * 这样它能被单测完整覆盖——而这类规则一旦写松，失效是悄无声息的。
 */
public final class FlowDraftValidator {

    private FlowDraftValidator() {
    }

    /** 元素定位里的具名变量，如 {@code ${tab_name}}。和 LocatorTemplate 保持同一套写法 */
    private static final Pattern NAMED_VAR = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    /** 点完之后画面会变的动作。这些后面必须有等待，否则下一步是在跟渲染赛跑 */
    private static final Set<String> NAVIGATING = Set.of(
            "CLICK", "CLICK_WHEN_ENABLED", "CLICK_H5", "BACK", "LAUNCH_APP",
            "COLD_START", "HOT_START", "SWIPE", "GO_HOME");

    /** 断言类关键字：它们不改变界面，只读 */
    private static final Set<String> ASSERTIONS = Set.of(
            "ASSERT_TEXT", "ASSERT_ELEMENTS_EXIST", "ASSERT_ELEMENT_STATE",
            "ASSERT_NO_ERROR", "ASSERT_H5_URL");

    /** 已经是等待的关键字，不用再补 */
    private static final Set<String> WAITS = Set.of("WAIT", "WAIT_ELEMENT", "WAIT_ANY", "SCROLL_UNTIL");

    /** 元素库里的一条，只取校验要用的字段 */
    public record ElementRef(String nameEn, String pageId, String locatorValue) {
    }

    /** 关键字库里的一条 */
    /**
     * @param stepsParams 值是「一串子步骤」的参数名，如 IF_ELEMENT_EXISTS 的 thenSteps / elseSteps。
     *                    <b>必须单独列出来</b>：子步骤里才是真正干活的那些点击，
     *                    只校验外层等于把流程最关键的部分放过去了。
     */
    public record KeywordRef(String code, boolean needsElement,
                             Set<String> knownParams, Set<String> requiredParams,
                             Set<String> stepsParams) {

        public KeywordRef(String code, boolean needsElement,
                          Set<String> knownParams, Set<String> requiredParams) {
            this(code, needsElement, knownParams, requiredParams, Set.of());
        }
    }

    /** 一个步骤。params 用可变 map，补等待时要往里塞 */
    public record Step(String keywordCode, String elementKey, Map<String, Object> params) {
    }

    /**
     * @param steps    修正后的步骤（可能比输入多几步等待）
     * @param problems 必须打回重出的问题
     * @param notes    平台替它改了什么 / 需要人补什么，不阻塞
     */
    public record Result(List<Step> steps, List<String> problems, List<String> notes) {
        public boolean usable() {
            return problems.isEmpty();
        }
    }

    /**
     * @param selectedPages 人在入口处勾的页面，按顺序。空表示不限制
     */
    public static Result check(List<Step> steps,
                               Map<String, ElementRef> elements,
                               Map<String, KeywordRef> keywords,
                               List<String> selectedPages,
                               Set<String> providedVars) {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (steps == null || steps.isEmpty()) {
            problems.add("一步都没生成");
            return new Result(List.of(), problems, notes);
        }

        Set<String> pageScope = selectedPages == null ? Set.of() : new LinkedHashSet<>(selectedPages);
        Set<String> missingVars = new LinkedHashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            Step st = steps.get(i);
            String at = "第 " + (i + 1) + " 步";
            String code = st.keywordCode() == null ? "" : st.keywordCode().trim().toUpperCase(Locale.ROOT);

            if (code.isEmpty()) {
                problems.add(at + "没有关键字");
                continue;
            }
            KeywordRef kw = keywords.get(code);
            if (kw == null) {
                // 编了个不存在的关键字。这条最常见，也最该直接打回——
                // 放过去的话执行时才报「不支持的关键字」，而那时人已经在调试了
                problems.add(at + "用了不存在的关键字：" + code);
                continue;
            }

            String key = blankToNull(st.elementKey());
            if (kw.needsElement() && key == null) {
                problems.add(at + "「" + code + "」需要元素，但没给");
            }
            // 这个元素定位里的 ${变量}。
            //
            // 这些变量的值是<b>本步 params 里给的</b>（见 KeywordContext.locatorParams：
            // 步骤参数覆盖流程参数），所以它们既不是「用不上的多余参数」，
            // 也不是「没给值的变量」——而改之前这两条误报都会发。
            //
            // 这很要命：人工流程里「一个元素配文案变量」是最常用的写法，
            // 照着人的写法编出来的草稿反而会被挑两处毛病，人会以为是 AI 编错了。
            Set<String> locatorVars = Set.of();
            if (key != null) {
                ElementRef el = elements.get(key);
                if (el == null) {
                    problems.add(at + "引用了元素库里没有的元素：" + key);
                } else {
                    if (!pageScope.isEmpty() && el.pageId() != null && !el.pageId().isBlank()
                            && !pageScope.contains(el.pageId())) {
                        problems.add(at + "用的元素「" + key + "」属于页面 " + el.pageId()
                                + "，不在这次勾选的页面范围里");
                    }
                    locatorVars = varsIn(el.locatorValue());
                    Set<String> here = new LinkedHashSet<>();
                    if (providedVars != null) {
                        here.addAll(providedVars);
                    }
                    here.addAll(scalarParamNames(st.params()));
                    collectVars(el.locatorValue(), here, missingVars);
                }
            }

            for (String req : kw.requiredParams()) {
                Object v = st.params() == null ? null : st.params().get(req);
                if (v == null || String.valueOf(v).isBlank()) {
                    problems.add(at + "「" + code + "」缺必填参数：" + req);
                }
            }
            if (st.params() != null) {
                for (String p : st.params().keySet()) {
                    if (!kw.knownParams().contains(p) && !locatorVars.contains(p)) {
                        notes.add(at + "「" + code + "」多了个用不上的参数 " + p + "，执行时会被忽略");
                    }
                }
                for (Object v : st.params().values()) {
                    collectVars(v == null ? null : String.valueOf(v), providedVars, missingVars);
                }
            }

            // 条件分支一个子步骤都没有 = 判断完什么也不做。
            //
            // 这种步骤能跑、不报错、在大纲里还占一行，看起来像是在做判断——
            // 而实际上它是空转。更糟的是人会以为「判断→动作」的逻辑已经有了，
            // 于是把真正的动作平铺在它后面：判断不成立时照样执行，等于没判断。
            if (!kw.stepsParams().isEmpty() && kw.code().toUpperCase(Locale.ROOT).startsWith("IF_")) {
                boolean anyChild = false;
                for (String sp : kw.stepsParams()) {
                    if (!asSteps(st.params() == null ? null : st.params().get(sp)).isEmpty()) {
                        anyChild = true;
                        break;
                    }
                }
                if (!anyChild) {
                    problems.add(at + "「" + code + "」一个子步骤都没有——"
                            + "判断完什么也不做。把要执行的动作放进 "
                            + String.join(" / ", kw.stepsParams()) + " 里");
                }
            }

            // elseSteps 空着的判断，结局由 endStatus 决定，默认 FAIL＝整条流程失败。
            //
            // 这个默认值对「必现页面」是对的，但对「可选弹窗」就是错的：
            // 弹窗那天没出现，整条用例报失败，而它其实什么问题都没有。
            // 这类误报最难查——今天红明天绿，看起来像偶发。
            if (code.equals("IF_ELEMENT_EXISTS")
                    && asSteps(st.params() == null ? null : st.params().get("elseSteps")).isEmpty()) {
                Object end = st.params() == null ? null : st.params().get("endStatus");
                if (end == null || String.valueOf(end).isBlank()) {
                    notes.add(at + "「" + key + "」不存在时会按默认判定 FAIL 结束整条流程。"
                            + "必现的页面就这样；如果这是可选弹窗，把 endStatus 改成 PASS");
                }
            }

            // 条件分支的子步骤要一起查。
            //
            // 「判断 X 存在，则点击 Y」这种描述，模型会编成 IF_ELEMENT_EXISTS + thenSteps，
            // 而 thenSteps 里那个点击才是这条流程真正干的事。
            // 只查外层的话，外层清一色是「判断/等待/断言」，看起来全合法，
            // 里面引用了不存在的元素也照样放过去——而人打开草稿只看得到外层大纲。
            for (String sp : kw.stepsParams()) {
                Object nested = st.params() == null ? null : st.params().get(sp);
                List<Step> children = asSteps(nested);
                if (children.isEmpty()) {
                    continue;
                }
                Result sub = check(children, elements, keywords, selectedPages, providedVars);
                for (String p : sub.problems()) {
                    problems.add(at + "「" + sp + "」里：" + p);
                }
                for (String n : sub.notes()) {
                    notes.add(at + "「" + sp + "」里：" + n);
                }
            }

            // 同义反复：刚点完 A，下一步断言 A 还在。
            // 点击成功本身就蕴含 A 存在，这种断言全绿、零价值，
            // 而且它会让一条什么都没验的流程看起来是有断言的
            if (ASSERTIONS.contains(code) && key != null && i > 0) {
                Step prev = steps.get(i - 1);
                String prevCode = prev.keywordCode() == null ? ""
                        : prev.keywordCode().trim().toUpperCase(Locale.ROOT);
                if (NAVIGATING.contains(prevCode) && key.equals(blankToNull(prev.elementKey()))) {
                    problems.add(at + "断言的是上一步刚操作过的「" + key + "」——"
                            + "上一步成功本身就说明它在，这条断言验不到任何东西。"
                            + "改成断言这次操作产生的结果");
                }
            }
        }

        if (!missingVars.isEmpty()) {
            notes.add("这些变量没给值，执行前要在环境变量或执行参数里补上："
                    + String.join("、", missingVars));
        }

        checkRepeats(steps, keywords, problems);

        List<Step> fixed = insertWaits(steps, keywords, notes);
        return new Result(fixed, problems, notes);
    }

    /**
     * 连着好几步一模一样，说明该用聚合关键字而没用。
     *
     * <p>这是模型最典型的偷懒：人说「上滑 2 次下滑 4 次」，它就老老实实排六步 SWIPE；
     * 人说「挨个 Tab 进去看看」，它就把每个 Tab 展开成一组点击加滑动。
     * 能跑，但流程从 4 步变成 11 步，**加一个 Tab 就要重编一遍**，
     * 维护成本全落在人身上——而人用这个功能正是为了不干这件事。
     *
     * <p>所以这条打回重出，不是提示：提示的话模型下次还这么写，
     * 而人看到一条十一步的流程只会觉得「AI 就这水平」，不会知道本可以是四步。
     */
    private static void checkRepeats(List<Step> steps, Map<String, KeywordRef> keywords,
                                     List<String> problems) {
        if (steps.size() < 2) {
            return;
        }
        int run = 1;
        for (int i = 1; i <= steps.size(); i++) {
            boolean same = i < steps.size() && sameAction(steps.get(i - 1), steps.get(i));
            if (same) {
                run++;
                continue;
            }
            if (run >= 2) {
                Step st = steps.get(i - 1);
                String code = upper(st.keywordCode());
                String at = "第 " + (i - run + 1) + "~" + i + " 步";
                if (code.equals("SWIPE") && keywords.containsKey("SWIPE_PAIR")) {
                    problems.add(at + "连着 " + run + " 步一样的滑动。"
                            + "用 SWIPE_PAIR 一步写完（axis + upTimes/downTimes），别排成好几步");
                } else if (code.startsWith("CLICK") && !code.startsWith("CLICK_EACH")
                        && keywords.containsKey("CLICK_EACH")) {
                    problems.add(at + "连着 " + run + " 步点同一个元素「"
                            + (st.elementKey() == null ? "" : st.elementKey())
                            + "」。用 CLICK_EACH 系列一步写完，别重复排");
                }
            }
            run = 1;
        }
    }

    /** 同一个动作：关键字、元素、参数都一样 */
    private static boolean sameAction(Step a, Step b) {
        if (!upper(a.keywordCode()).equals(upper(b.keywordCode()))) {
            return false;
        }
        String ea = blankToNull(a.elementKey());
        String eb = blankToNull(b.elementKey());
        if (ea == null ? eb != null : !ea.equals(eb)) {
            return false;
        }
        Map<String, Object> pa = a.params() == null ? Map.of() : a.params();
        Map<String, Object> pb = b.params() == null ? Map.of() : b.params();
        return String.valueOf(pa).equals(String.valueOf(pb));
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 动作后面补等待。
     *
     * <p>AI 最容易漏的就是这个，而漏了之后的失败长得像「元素定位不对」——
     * 方向完全错，人会去查元素。所以这条不打回，直接替它补上：
     * 等的是<b>下一步要用的那个元素</b>，不是刚点过的那个（刚点过的往往正要消失）。
     */
    private static List<Step> insertWaits(List<Step> steps, Map<String, KeywordRef> keywords,
                                          List<String> notes) {
        if (!keywords.containsKey("WAIT_ELEMENT")) {
            return steps;
        }
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step st = steps.get(i);
            out.add(st);
            String code = st.keywordCode() == null ? ""
                    : st.keywordCode().trim().toUpperCase(Locale.ROOT);
            if (!NAVIGATING.contains(code) || i + 1 >= steps.size()) {
                continue;
            }
            Step next = steps.get(i + 1);
            String nextCode = next.keywordCode() == null ? ""
                    : next.keywordCode().trim().toUpperCase(Locale.ROOT);
            if (WAITS.contains(nextCode)) {
                continue;
            }
            String target = blankToNull(next.elementKey());
            if (target == null) {
                continue;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("elementKey", target);
            p.put("waitType", "Visible");
            p.put("timeoutSec", 10);
            out.add(new Step("WAIT_ELEMENT", target, p));
            notes.add("第 " + (i + 1) + " 步之后补了一步「等待元素 " + target
                    + "」——动作后面不等就是在跟渲染赛跑");
        }
        return out;
    }

    /** 把参数里那坨 List&lt;Map&gt; 还原成 Step，认不出就当没有——校验不该因为格式怪就炸掉 */
    @SuppressWarnings("unchecked")
    static List<Step> asSteps(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Step> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Object code = m.get("keywordCode");
            if (code == null) {
                continue;
            }
            Object el = m.get("elementKey");
            Object ps = m.get("params");
            Map<String, Object> params = ps instanceof Map<?, ?> pm
                    ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
            out.add(new Step(String.valueOf(code), el == null ? null : String.valueOf(el), params));
        }
        return out;
    }

    /** 定位里出现的所有 ${变量名} */
    static Set<String> varsIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Matcher m = NAMED_VAR.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 本步能用来填定位变量的参数名。
     *
     * <p>只算标量：子步骤数组和对象进不了 {@code locatorParams}（那边显式跳过了
     * List 和 Map），拿它们当「已提供」会把真正缺的变量漏报掉。
     */
    private static Set<String> scalarParamNames(Map<String, Object> params) {
        Set<String> out = new LinkedHashSet<>();
        if (params == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof List<?> || v instanceof Map<?, ?>) {
                continue;
            }
            out.add(e.getKey());
        }
        return out;
    }

    private static void collectVars(String text, Set<String> provided, Set<String> missing) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = NAMED_VAR.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (provided == null || !provided.contains(name)) {
                missing.add(name);
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
