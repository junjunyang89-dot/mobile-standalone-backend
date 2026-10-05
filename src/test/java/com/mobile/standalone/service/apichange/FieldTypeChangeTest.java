package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型变更按「JSON 线上的实际后果」分档。
 *
 * <p>这组测试守的是一对<b>方向相反</b>的错,而且两边的代价差很远:
 * <ul>
 *   <li><b>误报</b>:把兼容的说成不兼容 → 一次提测 79 条噪音,人就不看这份报告了</li>
 *   <li><b>漏报</b>:把真的破坏性变更降级 → 它从报告里消失,上线后客户端解析失败</li>
 * </ul>
 * 这次改动的目的是降噪,所以<b>最危险的失败方式是降过头</b>——
 * 因此判不出来的一律留在不兼容档。
 */
class FieldTypeChangeTest {

    // ---------- 真正的破坏性变更,一条都不能漏 ----------

    /** 截图里那条真问题 */
    @Test
    @DisplayName("JSONArray → JSONObject:[...] 变成 {...},客户端必崩")
    void 数组变对象() {
        var e = FieldTypeChange.classify("JSONArray", "JSONObject");
        assertThat(e).isEqualTo(FieldTypeChange.SHAPE);
        assertThat(e.breaking()).isTrue();
    }

    @Test
    @DisplayName("集合 ↔ 标量 也是结构变了")
    void 集合变标量() {
        assertThat(FieldTypeChange.classify("List<String>", "String")).isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("String", "List<String>")).isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("String[]", "String")).isEqualTo(FieldTypeChange.SHAPE);
    }

    @Test
    @DisplayName("数字 ↔ 字符串:JSON 里 5 和 \"5\" 不是一回事")
    void 数字变字符串() {
        assertThat(FieldTypeChange.classify("long", "String"))
                .as("long→String 是修 JS 精度丢失的常见做法，而它一定是破坏性的")
                .isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("String", "Integer")).isEqualTo(FieldTypeChange.SHAPE);
    }

    @Test
    @DisplayName("数值变窄会溢出,不能算成兼容")
    void 数值变窄() {
        assertThat(FieldTypeChange.classify("long", "int")).isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("Long", "Integer")).isEqualTo(FieldTypeChange.SHAPE);
    }

    /** 这条很容易被当成「和 int→Integer 一样无害」 */
    @Test
    @DisplayName("Integer → int 不是无害的反向操作 —— 原来可能是 null,现在不可能了")
    void 拆箱不是无害的() {
        assertThat(FieldTypeChange.classify("Integer", "int"))
                .as("字段值的集合变了（null 不见了），调用方对 null 分支的处理会变成死代码，"
                        + "而后端那边 null 会变成 0 或者直接抛")
                .isEqualTo(FieldTypeChange.SHAPE);
    }

    @Test
    @DisplayName("boolean 和别的类型互换")
    void 布尔互换() {
        assertThat(FieldTypeChange.classify("boolean", "String")).isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("Integer", "Boolean")).isEqualTo(FieldTypeChange.SHAPE);
    }

    // ---------- 只是可空性:这是 79 条噪音的来源 ----------

    @Test
    @DisplayName("int → Integer 只是可能为 null,JSON 值一模一样")
    void 装箱() {
        var e = FieldTypeChange.classify("int", "Integer");
        assertThat(e).isEqualTo(FieldTypeChange.NULLABILITY);
        assertThat(e.breaking())
                .as("截图里 DeadlockMatchPlayerDetailDTO 一次 23 个字段都是这个，"
                        + "全算不兼容的话，真正该看的那条 tooltips 会被淹掉")
                .isFalse();
    }

    @Test
    @DisplayName("其它基本类型装箱同理")
    void 装箱的其它情况() {
        assertThat(FieldTypeChange.classify("long", "Long")).isEqualTo(FieldTypeChange.NULLABILITY);
        assertThat(FieldTypeChange.classify("boolean", "Boolean")).isEqualTo(FieldTypeChange.NULLABILITY);
        assertThat(FieldTypeChange.classify("double", "Double")).isEqualTo(FieldTypeChange.NULLABILITY);
    }

    @Test
    @DisplayName("可空性的文案要让人知道该做什么")
    void 可空性文案() {
        assertThat(FieldTypeChange.NULLABILITY.describe())
                .contains("null")
                .as("只说「可能为 null」人不知道要干嘛，要点出去确认调用方")
                .contains("调用方");
    }

    // ---------- 范围变大 ----------

    @Test
    @DisplayName("int → long 范围变大,一般兼容")
    void 变宽() {
        assertThat(FieldTypeChange.classify("int", "long")).isEqualTo(FieldTypeChange.WIDENING);
        assertThat(FieldTypeChange.classify("Integer", "Long")).isEqualTo(FieldTypeChange.WIDENING);
        assertThat(FieldTypeChange.classify("float", "double")).isEqualTo(FieldTypeChange.WIDENING);
        assertThat(FieldTypeChange.WIDENING.breaking()).isFalse();
    }

    // ---------- 泛型 ----------

    @Test
    @DisplayName("集合里面的类型按同一套规则判")
    void 泛型内部() {
        assertThat(FieldTypeChange.classify("List<Integer>", "List<Long>"))
                .isEqualTo(FieldTypeChange.WIDENING);
        assertThat(FieldTypeChange.classify("List<Integer>", "List<String>"))
                .isEqualTo(FieldTypeChange.SHAPE);
        assertThat(FieldTypeChange.classify("List<Foo>", "Set<Foo>"))
                .as("List 和 Set 序列化出来都是数组")
                .isEqualTo(FieldTypeChange.SAME);
    }

    // ---------- 判不出来的必须留在不兼容档 ----------

    /** 整个改动最要紧的一条:降噪不能把真问题降没 */
    @Test
    @DisplayName("判不出来的算 breaking —— 降噪的危险失败方式是降过头")
    void 判不出来要保守() {
        assertThat(FieldTypeChange.UNKNOWN.breaking())
                .as("改成 false 的话，所有看不透的类型变更会从报告里消失，"
                        + "而这正是这次改动最危险的失败方式")
                .isTrue();
        assertThat(FieldTypeChange.SHAPE.breaking()).isTrue();
    }

    @Test
    @DisplayName("Object / JsonNode 看不透 —— 序列化成什么取决于运行时的值")
    void 看不透的类型() {
        assertThat(FieldTypeChange.classify("Object", "String")).isEqualTo(FieldTypeChange.UNKNOWN);
        assertThat(FieldTypeChange.classify("JsonNode", "Integer")).isEqualTo(FieldTypeChange.UNKNOWN);
    }

    @Test
    @DisplayName("日期类型互换 —— 时间戳还是 ISO 字符串取决于全局配置,静态判不了")
    void 日期类型() {
        assertThat(FieldTypeChange.classify("Date", "LocalDateTime")).isEqualTo(FieldTypeChange.UNKNOWN);
        assertThat(FieldTypeChange.classify("LocalDateTime", "String")).isEqualTo(FieldTypeChange.UNKNOWN);
    }

    @Test
    @DisplayName("两个自定义类型互换 —— 结构大概率变了,但判不准")
    void 自定义类型() {
        assertThat(FieldTypeChange.classify("UserVo", "UserDetailVo")).isEqualTo(FieldTypeChange.UNKNOWN);
    }

    @Test
    @DisplayName("空值不炸,而且倒向保守")
    void 空输入() {
        assertThat(FieldTypeChange.classify(null, "int")).isEqualTo(FieldTypeChange.UNKNOWN);
        assertThat(FieldTypeChange.classify("int", null)).isEqualTo(FieldTypeChange.UNKNOWN);
        assertThat(FieldTypeChange.classify("  ", "  ")).isEqualTo(FieldTypeChange.UNKNOWN);
    }

    // ---------- 无影响 ----------

    @Test
    @DisplayName("完全一样、带包名、带空格,都算无影响")
    void 等价写法() {
        assertThat(FieldTypeChange.classify("Integer", "Integer")).isEqualTo(FieldTypeChange.SAME);
        assertThat(FieldTypeChange.classify("java.lang.Integer", "Integer"))
                .as("带不带包名是同一个类型")
                .isEqualTo(FieldTypeChange.SAME);
        assertThat(FieldTypeChange.classify("List< String >", "List<String>"))
                .isEqualTo(FieldTypeChange.SAME);
    }

    @Test
    @DisplayName("SAME 和 WIDENING 都不进不兼容档")
    void 不进不兼容档() {
        assertThat(FieldTypeChange.SAME.breaking()).isFalse();
        assertThat(FieldTypeChange.WIDENING.breaking()).isFalse();
        assertThat(FieldTypeChange.NULLABILITY.breaking()).isFalse();
    }

    @Test
    @DisplayName("每个档都有标签和说明")
    void 文案齐全() {
        for (FieldTypeChange e : FieldTypeChange.values()) {
            assertThat(e.label()).as("%s 没标签", e).isNotBlank();
            assertThat(e.describe()).as("%s 没说明", e).isNotBlank();
        }
    }
}
