package com.mobile.standalone.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * caseKind 作为<b>筛选条件</b>时的规范化。
 *
 * <p>这组测试守的是一个方向性判断:同一个字段,<b>存的时候宽松、筛的时候严格</b>。
 *
 * <ul>
 *   <li>存:{@code ApiCaseKind.of("BRANCHH")} → UNCLASSIFIED。
 *       一个拼错的值不该让整条用例写不进去</li>
 *   <li>筛:{@code normalizeCaseKindFilter("BRANCHH")} → <b>抛</b>。
 *       当成「不筛」的话,界面会把全部用例显示成分支用例,人按这个去分诊;
 *       用 {@code of()} 更糟——它会筛出一堆未分类的,
 *       而那看起来像是「真的没有分支用例」</li>
 * </ul>
 *
 * <p>两个方向都不是默认行为,所以两边都得有测试钉住。
 */
class ApiCaseKindFilterTest {

    @Test
    @DisplayName("空值＝不筛")
    void 空值不筛() {
        assertThat(ApiTestCaseService.normalizeCaseKindFilter(null)).isNull();
        assertThat(ApiTestCaseService.normalizeCaseKindFilter("")).isNull();
        assertThat(ApiTestCaseService.normalizeCaseKindFilter("   ")).isNull();
    }

    @Test
    @DisplayName("认识的值规范化成大写")
    void 规范化() {
        assertThat(ApiTestCaseService.normalizeCaseKindFilter("branch")).isEqualTo("BRANCH");
        assertThat(ApiTestCaseService.normalizeCaseKindFilter("  Regression ")).isEqualTo("REGRESSION");
        assertThat(ApiTestCaseService.normalizeCaseKindFilter("UNCLASSIFIED")).isEqualTo("UNCLASSIFIED");
    }

    /** 这条是整个筛选路径上唯一真正重要的判据 */
    @Test
    @DisplayName("认不出来的值必须抛 —— 不能悄悄不筛,也不能归到未分类")
    void 脏值必须抛() {
        assertThatThrownBy(() -> ApiTestCaseService.normalizeCaseKindFilter("BRANCHH"))
                .as("改成 return null 的话，界面会把全部用例当成分支用例显示，"
                        + "人按这个去分诊；改成走 of() 的话，会筛出一堆未分类的，"
                        + "而那看起来像「真的没有分支用例」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRANCHH");
    }

    @Test
    @DisplayName("中文、数字这类明显不对的值也要抛")
    void 其它脏值() {
        assertThatThrownBy(() -> ApiTestCaseService.normalizeCaseKindFilter("分支"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiTestCaseService.normalizeCaseKindFilter("1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("报错信息要把三个合法值列出来 —— 否则调用方只知道错了,不知道该传什么")
    void 报错要可操作() {
        assertThatThrownBy(() -> ApiTestCaseService.normalizeCaseKindFilter("x"))
                .hasMessageContaining("BRANCH")
                .hasMessageContaining("REGRESSION")
                .hasMessageContaining("UNCLASSIFIED");
    }

    @Test
    @DisplayName("存和筛对同一个脏值的处理必须相反 —— 这是这两段代码存在的理由")
    void 存宽筛严() {
        String dirty = "BRANCHH";
        assertThat(com.mobile.standalone.service.apitest.ApiCaseKind.of(dirty))
                .as("存：宽松，归到未分类，不要让用例写不进去")
                .isEqualTo(com.mobile.standalone.service.apitest.ApiCaseKind.UNCLASSIFIED);
        assertThatThrownBy(() -> ApiTestCaseService.normalizeCaseKindFilter(dirty))
                .as("筛：严格，直接抛")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
