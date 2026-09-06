package com.mobile.standalone.util;

import com.mobile.standalone.util.TextAssertion.Mode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 判定逻辑错了，后果是「所有用例都在骗你」——绿的未必真对。
 * 这段是最不该只靠肉眼 review 的代码。
 */
class TextAssertionTest {

    /**
     * 这条是加 mode 的全部理由。
     * <p>
     * 旧实现只有 contains：断言「余额 100」，页面显示「余额 1000」时<b>照样通过</b>。
     * 金额、数量、比分、倒计时全中招，而且是静默的——用例绿着，缺陷溜过去了。
     */
    @Test
    void contains会放过数值前缀而equals不会() {
        assertThat(TextAssertion.matches(Mode.CONTAINS, "余额 1000", "余额 100")).isTrue();
        assertThat(TextAssertion.matches(Mode.EQUALS, "余额 1000", "余额 100")).isFalse();
        assertThat(TextAssertion.matches(Mode.EQUALS, "余额 100", "余额 100")).isTrue();
    }

    /** 控件文案常带首尾空白和换行；不 trim 的话 equals 几乎永远失败，人就会退回去用 contains */
    @Test
    void equals两边都trim() {
        assertThat(TextAssertion.matches(Mode.EQUALS, "  已登录\n", "已登录")).isTrue();
        assertThat(TextAssertion.matches(Mode.EQUALS, "已登录", "  已登录  ")).isTrue();
        // 但中间的空白是内容的一部分，不能吞
        assertThat(TextAssertion.matches(Mode.EQUALS, "已 登录", "已登录")).isFalse();
    }

    @Test
    void 正则是查找不是全匹配() {
        assertThat(TextAssertion.matches(Mode.REGEX, "余额 1000 元", "\\d+")).isTrue();
        assertThat(TextAssertion.matches(Mode.REGEX, "余额 1000 元", "^余额 \\d{3}$")).isFalse();
        assertThat(TextAssertion.matches(Mode.REGEX, "余额 1000 元", "^余额 \\d{4} 元$")).isTrue();
    }

    /** 正则写错和断言不通过是两件事：一个要改用例，一个可能是缺陷，不能混成同一种失败 */
    @Test
    void 正则语法错误单独报() {
        assertThatThrownBy(() -> TextAssertion.matches(Mode.REGEX, "任意文案", "[未闭合"))
                .isInstanceOf(TextAssertion.BadPatternException.class);
    }

    @Test
    void 不包含用于断言没有报错文案() {
        assertThat(TextAssertion.matches(Mode.NOT_CONTAINS, "加载完成", "网络异常")).isTrue();
        assertThat(TextAssertion.matches(Mode.NOT_CONTAINS, "网络异常，请重试", "网络异常")).isFalse();
    }

    /**
     * 读不到文案时：除了「不包含」之外一律判失败。
     * 「不包含」通过是刻意的——元素上没有文案，自然也就不含那段错误提示。
     */
    @Test
    void 读不到文案时的判定() {
        assertThat(TextAssertion.matches(Mode.CONTAINS, null, "登录")).isFalse();
        assertThat(TextAssertion.matches(Mode.EQUALS, null, "登录")).isFalse();
        assertThat(TextAssertion.matches(Mode.REGEX, null, "\\d+")).isFalse();
        assertThat(TextAssertion.matches(Mode.NOT_CONTAINS, null, "网络异常")).isTrue();
    }

    /**
     * 存量流程一个字都没改过，它们的 mode 是空的——必须还按 contains 判，
     * 否则这次升级会让一批本来是绿的用例集体变红，而原因跟产品毫无关系。
     */
    @Test
    void 认不出的写法退回contains() {
        assertThat(Mode.from(null)).isEqualTo(Mode.CONTAINS);
        assertThat(Mode.from("")).isEqualTo(Mode.CONTAINS);
        assertThat(Mode.from("乱写的")).isEqualTo(Mode.CONTAINS);
        assertThat(Mode.from("contains")).isEqualTo(Mode.CONTAINS);
    }

    /** 前端下拉传的是 notContains 这种驼峰，别让它掉进默认分支 */
    @Test
    void 前端写法能认出来() {
        assertThat(Mode.from("equals")).isEqualTo(Mode.EQUALS);
        assertThat(Mode.from("EQUALS")).isEqualTo(Mode.EQUALS);
        assertThat(Mode.from("regex")).isEqualTo(Mode.REGEX);
        assertThat(Mode.from("notContains")).isEqualTo(Mode.NOT_CONTAINS);
        assertThat(Mode.from("not_contains")).isEqualTo(Mode.NOT_CONTAINS);
        assertThat(Mode.from("not-contains")).isEqualTo(Mode.NOT_CONTAINS);
    }

    @Test
    void 失败文案说清楚是哪种判定() {
        assertThat(TextAssertion.describeFailure(Mode.EQUALS, "余额 1000", "余额 100"))
                .contains("期望等于").contains("余额 100").contains("余额 1000");
        assertThat(TextAssertion.describeFailure(Mode.CONTAINS, null, "登录"))
                .contains("读不到文案");
    }
}
