package com.mobile.standalone.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 元素引用扫描的单测。
 *
 * <p>这组测试守的是两个方向相反的错,而且后者严重得多:
 * <ul>
 *   <li><b>假命中</b>:一个其实没人用的元素删不掉,报错说「有 3 条流程在用」却对不上——烦人</li>
 *   <li><b>假放过</b>:漏掉分支里的引用,删掉之后别人几十条流程下一次跑全红,
 *       报错是「找不到元素」,没人会联想到是有人删了个元素——要命</li>
 * </ul>
 */
class FlowStepScannerTest {

    @Test
    @DisplayName("顶层步骤的元素引用")
    void 顶层引用() {
        String steps = """
                [{"keywordCode":"CLICK","elementKey":"btn_login"},
                 {"keywordCode":"INPUT","elementKey":"input_phone"}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps))
                .containsExactlyInAnyOrder("btn_login", "input_phone");
    }

    @Test
    @DisplayName("早期步骤用的是 element 字段,也要认")
    void 兼容旧字段名() {
        String steps = """
                [{"keywordCode":"CLICK","element":"btn_old_style"}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps)).containsExactly("btn_old_style");
    }

    // ---------- 假放过:最要命的方向 ----------

    @Test
    @DisplayName("IF 分支里的引用不能漏")
    void 分支里的引用() {
        String steps = """
                [{"keywordCode":"IF_BRANCH",
                  "condition":{"elementKey":"tip_popup"},
                  "thenSteps":[{"keywordCode":"CLICK","elementKey":"btn_close_popup"}],
                  "elseSteps":[{"keywordCode":"CLICK","elementKey":"btn_continue"}]}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps))
                .as("漏掉分支里的元素,删除后那几条流程会在执行时报「找不到元素」")
                .contains("tip_popup", "btn_close_popup", "btn_continue");
    }

    @Test
    @DisplayName("多层嵌套(分支里套循环里套分支)也要扫到")
    void 深层嵌套() {
        String steps = """
                [{"keywordCode":"IF_BRANCH","thenSteps":[
                    {"keywordCode":"LOOP","steps":[
                        {"keywordCode":"IF_BRANCH","thenSteps":[
                            {"keywordCode":"CLICK","elementKey":"btn_deep"}]}]}]}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps)).contains("btn_deep");
    }

    @Test
    @DisplayName("任意新字段名下的子步骤也能扫到——这正是整树扫的价值")
    void 未知结构里的子步骤() {
        // 假设将来加了一个「竞速」关键字,子步骤放在 racers 里。
        // 逐层解析的写法会漏掉它,而且加关键字的人不会意识到自己弄坏了删除护栏
        String steps = """
                [{"keywordCode":"RACE","racers":[
                    {"branchA":[{"elementKey":"btn_a"}]},
                    {"branchB":[{"elementKey":"btn_b"}]}]}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps)).contains("btn_a", "btn_b");
    }

    // ---------- 假命中 ----------

    @Test
    @DisplayName("前缀相同的 key 不能算成引用")
    void 前缀不算引用() {
        String steps = """
                [{"keywordCode":"CLICK","elementKey":"btn_ok_cancel"}]
                """;
        assertThat(FlowStepScanner.referencesElement(steps, "btn_ok"))
                .as("btn_ok_cancel 被当成了 btn_ok 的引用——用 contains 匹配就会这样")
                .isFalse();
        assertThat(FlowStepScanner.referencesElement(steps, "btn_ok_cancel")).isTrue();
    }

    @Test
    @DisplayName("元素 key 出现在别的字段里不算引用")
    void 别的字段里的同名字符串不算引用() {
        // 步骤备注里提到了元素名,不代表这一步在用它
        String steps = """
                [{"keywordCode":"WAIT","remark":"等 btn_login 出现后再继续","seconds":3}]
                """;
        assertThat(FlowStepScanner.referencesElement(steps, "btn_login"))
                .as("只有 elementKey / element 字段才算引用,备注里提一句不算")
                .isFalse();
    }

    @Test
    @DisplayName("两边的空白不影响比较")
    void 空白归一() {
        String steps = """
                [{"elementKey":"  btn_login  "}]
                """;
        assertThat(FlowStepScanner.referencesElement(steps, "btn_login")).isTrue();
        assertThat(FlowStepScanner.referencesElement(steps, "  btn_login ")).isTrue();
    }

    // ---------- 边界 ----------

    @Test
    @DisplayName("空步骤 / null 不报错")
    void 空输入() {
        assertThat(FlowStepScanner.elementKeys(null)).isEmpty();
        assertThat(FlowStepScanner.elementKeys("")).isEmpty();
        assertThat(FlowStepScanner.elementKeys("[]")).isEmpty();
        assertThat(FlowStepScanner.referencesElement(null, "x")).isFalse();
        assertThat(FlowStepScanner.referencesElement("[]", null)).isFalse();
        assertThat(FlowStepScanner.referencesElement("[]", "")).isFalse();
    }

    @Test
    @DisplayName("脏 JSON 不抛异常,但要能被识别出来")
    void 脏数据() {
        String bad = "[{not json";
        assertThat(FlowStepScanner.elementKeys(bad)).isEmpty();
        assertThat(FlowStepScanner.isParsable(bad))
                .as("「没有引用」和「没扫出来」必须能分清——"
                        + "把后者当成前者然后放行删除,正是这套护栏想防的事")
                .isFalse();
    }

    @Test
    @DisplayName("空步骤算合法,不是脏数据")
    void 空步骤不算脏数据() {
        assertThat(FlowStepScanner.isParsable(null)).isTrue();
        assertThat(FlowStepScanner.isParsable("")).isTrue();
        assertThat(FlowStepScanner.isParsable("[]")).isTrue();
    }

    @Test
    @DisplayName("重复引用只算一次")
    void 去重() {
        String steps = """
                [{"elementKey":"btn_x"},{"elementKey":"btn_x"},{"elementKey":"btn_y"}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps)).containsExactlyInAnyOrder("btn_x", "btn_y");
    }

    @Test
    @DisplayName("子组件引用单独取")
    void 组件引用() {
        String steps = """
                [{"keywordCode":"CALL_COMPONENT","componentId":"uif-login"},
                 {"keywordCode":"CLICK","elementKey":"btn_next"}]
                """;
        assertThat(FlowStepScanner.componentIds(steps)).containsExactly("uif-login");
        assertThat(FlowStepScanner.elementKeys(steps)).containsExactly("btn_next");
    }

    @Test
    @DisplayName("非文本值不算引用——elementKey 写成数字或 null 都不该进结果")
    void 非文本值忽略() {
        String steps = """
                [{"elementKey":123},{"elementKey":null},{"elementKey":""},{"elementKey":"btn_real"}]
                """;
        assertThat(FlowStepScanner.elementKeys(steps)).containsExactly("btn_real");
    }
}
