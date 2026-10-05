package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 返回体字段差异的归并。
 *
 * <p>守的是一句话:<b>一个要人做的决定 = 一行</b>,不是「一条字段差异 = 一行」。
 *
 * <p>同时守住归并的反向风险:<b>别把那条真问题合并进一个数字里</b>。
 * 截图里 79 条中唯一要动手的是 {@code tooltips JSONArray → JSONObject},
 * 如果它和 23 条装箱合成「24 个字段类型变了」,那这次改动就白做了。
 */
class DtoChangeRollupTest {

    private static ApiChangeAnalyzer.FieldChange f(String changeType, String name,
                                                   String oldType, String newType) {
        var c = new ApiChangeAnalyzer.FieldChange();
        c.setChangeType(changeType);
        c.setName(name);
        c.setOldType(oldType);
        c.setNewType(newType);
        if ("TYPE_CHANGED".equals(changeType)) {
            c.setWireEffect(FieldTypeChange.classify(oldType, newType).name());
        }
        return c;
    }

    private static DtoChangeRollup.Group pick(List<DtoChangeRollup.Group> gs, String effect) {
        return gs.stream().filter(g -> g.effect().equals(effect)).findFirst().orElse(null);
    }

    /** 这条就是截图里那个 DTO */
    @Test
    @DisplayName("23 条装箱 + 1 条结构变更 → 2 行,不是 24 行")
    void 截图里那个DTO() {
        List<ApiChangeAnalyzer.FieldChange> fields = new ArrayList<>();
        fields.add(f("TYPE_CHANGED", "tooltips", "JSONArray", "JSONObject"));
        for (String n : new String[]{"team", "kills", "deaths", "assists", "netWorth",
                "heroId", "lastHits", "denies", "assignedLane", "level", "maxHealth",
                "playerDamage", "bossDamage", "playerHealing", "dps", "techPower",
                "playerDamageTaken", "playerDamagePercent", "playerDamageTakenPercent",
                "netWorthPercent", "playerHealingPercent", "bossDamagePercent", "lastHitsPercent"}) {
            fields.add(f("TYPE_CHANGED", n, "int", "Integer"));
        }

        var gs = DtoChangeRollup.of("DeadlockMatchPlayerDetailDTO", fields);

        assertThat(gs).as("24 条差异应该收成 2 组").hasSize(2);
        assertThat(pick(gs, "SHAPE").count()).isEqualTo(1);
        assertThat(pick(gs, "SHAPE").fields()).containsExactly("tooltips");
        assertThat(pick(gs, "NULLABILITY").count()).isEqualTo(23);
    }

