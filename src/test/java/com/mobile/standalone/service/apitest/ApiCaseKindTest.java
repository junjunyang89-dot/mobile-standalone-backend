package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例种类的单测。
 *
 * <p>这组测试守三件会静默出错的事:
 * <ul>
 *   <li><b>加了字段却让已有用例少跑</b>——少跑是看不见的,没人会发现覆盖掉了</li>
 *   <li><b>把未分类的也倒进待分诊队列</b>——队列一冲淡,人就批量点过,比不分诊更糟</li>
 *   <li><b>两类用例共用通过率分母</b>——那个数字就不再能支撑任何决定</li>
 * </ul>
 */
class ApiCaseKindTest {

    // ---------- 解析 ----------

    @Test
    @DisplayName("空值一律未分类 —— 迁移后老数据就是这个样子")
    void 空值() {
        assertThat(ApiCaseKind.of(null)).isEqualTo(ApiCaseKind.UNCLASSIFIED);
        assertThat(ApiCaseKind.of("")).isEqualTo(ApiCaseKind.UNCLASSIFIED);
        assertThat(ApiCaseKind.of("   ")).isEqualTo(ApiCaseKind.UNCLASSIFIED);
    }

    @Test
    @DisplayName("不认识的值落到未分类,而不是抛异常")
    void 脏值不抛异常() {
        assertThat(ApiCaseKind.of("REGRESION")).isEqualTo(ApiCaseKind.UNCLASSIFIED);
        assertThat(ApiCaseKind.of("分支")).isEqualTo(ApiCaseKind.UNCLASSIFIED);
        assertThat(ApiCaseKind.of("0"))
                .as("一个拼错的值不该让整条用例列表打不开")
                .isEqualTo(ApiCaseKind.UNCLASSIFIED);
    }

    @Test
    @DisplayName("大小写和空格都要认 —— 前端传什么样的都有")
    void 宽松解析() {
        assertThat(ApiCaseKind.of("branch")).isEqualTo(ApiCaseKind.BRANCH);
        assertThat(ApiCaseKind.of("Regression")).isEqualTo(ApiCaseKind.REGRESSION);
        assertThat(ApiCaseKind.of("  BRANCH  ")).isEqualTo(ApiCaseKind.BRANCH);
    }

    @Test
    @DisplayName("存进去的值读回来必须还是它 —— 存的是 name()")
    void 往返一致() {
        for (ApiCaseKind k : ApiCaseKind.values()) {
            assertThat(ApiCaseKind.of(k.name()))
                    .as("%s 存进数据库再读出来变了", k)
                    .isEqualTo(k);
        }
    }

    // ---------- 执行范围 ----------

    @Test
    @DisplayName("分支用例不进定期回归 —— 它只在提测期间跑")
    void 分支用例不定期跑() {
        assertThat(ApiCaseKind.BRANCH.runsInPeriodicRegression()).isFalse();
    }

    /** 这条是整个改动里最容易被"顺手简化"掉的一个边界 */
    @Test
    @DisplayName("未分类的要照跑 —— 加字段不能让已有用例悄悄少跑")
    void 未分类不能少跑() {
        assertThat(ApiCaseKind.UNCLASSIFIED.runsInPeriodicRegression())
                .as("写成 this == REGRESSION 的话，库里全部历史用例会在加字段那天"
                        + "集体停跑，而少跑是静默的——没人会发现覆盖掉了")
                .isTrue();
        assertThat(ApiCaseKind.REGRESSION.runsInPeriodicRegression()).isTrue();
    }

    // ---------- 分诊队列 ----------

    @Test
    @DisplayName("只有分支用例需要分诊,未分类的不进队列")
    void 分诊队列只收分支用例() {
        assertThat(ApiCaseKind.BRANCH.needsTriageAfterMerge()).isTrue();
        assertThat(ApiCaseKind.REGRESSION.needsTriageAfterMerge()).isFalse();
        assertThat(ApiCaseKind.UNCLASSIFIED.needsTriageAfterMerge())
                .as("未分类的没有对应分支，进来了也无从判断，只会把队列冲淡到没人看")
                .isFalse();
    }

    // ---------- 通过率分桶 ----------

    @Test
    @DisplayName("分支用例和回归用例必须分桶 —— 这是这个方法存在的唯一理由")
    void 两类不能共用分母() {
        assertThat(ApiCaseKind.BRANCH.passRateBucket())
                .as("混进一个分母后「通过率 92%」就没有含义了："
                        + "你不知道挂的那 8% 是「这次改动有问题」还是「回归了」")
                .isNotEqualTo(ApiCaseKind.REGRESSION.passRateBucket());
    }

    @Test
    @DisplayName("未分类的和回归用例同桶 —— 跑了就要算进分母")
    void 未分类和回归同桶() {
        assertThat(ApiCaseKind.UNCLASSIFIED.passRateBucket())
                .as("它本来就是按回归跑的；跑了却把结果从分母摘掉＝把失败藏起来，"
                        + "那比归错桶更糟")
                .isEqualTo(ApiCaseKind.REGRESSION.passRateBucket());
    }

    // ---------- 文案 ----------

    @Test
    @DisplayName("每个值都得有标签和说明 —— 将来加值的人必须想清楚它是什么")
    void 文案不能空() {
        for (ApiCaseKind k : ApiCaseKind.values()) {
            assertThat(k.label()).as("%s 没有标签", k).isNotBlank();
            assertThat(k.description()).as("%s 没有说明", k).isNotBlank();
        }
    }

    @Test
    @DisplayName("分支用例的说明要写明「必须分诊」 —— 否则没人知道要做这个动作")
    void 分支用例的说明要提分诊() {
        assertThat(ApiCaseKind.BRANCH.description()).contains("分诊");
    }
}
