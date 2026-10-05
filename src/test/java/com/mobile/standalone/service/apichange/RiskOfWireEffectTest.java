package com.mobile.standalone.service.apichange;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险分档:<b>「可能不兼容」这个桶到底装什么</b>。
 *
 * <p>{@link FieldTypeChangeTest} 测的是单条类型变更怎么判,这里测的是
 * 那个判断有没有真的接到报告上——两者分开是因为中间那一步
 * ({@code riskOf} 里的 {@code fieldBreaking})是最容易被改回去的:
 * 它看起来只是一个 {@code anyMatch} 的简化。
 *
 * <p>实测背景:一次提测报出 79 条「可能不兼容」,
 * 里面真正要去问开发的只有 1 条,其余是一个 DTO 整体换包装类型。
 */
class RiskOfWireEffectTest {

    private static FieldChange typeChanged(String name, String from, String to) {
        FieldChange f = new FieldChange();
        f.setChangeType("TYPE_CHANGED");
        f.setName(name);
        f.setOldType(from);
        f.setNewType(to);
        return f;
    }

    private static EndpointChange endpoint(List<FieldChange> fields) {
        EndpointChange e = new EndpointChange();
        e.setChangeType("MODIFIED");
        e.setHttpMethod("GET");
        e.setPath("/x");
        e.setFields(fields);
        return e;
    }

    /** 这条就是那 79 条噪音的来源 */
    @Test
    @DisplayName("整个 DTO 换成包装类型,不该让这个接口变成「可能不兼容」")
    void 装箱不算不兼容() {
        var e = endpoint(List.of(
                typeChanged("kills", "int", "Integer"),
                typeChanged("deaths", "int", "Integer"),
                typeChanged("assists", "int", "Integer")));
        assertThat(ApiChangeAnalyzer.riskOf(e))
                .as("JSON 值一个字都没变，占着「先确认这些」最显眼的位置，"
                        + "会把真正该看的那条挤下去")
                .isNotEqualTo("BREAKING");
    }

    @Test
    @DisplayName("结构真的变了就必须是 BREAKING —— 降噪不能把真问题降没")
    void 结构变更仍是不兼容() {
        assertThat(ApiChangeAnalyzer.riskOf(endpoint(List.of(
                typeChanged("tooltips", "JSONArray", "JSONObject")))))
                .isEqualTo("BREAKING");
        assertThat(ApiChangeAnalyzer.riskOf(endpoint(List.of(
                typeChanged("id", "long", "String")))))
                .isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("一堆装箱里混着一条结构变更,整体仍是 BREAKING")
    void 混合时按最高档() {
        var e = endpoint(List.of(
                typeChanged("kills", "int", "Integer"),
                typeChanged("deaths", "int", "Integer"),
                typeChanged("tooltips", "JSONArray", "JSONObject")));
        assertThat(ApiChangeAnalyzer.riskOf(e))
                .as("这正是截图里那个 DTO 的形状——那一条不能被 23 条装箱稀释掉")
                .isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("删字段一直都是 BREAKING,和类型判定无关")
    void 删字段() {
        FieldChange f = new FieldChange();
        f.setChangeType("REMOVED");
        f.setName("gone");
        assertThat(ApiChangeAnalyzer.riskOf(endpoint(List.of(f)))).isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("判不出来的类型变更仍算 BREAKING")
    void 判不出来仍算不兼容() {
        assertThat(ApiChangeAnalyzer.riskOf(endpoint(List.of(
                typeChanged("payload", "Object", "String")))))
                .as("降噪最危险的失败方式是降过头")
                .isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("入参类型变更同样按 JSON 线上的后果判")
    void 入参也适用() {
        EndpointChange boxed = endpoint(List.of());
        ParamChange p1 = new ParamChange();
        p1.setChangeType("TYPE_CHANGED");
        p1.setName("page");
        p1.setOldType("int");
        p1.setNewType("Integer");
        boxed.setParams(List.of(p1));
        assertThat(ApiChangeAnalyzer.riskOf(boxed)).isNotEqualTo("BREAKING");

        EndpointChange shape = endpoint(List.of());
        ParamChange p2 = new ParamChange();
        p2.setChangeType("TYPE_CHANGED");
        p2.setName("ids");
        p2.setOldType("String");
        p2.setNewType("List<Long>");
        shape.setParams(List.of(p2));
        assertThat(ApiChangeAnalyzer.riskOf(shape)).isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("新增必填入参仍是 BREAKING —— 老客户端直接 400")
    void 新增必填入参() {
        EndpointChange e = endpoint(List.of());
        ParamChange p = new ParamChange();
        p.setChangeType("ADDED");
        p.setName("token");
        p.setRequired(true);
        e.setParams(List.of(p));
        assertThat(ApiChangeAnalyzer.riskOf(e)).isEqualTo("BREAKING");
    }

    @Test
    @DisplayName("新接口不参与这个判断 —— 它的入参当然全是新增必填")
    void 新接口不算不兼容() {
        EndpointChange e = endpoint(List.of(typeChanged("a", "JSONArray", "JSONObject")));
        e.setChangeType("ADDED");
        assertThat(ApiChangeAnalyzer.riskOf(e)).isEqualTo("NEW");
    }
}
