package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobile.standalone.service.ai.QwenChatClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用千问生成 APP 功能测试用例，替代 CodeWords 那条按次计费的工作流。
 * <p>
 * 产出格式跟原来的生成器保持一致（{@code {test_cases:[{case_id,title,priority,type,precondition,steps}]}}），
 * 所以落库、前端展示都不用改——换供应商不该波及下游。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QwenTestCaseGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 一次要太多会让模型开始灌水凑数，也更容易被截断 */
    private static final int MAX_CASES = 20;

    private final QwenChatClient qwen;

    public boolean isConfigured() {
        return qwen.isConfigured();
    }

    /**
     * @param pageName  页面/模块名
     * @param text      需求说明、元素清单等文字素材，可为空
     * @param images    界面截图，可为空；给了图必须用带视觉能力的模型
     */
    public JsonNode generate(String pageName, String text, List<QwenChatClient.Image> images)
            throws Exception {
        return generate(pageName, text, images, MaterialKind.PAGE_SHOT);
    }

    /**
     * @param kind 素材是页面截图还是需求文档——两者要的提示词是相反的，见 {@link MaterialKind}
     */
    public JsonNode generate(String pageName, String text, List<QwenChatClient.Image> images,
                             MaterialKind kind) throws Exception {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = images != null && !images.isEmpty();
        if (!hasText && !hasImages) {
            throw new IllegalArgumentException("请至少提供界面截图或需求说明");
        }
        String page = pageName == null || pageName.isBlank() ? "未命名页面" : pageName.trim();

        String raw = qwen.chat(prompt(page, hasText ? text.trim() : null, hasImages, kind), images);
        List<JsonNode> cases = parseCases(raw);
        if (cases.isEmpty()) {
            log.warn("[qwen-testcase] 未解析出用例，模型原文前 500 字：{}", truncate(raw, 500));
            throw new IllegalStateException("模型没有返回可解析的用例，请补充需求说明或换一张更清晰的截图");
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("page", page);
        out.put("total", cases.size());
        out.put("model", qwen.modelId());
        out.put("materialKind", kind.name());
        ArrayNode arr = out.putArray("test_cases");
        cases.forEach(arr::add);
        // 待确认项：解析不出来不算失败——用例才是主产物，
        // 为了几条问题把整次生成判失败，等于让人再等几十秒再花一次钱
        ArrayNode qs = out.putArray("open_questions");
        parseOpenQuestions(raw).forEach(qs::add);
        return out;
    }

    String prompt(String pageName, String text, boolean hasImages) {
        return prompt(pageName, text, hasImages, MaterialKind.PAGE_SHOT);
    }

    String prompt(String pageName, String text, boolean hasImages, MaterialKind kind) {
        StringBuilder sb = new StringBuilder();
        if (kind == MaterialKind.REQUIREMENT_DOC) {
            sb.append("你是资深移动端测试工程师。下面给你的是一份【需求文档】，"
                    + "请据此设计功能测试用例，并把文档本身的问题挑出来。\n\n");
        } else {
            sb.append("你是资深移动端测试工程师。请为下面这个 APP 页面设计功能测试用例。\n\n");
        }
        sb.append("【需求/模块】").append(pageName).append('\n');

        if (hasImages) {
            if (kind == MaterialKind.REQUIREMENT_DOC) {
                // 页面截图那句「不要臆造截图里没有的控件」在这里是有害的：
                // 需求文档上一个控件都没有，模型会去找「文档这一页上的按钮」
                sb.append("【需求文档】见随附图片。图上是需求条款，不是界面截图，请逐条读完再设计用例。"
                        + "不要臆造需求里没有写的规则；需求没写清楚的地方放进 open_questions，"
                        + "不要自己替产品编一个预期。\n");
            } else {
                sb.append("【界面截图】见随附图片。请依据图上真实存在的控件与文案设计用例，"
                        .concat("不要臆造截图里没有的入口或字段。\n"));
            }
        }
        if (text != null) {
            sb.append("【补充说明】\n").append(text).append('\n');
        }

        sb.append("""

                【要求】
                1. 覆盖正常流与异常流，异常流至少占三分之一：空值、超长、非法格式、边界值、
                   网络异常、重复提交、取消/返回中断。
                2. 每条用例只验一件事。一条用例里塞多个断言，失败时说不清是哪儿坏了。
                3. 步骤写成人能照着操作的动作，不要写 xpath、不要写代码。
                4. 期望结果写成可观察到的现象（看到什么文案、跳到哪个页面），
                   不要写「登录成功」这种没法直接观察的结论。
                5. 至多 %d 条，宁少勿滥。
                """.formatted(MAX_CASES));

        if (kind == MaterialKind.REQUIREMENT_DOC) {
            sb.append("""
                6. 需求文档常常是「原始需求 + 若干轮优化」拼起来的，两部分之间经常互相打架。
                   遇到互相矛盾的两条，【不要】照着各写一遍用例——那会让人测到一半才发现
                   两条用例的预期是反的。把它放进 open_questions，用例里先不写这一块。
                7. 补上需求文档通常不会写、但平台上一定会遇到的约束，例如：
                   iOS 实时活动/灵动岛的时长上限与推送频率预算、通知与定位等权限被拒的路径、
                   App 被杀死或切后台后的表现、弱网与断网、系统深浅色、机型不支持某能力时的降级。
                   这些放进正常的 test_cases 里，type 填「兼容性测试」。

                【另外单独输出 open_questions】
                把下面这几类写进 open_questions，每条说清「文档怎么写的」和「要问什么」：
                - 文档前后自相矛盾的（两处规则冲突）
                - 边界没定义的（超时多久、窗口期多长、上限是多少）
                - 目标和落地对不上的（例如要看点击率，但埋点里没有展示次数这个分母）
                宁可多问，不要自己替产品编一个预期——编出来的预期是错的，
                而错的预期比没有用例更糟：人会照着它测，然后把「符合错误预期」判成通过。
                """);
        }

        sb.append("""

                【只输出 JSON，不要任何解释文字、不要 markdown 代码块】
                {
                  "test_cases": [
                    {
                      "case_id": "TC-001",
                      "title": "用例标题，一句话说清验什么",
                      "priority": "P0|P1|P2",
                      "type": "功能测试|异常测试|兼容性测试",
                      "test_point": "比标题更细的测试点，可与标题相同",
                      "precondition": "执行前设备/账号要处于什么状态",
                      "steps": [
                        {"step": 1, "action": "具体操作", "target": "操作的控件名", "expected": "这一步应看到什么"}
                      ],
                      "expected_result": "整条用例跑完应看到的最终现象"
                    }
                  ]%s
                }
                """.formatted(kind == MaterialKind.REQUIREMENT_DOC
                        ? """
                ,
                  "open_questions": [
                    {
                      "title": "一句话说清哪里有问题",
                      "kind": "冲突|边界未定义|目标对不上",
                      "detail": "文档里是怎么写的（引用相关条款）",
                      "question": "需要找产品确认什么",
                      "impact": "不确认的话会影响哪些用例写不了"
                    }
                  ]"""
                        : ""));
        return sb.toString();
    }

    /**
     * 取出「待确认」清单。
     * <p>
     * 和用例分开解析：这一段是给人看、拿去问产品的，不进用例库。
     * 混进用例里的话，一条「这里有矛盾」会被当成用例去执行，然后被判成失败。
     */
    List<JsonNode> parseOpenQuestions(String raw) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode root = tryReadJson(stripFence(raw));
        if (root == null) {
            root = tryReadJson(sliceJson(raw));
        }
        if (root == null) {
            return out;
        }
        JsonNode arr = firstArray(root, "open_questions", "openQuestions", "questions");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n.isObject() && n.hasNonNull("title")) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 从模型正文里取出用例数组。
     * <p>
     * 模型常不听话：包一层 ```json 代码块、前面加一句「好的，以下是…」、
     * 或者直接返回顶层数组。这些都不该让整次生成白跑——重来一次要几十秒，还要再花一次钱。
     */
    List<JsonNode> parseCases(String raw) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode root = tryReadJson(stripFence(raw));
        if (root == null) {
            root = tryReadJson(sliceJson(raw));
        }
        if (root == null) {
            return out;
        }
        JsonNode arr = root.isArray() ? root : firstArray(root, "test_cases", "testCases", "cases", "data");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        int idx = 1;
        for (JsonNode n : arr) {
            if (!n.isObject()) {
                continue;
            }
            ObjectNode c = ((ObjectNode) n).deepCopy();
            if (!c.hasNonNull("title")) {
                continue;
            }
            if (!c.hasNonNull("case_id")) {
                c.put("case_id", String.format("TC-%03d", idx));
            }
            // steps 缺了会被落库层直接拒掉，补个空数组让这条还能进来让人改
            if (!c.has("steps") || !c.get("steps").isArray()) {
                c.putArray("steps");
            }
            out.add(c);
            idx++;
            if (out.size() >= MAX_CASES) {
                break;
            }
        }
        return out;
    }

    private static JsonNode firstArray(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.path(k);
            if (n.isArray()) {
                return n;
            }
            // 有时会多包一层 {data:{test_cases:[...]}}
            if (n.isObject()) {
                JsonNode nested = firstArray(n, "test_cases", "testCases", "cases");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JsonNode tryReadJson(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 ```json ... ``` 围栏 */
    static String stripFence(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int nl = s.indexOf('\n');
        if (nl < 0) {
            return s;
        }
        s = s.substring(nl + 1);
        int end = s.lastIndexOf("```");
        return end >= 0 ? s.substring(0, end).trim() : s.trim();
    }

    /** 正文里混了解释文字时，截出第一个 { 到最后一个 } */
    static String sliceJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            start = raw.indexOf('[');
            end = raw.lastIndexOf(']');
        }
        return start >= 0 && end > start ? raw.substring(start, end + 1) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
