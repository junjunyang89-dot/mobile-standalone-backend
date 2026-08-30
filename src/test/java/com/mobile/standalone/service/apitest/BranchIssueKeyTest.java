package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认错单号的后果是静默的：接口挂到了别的单子上，而两边看着都正常。
 * 所以这里把「该认」和「不该认」都钉死。
 */
class BranchIssueKeyTest {

    @Test
    void 认出你们的分支格式() {
        assertThat(BranchIssueKey.firstIn("fch/20260804/APP-8928")).isEqualTo("APP-8928");
    }

    @Test
    void 常见的几种写法都认() {
        assertThat(BranchIssueKey.firstIn("feature/APP-9059")).isEqualTo("APP-9059");
        assertThat(BranchIssueKey.firstIn("APP-8873")).isEqualTo("APP-8873");
        assertThat(BranchIssueKey.firstIn("hotfix/app-1234")).isEqualTo("APP-1234");
        assertThat(BranchIssueKey.firstIn("release/PWA-77-copy")).isEqualTo("PWA-77");
    }

    /**
     * 日期段不能当成单号。
     * {@code fch/20260804/APP-8928} 里的 20260804 没有字母前缀，正则本身不会匹配；
     * 但 {@code REL-20260804} 会——那是日期，不是单子。
     */
    @Test
    void 日期不算单号() {
        assertThat(BranchIssueKey.firstIn("release/REL-20260804")).isNull();
        assertThat(BranchIssueKey.firstIn("fch/20260804")).isNull();
        // 同时有日期和真单号时，只认真的那个
        assertThat(BranchIssueKey.firstIn("REL-20260804/APP-8928")).isEqualTo("APP-8928");
    }

    /** 一条分支带两个单是存在的写法；只取第一个会让第二个永远关联不上 */
    @Test
    void 一条分支多个单号都返回() {
        assertThat(BranchIssueKey.allIn("feat/APP-100-APP-101"))
                .containsExactly("APP-100", "APP-101");
    }

    /** 同一个单号写两遍只算一次 */
    @Test
    void 重复的单号去重() {
        assertThat(BranchIssueKey.allIn("APP-100/fix/APP-100")).containsExactly("APP-100");
    }

    /** 前后粘着字母数字的不算——那多半是版本号或哈希的一部分 */
    @Test
    void 不认粘在其它字符里的() {
        assertThat(BranchIssueKey.firstIn("v2APP-1234")).isNull();
        assertThat(BranchIssueKey.firstIn("APP-12345678")).isNull();
    }

    /** 一位数字多半是版本号误伤，如 v1-2 */
    @Test
    void 一位数字不算单号() {
        assertThat(BranchIssueKey.firstIn("branch/AB-1")).isNull();
        assertThat(BranchIssueKey.firstIn("branch/AB-12")).isEqualTo("AB-12");
    }

    @Test
    void 空值和认不出时返回空() {
        assertThat(BranchIssueKey.firstIn(null)).isNull();
        assertThat(BranchIssueKey.firstIn("")).isNull();
        assertThat(BranchIssueKey.firstIn("master")).isNull();
        assertThat(BranchIssueKey.firstIn("develop")).isNull();
        assertThat(BranchIssueKey.allIn("master")).isEmpty();
    }

    /** 「目录:head」这种 analyze-dirs 传进来的伪分支名不该认出单号 */
    @Test
    void 目录对比的伪分支名不误认() {
        assertThat(BranchIssueKey.firstIn("目录:head")).isNull();
    }
}
