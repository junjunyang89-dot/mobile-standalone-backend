package com.mobile.standalone.service.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.service.UiElementService;
import com.mobile.standalone.service.UiFlowService;
import com.mobile.standalone.service.ai.QwenChatClient;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.KeywordRegistry;
import com.mobile.standalone.service.keyword.ParamDef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 说一句话，编一条流程草稿。
 *
 * <p><b>为什么这件事能交给模型，而写断言不能</b>：编排是<b>选择题</b>——
 * 关键字库是有限的、元素库是有限的，模型只是从里面挑、排序、填参数。
 * 而它选得对不对，<b>大部分可以当场查出来</b>（元素在不在、参数齐不齐、
 * 有没有自我循环断言），不用跑设备也不用再问模型。
 * 判据硬，才敢交出去；写断言没有这种判据，所以那件事仍然是人的。
 *
 * <p>产物一律是<b>停用的草稿</b>。省掉的是「把二十步一个个拖出来、填参数、选元素」
 * 这件苦力活，不是省掉人的调试——后者才是这条流程能不能用的真正判据。
 *
 * <p>元素上下文按<b>人勾的页面</b>来装。不按页面限定的话，上千个元素塞不进去，
 * 而且噪音会让它选得更差；人本来就知道这条用例走哪几个页面，这个信息白送的，
 * 还顺带告诉了模型页面顺序。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiFlowComposer {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 校验没过就把问题回给模型重来。只给一次——两次还不对，多半是描述或元素有问题，该让人看 */
    private static final int MAX_RETRY = 1;

    /**
     * AI 草稿的描述前缀。<b>唯一作用是把 AI 自己的产物从范例里排除掉。</b>
     *
     * <p>不排除的话会形成一个闭环：模型编了一条有问题的流程 → 它进了库 →
     * 下次被当成「人工范例」喂回去 → 错误被当成惯例学走。
     * 而这种退化<b>没有任何报错</b>，只会表现为「AI 越编越差」，查都没法查。
     */
    static final String AI_DRAFT_MARK = "由 AI 按描述编排：";

    /** 范例给几条。给多了挤掉元素清单，而且三条已经足够把惯例表达清楚 */
    private static final int MAX_EXAMPLES = 3;
    /** 太长的流程不适合当范例：占 token，而且它自己的结构往往也是堆出来的 */
    private static final int MAX_EXAMPLE_STEPS = 30;

    private final KeywordRegistry keywordRegistry;
    private final UiElementService uiElementService;
    private final UiFlowService uiFlowService;
    private final QwenChatClient chatClient;

    /**
     * @param environment TEST / PROD
     * @param catalog     AUTOMATION / ONLINE / SHARED
     *
     * <p>环境和轨道<b>由人选，不交给模型</b>。这两个值决定这条流程以后会被哪些批次捞走——
     * 让模型从一句自然语言里猜，猜错了不会当场报错，
     * 而是过一阵子在某个批次里冒出来一条没人预期的流程。
     */
    /**
     * <p><b>用 builder 构造，不要用全参构造器。</b>
     * 这个参数表已经被追着加过三次字段（environment / catalog / releaseStage），
     * 每加一次，按位置传参的调用方就编不过一次——而那个报错长得像类型错误，
     * 跟「有人加了个字段」一点关系都看不出来。
     * builder 之后加字段不会再打断任何调用方。
     */
    @lombok.Builder
    public record Request(String appId, String platform, List<String> pageIds,
                          String intent, String flowName, Set<String> knownVars,
                          String environment, String catalog, String releaseStage) {
    }

    public record Draft(String flowId, String flowName, int steps,
                        List<String> notes, String rawModelOutput) {
    }

    public Draft compose(Request req) throws Exception {
        if (!chatClient.isConfigured()) {
            throw new IllegalStateException("没有配置可用的模型，AI 编排用不了");
        }
        if (req.appId() == null || req.appId().isBlank()) {
            throw new IllegalArgumentException("appId 必填");
        }
        if (req.intent() == null || req.intent().isBlank()) {
            throw new IllegalArgumentException("说一下这条流程要干什么");
        }

        Map<String, FlowDraftValidator.ElementRef> elements = loadElements(req);
        if (elements.isEmpty()) {
            throw new IllegalStateException("这几个页面下没有可用的元素（只取 ACTIVE 的）。"
                    + "先去元素管理确认页面归属和状态");
        }
        Map<String, FlowDraftValidator.KeywordRef> keywords = loadKeywords();

        String prompt = buildPrompt(req, elements);
        String raw = null;
        List<String> lastProblems = List.of();

        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            String ask = attempt == 0 ? prompt
                    : prompt + "\n\n上一次生成有这些问题，请修正后重新输出完整 JSON：\n- "
                            + String.join("\n- ", lastProblems);
            raw = chatClient.chat(ask);
            List<FlowDraftValidator.Step> steps = parseSteps(raw);
            FlowDraftValidator.Result r = FlowDraftValidator.check(
                    steps, elements, keywords, req.pageIds(), req.knownVars());
            if (r.usable()) {
                return save(req, r);
            }
            lastProblems = r.problems();
            log.info("[ai-compose] 第 {} 次校验未通过: {}", attempt + 1, lastProblems);
        }
        // 两次都没过就如实说，别把一份自己都验不过的草稿塞给人——
        // 人打开之后要逐条当校验器用，比自己编还慢
        throw new IllegalStateException("AI 编出来的流程没通过校验，已重试一次仍不行：\n- "
                + String.join("\n- ", lastProblems)
                + "\n多半是描述太笼统，或这几个页面里缺少需要的元素");
    }

    // ==================== 上下文 ====================

    private Map<String, FlowDraftValidator.ElementRef> loadElements(Request req) {
        Map<String, FlowDraftValidator.ElementRef> out = new LinkedHashMap<>();
        List<String> pages = req.pageIds() == null || req.pageIds().isEmpty()
                ? java.util.Collections.singletonList(null) : req.pageIds();
        for (String pageId : pages) {
            // 只给 ACTIVE：CANDIDATE 是还没审的，让模型拿它编流程等于把未审内容洗成正式用例
            for (UiElement e : uiElementService.list(req.appId(), req.platform(), null, null,
                    null, pageId, null, "ACTIVE", null)) {
                if (e.getNameEn() == null || e.getNameEn().isBlank()) continue;
                out.putIfAbsent(e.getNameEn(), new FlowDraftValidator.ElementRef(
                        e.getNameEn(), e.getPageId(), e.getLocatorValue()));
            }
        }
        return out;
    }

    private Map<String, FlowDraftValidator.KeywordRef> loadKeywords() {
        Map<String, FlowDraftValidator.KeywordRef> out = new LinkedHashMap<>();
        for (KeywordHandler h : keywordRegistry.all()) {
            Set<String> known = new LinkedHashSet<>();
            Set<String> required = new LinkedHashSet<>();
            Set<String> nested = new LinkedHashSet<>();
            for (ParamDef p : h.params()) {
                known.add(p.name());
                if (p.type() == ParamDef.Type.STEPS) {
                    // 子步骤参数单独记下来，校验器要递归进去查
                    nested.add(p.name());
                    continue;
                }
                // 元素由 elementKey 承载，不在参数里重复要求
                if (p.required() && p.type() != ParamDef.Type.ELEMENT) {
                    required.add(p.name());
                }
            }
            known.add("elementKey");
            out.put(h.code().toUpperCase(Locale.ROOT),
                    new FlowDraftValidator.KeywordRef(h.code(), h.needsElement(), known, required, nested));
        }
        return out;
    }

    private String buildPrompt(Request req, Map<String, FlowDraftValidator.ElementRef> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在给一个移动端 UI 自动化平台编排流程。只能用下面列出的关键字和元素，不许自己发明。\n\n");

        sb.append("## 可用关键字\n");
        for (KeywordHandler h : keywordRegistry.all()) {
            sb.append("- ").append(h.code()).append("（").append(h.nameZh()).append("）");
            if (h.needsElement()) sb.append(" [需要 elementKey]");
            if (h.description() != null && !h.description().isBlank()) {
                sb.append("：").append(h.description());
            }
            List<ParamDef> ps = h.params();
            if (!ps.isEmpty()) {
                List<String> bits = new ArrayList<>();
                for (ParamDef p : ps) {
                    if (p.type() == ParamDef.Type.ELEMENT) continue;
                    if (p.type() == ParamDef.Type.STEPS) {
                        // 标清楚它装的是一串子步骤，不是一个字符串——
                        // 不标的话模型会把它当普通参数，要么不填，要么塞一句话进去
                        bits.add(p.name() + "【子步骤数组】");
                        continue;
                    }
                    String one = p.name() + (p.required() ? "(必填)" : "");
                    if (p.options() != null && !p.options().isEmpty()) {
                        one += "=" + String.join("|", p.options());
                    }
                    bits.add(one);
                }
                if (!bits.isEmpty()) sb.append("  参数: ").append(String.join(", ", bits));
            }
            sb.append('\n');
        }

        sb.append("\n## 可用元素（elementKey → 说明 · 所属页面）\n");
        for (UiElement e : elementDetails(req)) {
            sb.append("- ").append(e.getNameEn());
            if (e.getNameZh() != null && !e.getNameZh().isBlank()) sb.append(" → ").append(e.getNameZh());
            if (e.getPageId() != null && !e.getPageId().isBlank()) sb.append(" · ").append(e.getPageId());
            if (e.getVisualHint() != null && !e.getVisualHint().isBlank()) {
                sb.append(" · ").append(e.getVisualHint());
            }
            // 定位里带 ${} 的，把变量名直接标出来。
            //
            // 不标的话，模型面对「点刀塔商城」只有两条路：要么去找一个叫「刀塔商城」的元素
            // （库里根本没有，于是打回重来），要么自己猜一个变量名（拼错就是运行时报缺变量）。
            // 标出来之后这一步就不用猜了——而这正是人工流程里用得最多的写法。
            List<String> vars = com.mobile.standalone.util.LocatorTemplate
                    .variablesIn(e.getLocatorValue());
            if (vars != null && !vars.isEmpty()) {
                sb.append(" · 文案变量: ").append(String.join("、", vars))
                  .append("（用这个元素时要在本步 params 里给它赋值）");
            }
            sb.append('\n');
        }

        if (req.pageIds() != null && !req.pageIds().isEmpty()) {
            sb.append("\n## 页面路径（按这个顺序走）\n")
              .append(String.join(" → ", req.pageIds())).append('\n');
        }

        sb.append("""

                ## 先挑聚合关键字，不要展开成重复步骤

                这是最容易做错的地方。下面这些关键字一步顶很多步，能用就必须用：

                - `SWIPE_PAIR` —— <b>双向滑动</b>。「上滑 2 次、下滑 4 次」是<b>一步</b>
                  （axis=vertical, upTimes=2, downTimes=4），不是六步 SWIPE。
                - `CLICK_EACH` —— 同一个元素换不同文案依次点（元素定位里带文案变量时用）。
                - `CLICK_EACH_SWIPE` —— 依次点击，每点完滑一下。「挨个 Tab 进去浏览」用这个。
                - `CLICK_EACH_OPEN_BACK` —— 依次点进去、看一眼、返回、下一项。

                <b>禁止</b>把上面任何一种展开成重复的 CLICK / SWIPE。
                同一个动作要做 N 次、或一组同类入口要挨个走一遍，就是聚合关键字的场景。

                ## 条件分支的动作要放进子步骤里

                标了【子步骤数组】的参数，值是<b>一串步骤</b>，形状和外层一模一样。

                「判断 X 存在，存在就点 Y」必须写成：

                ```json
                {"keywordCode":"IF_ELEMENT_EXISTS","elementKey":"x",
                 "params":{"timeoutSec":5,
                           "thenSteps":[{"keywordCode":"CLICK","elementKey":"y","params":{}}]}}
                ```

                <b>不许写成一个空的 IF_ELEMENT_EXISTS，再把点击平铺到它后面。</b>
                那样判断和动作就脱钩了：判断不成立时后面照样执行，等于没判断；
                而且 thenSteps 空着的条件分支什么都不做，纯占位。

                ## 人说的是意图，步骤由你补

                人不会写「判断存在→点击→等待」，他只会说「进入周边商城」。
                下面这些展开是默认动作，人没写你也要补上；人写了细节就按他的：

                | 人说 | 展开成 |
                | --- | --- |
                | 进入 / 打开 X | WAIT_ELEMENT(X, Visible, 10) → CLICK(X) → WAIT_ELEMENT(新页面的标志元素) |
                | 浏览 / 看一遍 X | SWIPE_PAIR(vertical, up=2, down=2) |
                | 输入 X | CLICK(输入框) → INPUT(text=X) |
                | 返回 | BACK |
                | 冷启动 / 重新打开 | COLD_START |

                没说次数就用默认值（滑动各 2 次、等待 10 秒）——人到编排页改数字，比在描述里写快。

                如果「要做的事」里已经按序号写了 keywordCode，那些步必须保留：
                顺序不许改，关键字不许换成别的，给出的 elementKey 必须用上。
                只在动作之间补必要的 WAIT_ELEMENT，并补上缺的参数。

                ## 同一类控件用一个元素 + 文案变量，不要找 N 个元素

                「CS商城 / 刀塔商城 / 英雄联盟商城」这种同一排的 Tab，元素库里通常**只有一个**元素，
                它的定位里带 ${xxx}（比如 mall_category_tab 的定位是 //*[@text='${mall_category_tab}']）。
                点哪个，就在这一步的 params 里给这个变量赋值：

                ```json
                {"keywordCode":"CLICK","elementKey":"mall_category_tab",
                 "params":{"mall_category_tab":"刀塔商城"}}
                ```

                **变量名就是定位里 ${} 中间那个词**，不要自己起名，也不要去找「刀塔商城」这个独立元素——
                多半没有。上面「可用元素」清单里凡是定位带 ${} 的都是这种用法，已经标出来了。

                ## 滑动分两种，别用错

                - SWIPE —— 整屏滑。翻页、看长列表用它。
                - SWIPE_IN_ELEMENT（区域内滑动）—— **在某个元素的范围内滑**。
                  金刚位、横向卡片流、横排 Tab 条用它。整屏滑会被外层容器抢走手势，
                  结果是翻了页而不是滑动那一条。

                横向的、成排的东西，一律先考虑 SWIPE_IN_ELEMENT。

                ## IF_ELEMENT_EXISTS 的 endStatus 要想清楚

                元素不存在、且 elseSteps 为空时，流程按 endStatus 结束（默认 FAIL）：

                - 必现的页面或按钮 → FAIL（默认，不用写）：找不到就是出问题了，该报失败
                - 可选弹窗（签到、广告） → "endStatus":"PASS"：没有就跳过，不算失败
                - 前置条件不满足 → "endStatus":"SKIP"

                只有在「不存在时要做别的事」（比如先关掉一个弹窗再继续）时，才写 elseSteps。

                ## 不要编人没说的断言

                人没说要验什么，就<b>只在最后加一步 ASSERT_NO_ERROR</b>。
                不要自己发明业务断言——猜出来的断言要么一跑就红，要么全绿但什么都没验，
                两种都比没有断言更糟。

                ## 例子

                人说：「冷启动后，依次浏览周边商城里的 CS 商城和刀塔商城，每个都上下滑动浏览一遍」

                应该编成（注意只有 4 步，不是十几步）：

                ```json
                [
                  {"keywordCode":"COLD_START","params":{}},
                  {"keywordCode":"CLICK","elementKey":"tab_peripheral_mall","params":{}},
                  {"keywordCode":"CLICK_EACH_SWIPE","elementKey":"tab_mall_category",
                   "params":{"varName":"name","sequence":"CS商城,刀塔商城",
                             "afterSwipe":"up","afterSwipeTimes":2}},
                  {"keywordCode":"ASSERT_NO_ERROR","params":{}}
                ]
                ```

                """);
        sb.append(renderExamples(loadExamples(req)));
        sb.append("\n## 要做的事\n").append(req.intent()).append("""


                ## 输出规则
                1. 只输出 JSON 数组，不要任何解释文字、不要 markdown 代码块。
                2. 每项形如 {"keywordCode":"CLICK","elementKey":"btn_x","params":{}}。
                3. needsElement 的关键字必须给 elementKey，且必须是上面列出的；不许自己编元素名。
                4. 必填参数一个都不能少。
                5. 动作（点击/返回/启动/滑动）之后，如果下一步要操作别的元素，
                   先加一步 WAIT_ELEMENT 等那个元素（waitType=Visible, timeoutSec=10）。
                6. <b>绝对不要断言上一步刚点过的那个元素还在</b>——点击成功本身就说明它在，
                   那样的断言什么都验不到。
                7. 金额、数量、比分这类断言一律用 mode=equals，不要用 contains。
                8. 步数越少越好。同样的效果，能用 4 步就不要写 11 步。
                """);
        return sb.toString();
    }

    /**
     * 挑几条<b>人自己编的、跑得通的</b>流程当范例。
     *
     * <p>为什么要这么做：前面三轮都是我把「你们编排流程的惯例」猜着写成提示词规则——
     * 猜一条补一条，而人一跑就发现还差别的。这个循环不收敛，因为惯例是隐性的：
     * 「判断不成立要走失败结束」「同类 Tab 用一个元素加文案变量」「金刚位用区域内滑动」
     * 这些没人写下来过，只存在于已经编好的流程里。
     *
     * <p>所以改成直接把那些流程给模型看。好处不只是准，还在于<b>它会跟着惯例自动更新</b>：
     * 以后你们改了写法，范例跟着变，不用再改这里一行代码。
     *
     * <p>只取 {@code enabled=true} 的：停用的多半是编废了或还没调通的，
     * 拿它当范例等于把没验证过的写法固化下来。
     */
    List<UiFlow> loadExamples(Request req) {
        List<UiFlow> all;
        try {
            all = uiFlowService.list(req.appId(), req.platform(), null, Boolean.FALSE, null, null);
        } catch (Exception e) {
            // 范例是锦上添花，取不到就照常编——不能因为这个让整个功能挂掉
            log.warn("[ai-compose] 取范例流程失败，这次不带范例: {}", e.getMessage());
            return List.of();
        }
        List<UiFlow> usable = new ArrayList<>();
        for (UiFlow f : all) {
            if (f == null || !Boolean.TRUE.equals(f.getEnabled())) continue;
            if (Boolean.TRUE.equals(f.getIsComponent())) continue;
            if (f.getSteps() == null || f.getSteps().isBlank()) continue;
            if (f.getDescription() != null && f.getDescription().startsWith(AI_DRAFT_MARK)) continue;
            int n = countSteps(f.getSteps());
            if (n < 3 || n > MAX_EXAMPLE_STEPS) continue;
            usable.add(f);
        }
        // 排序：优先带嵌套分支的。那正是模型最不会写、而人写得最多的结构——
        // 一条全是平铺 CLICK 的流程当范例，等于告诉它「平铺就行」
        usable.sort((a, b) -> Integer.compare(exampleScore(b), exampleScore(a)));
        return usable.size() <= MAX_EXAMPLES ? usable : usable.subList(0, MAX_EXAMPLES);
    }

    /** 嵌套分支权重高于步骤丰富度：结构比长度更值得学 */
    private int exampleScore(UiFlow f) {
        try {
            JsonNode arr = JSON.readTree(f.getSteps());
            int nested = 0;
            Set<String> kinds = new LinkedHashSet<>();
            for (JsonNode n : arr) {
                String code = text(n, "keywordCode");
                if (code != null) kinds.add(code.toUpperCase(Locale.ROOT));
                JsonNode ps = n.get("params");
                if (ps == null || !ps.isObject()) continue;
                java.util.Iterator<Map.Entry<String, JsonNode>> it = ps.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue() != null && e.getValue().isArray() && !e.getValue().isEmpty()) {
                        nested += e.getValue().size();
                    }
                }
            }
            return nested * 10 + kinds.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private int countSteps(String stepsJson) {
        try {
            JsonNode arr = JSON.readTree(stepsJson);
            return arr != null && arr.isArray() ? arr.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把范例流程渲染进提示词。
     *
     * <p>只保留 keywordCode / elementKey / params 三个字段。库里那份还带着 id、前端展开状态之类的
     * 界面字段，原样贴进去会让模型以为那些也要输出。
     */
    String renderExamples(List<UiFlow> examples) {
        if (examples == null || examples.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 照着这些真实流程的写法来（人工编写、已跑通）\n\n")
          .append("这几条是这个应用里已经在用的流程。**它们的结构就是标准**——"
                  + "特别注意判断分支怎么用、同一个元素怎么配文案变量、什么时候用区域内滑动。\n"
                  + "你要编的流程应该和它们长得像。\n\n");
        for (UiFlow f : examples) {
            sb.append("### 范例：").append(f.getName() == null ? "" : f.getName()).append('\n');
            if (f.getDescription() != null && !f.getDescription().isBlank()) {
                sb.append("> ").append(truncate(f.getDescription(), 120)).append('\n');
            }
            sb.append("```json\n").append(slimSteps(f.getSteps())).append("\n```\n\n");
        }
        return sb.toString();
    }

    /** 递归只留下这三个字段，顺手把空 params 去掉 */
    String slimSteps(String stepsJson) {
        try {
            JsonNode arr = JSON.readTree(stepsJson);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(slimArray(arr));
        } catch (Exception e) {
            return stepsJson == null ? "[]" : stepsJson;
        }
    }

    private List<Map<String, Object>> slimArray(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String code = text(n, "keywordCode");
            if (code == null || code.isBlank()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keywordCode", code);
            String el = text(n, "elementKey");
            if (el != null && !el.isBlank()) m.put("elementKey", el);
            JsonNode ps = n.get("params");
            if (ps != null && ps.isObject() && !ps.isEmpty()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                java.util.Iterator<Map.Entry<String, JsonNode>> it = ps.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    if (v == null || v.isNull()) continue;
                    if (v.isArray() && !v.isEmpty() && v.get(0) != null && v.get(0).has("keywordCode")) {
                        pm.put(e.getKey(), slimArray(v));
                    } else if (!v.isArray() || !v.isEmpty()) {
                        pm.put(e.getKey(), toJava(v));
                    }
                }
                if (!pm.isEmpty()) m.put("params", pm);
            }
            out.add(m);
        }
        return out;
    }

    private List<UiElement> elementDetails(Request req) {
        List<UiElement> out = new ArrayList<>();
        List<String> pages = req.pageIds() == null || req.pageIds().isEmpty()
                ? java.util.Collections.singletonList(null) : req.pageIds();
        Set<String> seen = new LinkedHashSet<>();
        for (String pageId : pages) {
            for (UiElement e : uiElementService.list(req.appId(), req.platform(), null, null,
                    null, pageId, null, "ACTIVE", null)) {
                if (e.getNameEn() != null && seen.add(e.getNameEn())) out.add(e);
            }
        }
        return out;
    }

    // ==================== 解析与落库 ====================

    static List<FlowDraftValidator.Step> parseSteps(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.trim();
        // 模型很爱裹一层 ```json，剥掉再解析比在提示词里反复叮嘱可靠
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        s = s.substring(start, end + 1);
        try {
            JsonNode arr = JSON.readTree(s);
            List<FlowDraftValidator.Step> out = new ArrayList<>();
            for (JsonNode n : arr) {
                Map<String, Object> params = new LinkedHashMap<>();
                JsonNode p = n.get("params");
                if (p != null && p.isObject()) {
                    p.fields().forEachRemaining(f -> params.put(f.getKey(), toJava(f.getValue())));
                }
                out.add(new FlowDraftValidator.Step(
                        text(n, "keywordCode"), text(n, "elementKey"), params));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * JSON → Java，<b>递归</b>。
     *
     * <p>这里原来是一行 {@code asText()}，而 Jackson 对数组和对象节点的
     * {@code asText()} 返回<b>空字符串</b>。于是模型哪怕把 {@code thenSteps}
     * 完全写对了，到这里也会被拍成 {@code ""}：子步骤全部丢掉，
     * 落库的流程里判断分支是空的。
     *
     * <p><b>这才是「流程跑不起来」的真正原因</b>——不是提示词没教会它。
     * 而且这个 bug 特别隐蔽：外层大纲看着好好的，步数也对，
     * 只有点进判断步里才会发现分支是空的。
     */
    static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode c : n) {
                list.add(toJava(c));
            }
            return list;
        }
        if (n.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            n.fields().forEachRemaining(f -> m.put(f.getKey(), toJava(f.getValue())));
            return m;
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        return n.asText();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private Draft save(Request req, FlowDraftValidator.Result r) throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (FlowDraftValidator.Step st : r.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keywordCode", st.keywordCode());
            if (st.elementKey() != null && !st.elementKey().isBlank()) {
                m.put("elementKey", st.elementKey());
            }
            if (st.params() != null && !st.params().isEmpty()) m.put("params", st.params());
            steps.add(m);
        }
        UiFlow flow = new UiFlow();
        flow.setName(req.flowName() != null && !req.flowName().isBlank()
                ? req.flowName().trim() : "AI 编排-" + java.time.LocalDate.now());
        flow.setDescription(AI_DRAFT_MARK + truncate(req.intent(), 200));
        flow.setAppId(req.appId());
        flow.setPlatform(req.platform());
        flow.setSteps(JSON.writeValueAsString(steps));
        // 人没选就按最保守的来：只进自动化轨、只在测试环境。
        // 反过来默认 ONLINE/PROD 的话，一条刚生成、没人跑过的草稿会进线上回归的候选列表
        flow.setCatalog(normalizeCatalog(req.catalog()));
        flow.setEnvironment(normalizeEnvironment(req.environment()));
        // 人没选就按「本版新加的」算：跑老包的回归批次会据此把它排除掉。
        // 认不出的写法归到 ONLINE（见 UiFlowService.normalizeReleaseStage），
        // 那是给存量流程留的，对新草稿来说 TESTING 才是安全的那一边
        flow.setReleaseStage(req.releaseStage() == null || req.releaseStage().isBlank()
                ? "TESTING"
                : com.mobile.standalone.service.UiFlowService.normalizeReleaseStage(req.releaseStage()));
        // 草稿一律停用：没人跑过的流程不该出现在批量执行列表里
        flow.setEnabled(false);
        UiFlow saved = uiFlowService.create(flow);
        log.info("[ai-compose] 生成草稿 flow={} 步数={}", saved.getId(), steps.size());
        return new Draft(saved.getId(), saved.getName(), steps.size(), r.notes(), null);
    }

    /**
     * 人没选就是 TEST。
     *
     * <p><b>不能直接用 {@code TestEnvironment.normalize}</b>：它对空值返回 PROD，
     * 那是为了存量任务的兼容（老数据没这个字段，行为得跟改动前一致）。
     * 但这里是一条刚生成、没人跑过的草稿——默认落到生产是反的。
     */
    static String normalizeEnvironment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "TEST";
        }
        return com.mobile.standalone.util.TestEnvironment.normalize(raw);
    }

    /** 认不出就归 AUTOMATION——写错一个值不该让它悄悄进线上回归那一侧 */
    static String normalizeCatalog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "AUTOMATION";
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "AUTOMATION", "ONLINE", "SHARED", "PERFORMANCE" -> c;
            default -> "AUTOMATION";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
