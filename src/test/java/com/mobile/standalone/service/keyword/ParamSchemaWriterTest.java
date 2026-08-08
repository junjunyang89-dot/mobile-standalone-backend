package com.mobile.standalone.service.keyword;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * param_schema 是前端渲染步骤表单的唯一数据源，字段名和历史格式必须保持一致。
 */
class ParamSchemaWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode parse(List<ParamDef> params) throws Exception {
        return JSON.readTree(ParamSchemaWriter.toJson(params));
    }

    @Test
    void 空参数序列化成空数组() {
        assertEquals("[]", ParamSchemaWriter.toJson(List.of()));
        assertEquals("[]", ParamSchemaWriter.toJson(null));
    }

    @Test
    void 基本字段与历史格式一致() throws Exception {
        JsonNode arr = parse(List.of(ParamDef.requiredString("flowId", "组件流程ID")));

        assertEquals(1, arr.size());
        JsonNode n = arr.get(0);
        assertEquals("flowId", n.get("name").asText());
        assertEquals("组件流程ID", n.get("label").asText());
        assertEquals("string", n.get("type").asText());
        assertTrue(n.get("required").asBoolean());
    }

    @Test
    void 类型名输出小写() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.element("elementKey", "元素"),
                ParamDef.number("timeoutSec", "超时秒", 10),
                ParamDef.bool("failIfNotFound", "未找到是否失败", true),
                ParamDef.steps("thenSteps", "存在时步骤"),
                ParamDef.text("note", "备注")));

        assertEquals("element", arr.get(0).get("type").asText());
        assertEquals("number", arr.get(1).get("type").asText());
        assertEquals("boolean", arr.get(2).get("type").asText());
        assertEquals("steps", arr.get(3).get("type").asText());
        assertEquals("textarea", arr.get(4).get("type").asText());
    }

    @Test
    void 默认值保留原始类型() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.number("timeoutSec", "超时秒", 10),
                ParamDef.bool("failIfNotFound", "未找到", true),
                ParamDef.enumOf("direction", "方向", List.of("up", "down"), "up")));

        assertTrue(arr.get(0).get("default").isNumber());
        assertEquals(10, arr.get(0).get("default").asInt());
        assertTrue(arr.get(1).get("default").isBoolean());
        assertTrue(arr.get(1).get("default").asBoolean());
        assertEquals("up", arr.get(2).get("default").asText());
    }

    @Test
    void 无默认值时不输出default字段() throws Exception {
        JsonNode arr = parse(List.of(ParamDef.string("text", "输入内容")));

        assertFalse(arr.get(0).has("default"));
    }

    @Test
    void 枚举输出options数组() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.enumOf("clickMethod", "点击方式", List.of("AUTO", "ELEMENT", "ADB"), "AUTO")));

        JsonNode options = arr.get(0).get("options");
        assertTrue(options.isArray());
        assertEquals(3, options.size());
        assertEquals("AUTO", options.get(0).asText());
    }

    @Test
    void 非枚举不输出options() throws Exception {
        JsonNode arr = parse(List.of(ParamDef.string("text", "输入内容")));

        assertFalse(arr.get(0).has("options"));
    }

    @Test
    void help有值才输出() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.string("packageName", "包名").withHelp("留空则取应用字典"),
                ParamDef.string("activity", "Activity")));

        assertEquals("留空则取应用字典", arr.get(0).get("help").asText());
        assertFalse(arr.get(1).has("help"));
    }

    // ==================== 显示条件 ====================

    /**
     * 显示条件写在后端，是为了保住「后端是参数唯一来源、新增关键字前端零改动」这条线；
     * 一旦下沉到前端 v-if，两边立刻开始漂移——历史上就是这么漂的。
     */
    @Test
    void 显示条件序列化成showWhen() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.bool("assertChecked", "点后断言已勾选", false)
                        .showWhen("clickMethod", "ELEMENT")));

        JsonNode cond = arr.get(0).get("showWhen");
        assertEquals("clickMethod", cond.get("param").asText());
        assertEquals(1, cond.get("values").size());
        assertEquals("ELEMENT", cond.get("values").get(0).asText());
    }

    /** 布尔条件也按字符串下发，前端一律 String 比较，省掉 true/"true" 两套判断 */
    @Test
    void 布尔条件也写成字符串() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.element("fixElementKey", "不符时点哪个元素").showWhen("fixByClick", true)));

        assertEquals("true", arr.get(0).get("showWhen").get("values").get(0).asText());
    }

    @Test
    void 没有条件的参数不输出showWhen() throws Exception {
        JsonNode arr = parse(List.of(ParamDef.bool("uninstallFirst", "安装前卸载", false)));

        assertFalse(arr.get(0).has("showWhen"));
    }

    /** withHelp / optional 会重建记录，别把条件弄丢了 */
    @Test
    void 条件在withHelp与optional之后仍保留() throws Exception {
        JsonNode arr = parse(List.of(
                ParamDef.element("fixElementKey", "不符时点哪个元素")
                        .showWhen("fixByClick", true)
                        .optional()
                        .withHelp("留空则点自己")));

        assertFalse(arr.get(0).get("required").asBoolean());
        assertEquals("fixByClick", arr.get(0).get("showWhen").get("param").asText());
    }
}
