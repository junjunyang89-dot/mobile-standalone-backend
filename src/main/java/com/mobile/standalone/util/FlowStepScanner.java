package com.mobile.standalone.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 扫流程步骤 JSON,取出它引用了哪些元素 / 哪些子组件。
 *
 * <p><b>为什么要整树扫,而不是逐层解析</b>:步骤结构是嵌套且多变的——
 * IF 分支、循环、竞速、组件调用,各自把子步骤放在不同的字段里。
 * 逐层解析就得跟着每个关键字的结构走,加一个新关键字就漏一处;
 * 按字段名整树扫,新关键字只要还用 {@code elementKey} 这个字段名就自动覆盖到。
 * (这套做法本来在 {@code UiFlowService} 里是私有的,提出来是为了让元素删除/改名
 * 也能用同一份逻辑,顺便能单测。)
 *
 * <p><b>为什么不能用字符串 contains</b>:那会同时犯两种错。
 * 找 {@code btn_ok} 时 {@code btn_ok_cancel} 会被算成引用(<b>假命中</b>,
 * 导致一个其实没人用的元素删不掉,而且报错说"有 3 条流程在用"完全对不上);
 * 反过来,只扫顶层数组会漏掉分支里的步骤(<b>假放过</b>,
 * 那才是真正要命的——删掉之后别人几十条流程下一次跑全红,报错是"找不到元素")。
 * 所以必须解析 JSON、按字段名精确取值。
 */
public final class FlowStepScanner {

    /** 元素引用可能落在这两个字段名下:新步骤用 elementKey,早期步骤用 element */
    private static final String[] ELEMENT_FIELDS = {"elementKey", "element"};

    /** 子流程引用:组件调用用 componentId,依赖用 flowId */
    private static final String[] COMPONENT_FIELDS = {"componentId", "flowId"};

    private FlowStepScanner() {
    }

    /** 这段步骤 JSON 里引用到的所有元素 key(含嵌套分支);解析不了返回空集 */
    public static Set<String> elementKeys(String stepsJson) {
        return valuesOf(stepsJson, ELEMENT_FIELDS);
    }

    /** 这段步骤 JSON 里引用到的所有子流程 id */
    public static Set<String> componentIds(String stepsJson) {
        return valuesOf(stepsJson, COMPONENT_FIELDS);
    }

    /**
     * 这段步骤有没有引用这个元素 key。
     *
     * <p>精确相等比较,不做前缀/包含匹配——理由见类注释。
     */
    public static boolean referencesElement(String stepsJson, String elementKey) {
        if (elementKey == null || elementKey.isBlank()) {
            return false;
        }
        return elementKeys(stepsJson).contains(elementKey.trim());
    }

    /**
     * 按字段名整树取值。
     *
     * <p>解析失败返回<b>空集</b>而不是抛异常:这个扫描用在"删之前检查一下"的位置,
     * 一条脏数据不该让整个删除操作失败。
     *
     * <p>但这也意味着<b>脏 JSON 会被当成"没有引用"</b>——调用方如果要拿它做安全拦截,
     * 得自己想清楚这个口子。{@link #isParsable} 就是给这种场景准备的。
     */
    private static Set<String> valuesOf(String json, String[] fields) {
        Set<String> out = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = ExecutionLogJson.mapper().readTree(json);
            Deque<JsonNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                JsonNode n = stack.pop();
                if (n.isObject()) {
                    for (String field : fields) {
                        JsonNode v = n.get(field);
                        if (v != null && v.isTextual() && !v.asText().isBlank()) {
                            out.add(v.asText().trim());
                        }
                    }
                    n.fields().forEachRemaining(e -> stack.push(e.getValue()));
                } else if (n.isArray()) {
                    n.forEach(stack::push);
                }
            }
        } catch (Exception ignored) {
            // 扫不出来就当没有引用。调用方要区分「真没引用」和「没扫出来」就用 isParsable
        }
        return out;
    }

    /**
     * 这段步骤 JSON 解析得开吗。
     *
     * <p>存在的理由:{@link #elementKeys} 对脏 JSON 返回空集,
     * 于是"没有引用"和"没扫出来"长得一样。做安全拦截的地方必须能分清这两者——
     * 把"没扫出来"当成"没人用"然后放行删除,正是这套护栏想防的事。
     */
    public static boolean isParsable(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            // 空步骤是合法的(新建的空流程),不算脏数据
            return true;
        }
        try {
            ExecutionLogJson.mapper().readTree(stepsJson);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
