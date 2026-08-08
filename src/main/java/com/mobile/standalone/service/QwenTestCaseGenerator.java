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
        boolean hasText = text != null && !text.isBlank();
        boolean hasImages = images != null && !images.isEmpty();
        if (!hasText && !hasImages) {
            throw new IllegalArgumentException("请至少提供界面截图或需求说明");
        }
        String page = pageName == null || pageName.isBlank() ? "未命名页面" : pageName.trim();

        String raw = qwen.chat(prompt(page, hasText ? text.trim() : null, hasImages), images);
        List<JsonNode> cases = parseCases(raw);
        if (cases.isEmpty()) {
            log.warn("[qwen-testcase] 未解析出用例，模型原文前 500 字：{}", truncate(raw, 500));
            throw new IllegalStateException("模型没有返回可解析的用例，请补充需求说明或换一张更清晰的截图");
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("page", page);
        out.put("total", cases.size());
        out.put("model", qwen.modelId());
        ArrayNode arr = out.putArray("test_cases");
        cases.forEach(arr::add);
        return out;
    }

    String prompt(String pageName, String text, boolean hasImages) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深移动端测试工程师。请为下面这个 APP 页面设计功能测试用例。\n\n");
        sb.append("【页面/模块】").append(pageName).append('\n');
        if (hasImages) {
            sb.append("【界面截图】见随附图片。请依据图上真实存在的控件与文案设计用例，"
                    .concat("不要臆造截图里没有的入口或字段。\n"));
        }
        if (text != null) {
            sb.append("【需求/元素说明】\n").append(text).append('\n');
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
                  ]
                }
                """.formatted(MAX_CASES));
        return sb.toString();
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