    @Test
    @DisplayName("真问题排在最前,而且不折叠")
    void 真问题优先且不折叠() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("TYPE_CHANGED", "a", "int", "Integer"),
                f("TYPE_CHANGED", "tooltips", "JSONArray", "JSONObject")));
        assertThat(gs.get(0).effect())
                .as("要人动手的必须排第一，否则人滚到下面才看见")
                .isEqualTo("SHAPE");
        assertThat(gs.get(0).collapse()).isFalse();
    }

    @Test
    @DisplayName("结构变更一条都不合并 —— 每条都要人单独去确认")
    void 结构变更逐条列() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("TYPE_CHANGED", "a", "JSONArray", "JSONObject"),
                f("TYPE_CHANGED", "b", "long", "String"),
                f("TYPE_CHANGED", "c", "List<Foo>", "Foo")));
        var g = pick(gs, "SHAPE");
        assertThat(g.count()).isEqualTo(3);
        assertThat(g.fields()).containsExactly("a", "b", "c");
        assertThat(g.collapse())
                .as("合并成「3 个字段结构变了」人还得展开看是哪三个，省不了事")
                .isFalse();
    }

    /** 归并的反向风险 */
    @Test
    @DisplayName("绝不能把 SHAPE 和 NULLABILITY 合成一组")
    void 不能混成一组() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("TYPE_CHANGED", "tooltips", "JSONArray", "JSONObject"),
                f("TYPE_CHANGED", "kills", "int", "Integer")));
        assertThat(gs).hasSize(2);
        assertThat(pick(gs, "SHAPE")).isNotNull();
        assertThat(pick(gs, "NULLABILITY")).isNotNull();
        assertThat(pick(gs, "SHAPE").breaking()).isTrue();
        assertThat(pick(gs, "NULLABILITY").breaking())
                .as("JSON 值没变，不该占「可能不兼容」的位置")
                .isFalse();
    }

    @Test
    @DisplayName("没算过 wireEffect 的按判不出处理,不能当成无影响")
    void 空wireEffect要保守() {
        var c = new ApiChangeAnalyzer.FieldChange();
        c.setChangeType("TYPE_CHANGED");
        c.setName("x");
        c.setOldType("Foo");
        c.setNewType("Bar");
        // 故意不设 wireEffect —— 模拟将来有人加了条新路径忘了算
        var gs = DtoChangeRollup.of("X", List.of(c));
        assertThat(gs.get(0).effect())
                .as("当成 SAME 的话，漏算会让真变更静默消失")
                .isEqualTo("UNKNOWN");
        assertThat(gs.get(0).breaking()).isTrue();
    }

    @Test
    @DisplayName("删字段要人动手,排在最前")
    void 删字段() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("ADDED", "a", null, "String"),
                f("REMOVED", "b", "String", null)));
        assertThat(gs.get(0).effect()).isEqualTo("REMOVED");
        assertThat(gs.get(0).breaking()).isTrue();
        assertThat(pick(gs, "ADDED").breaking()).isFalse();
    }

    @Test
    @DisplayName("单个字段时文案要带字段名,不要说「1 个字段」")
    void 单条文案() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("TYPE_CHANGED", "tooltips", "JSONArray", "JSONObject")));
        assertThat(gs.get(0).headline()).contains("tooltips");
    }

    @Test
    @DisplayName("一批装箱的文案要点出「像一次整体重构」")
    void 整体重构提示() {
        List<ApiChangeAnalyzer.FieldChange> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(f("TYPE_CHANGED", "f" + i, "int", "Integer"));
        }
        assertThat(DtoChangeRollup.of("X", fs).get(0).headline())
                .contains("重构");
    }

    @Test
    @DisplayName("要人动手的组必须给出动作,不要只说发生了什么")
    void 动作不能空() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("TYPE_CHANGED", "a", "JSONArray", "JSONObject"),
                f("REMOVED", "b", "String", null),
                f("TYPE_CHANGED", "c", "int", "Integer")));
        for (var g : gs) {
            if (g.breaking() || g.effect().equals("NULLABILITY")) {
                assertThat(g.action()).as("%s 没给动作", g.effect()).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("顺序固定 —— 两次跑出来栏目顺序不一样的话人没法对比")
    void 顺序固定() {
        var gs = DtoChangeRollup.of("X", List.of(
                f("ADDED", "a", null, "String"),
                f("TYPE_CHANGED", "b", "int", "Integer"),
                f("TYPE_CHANGED", "c", "JSONArray", "JSONObject"),
                f("REMOVED", "d", "String", null)));
        assertThat(gs).extracting(DtoChangeRollup.Group::effect)
                .containsExactly("REMOVED", "SHAPE", "NULLABILITY", "ADDED");
    }

    @Test
    @DisplayName("空输入不炸")
    void 空输入() {
        assertThat(DtoChangeRollup.of("X", null)).isEmpty();
        assertThat(DtoChangeRollup.of("X", List.of())).isEmpty();
        var withNull = new ArrayList<ApiChangeAnalyzer.FieldChange>();
        withNull.add(null);
        withNull.add(f("ADDED", "a", null, "String"));
        assertThat(DtoChangeRollup.of("X", withNull)).hasSize(1);
    }
}
