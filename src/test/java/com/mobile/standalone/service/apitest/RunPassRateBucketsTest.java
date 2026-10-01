package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行结果分桶的单测。
 *
 * <p>守四件会静默出错的事:
 * <ul>
 *   <li><b>两类用例共用分母</b>——"通过率 92%"仍然是个合理的百分数,坏得看不出来</li>
 *   <li><b>"没记录"被算进回归桶</b>——历史运行的数据会污染回归通过率</li>
 *   <li><b>BLOCKED 进分母</b>——跑太快会让通过率下降,人去查用例,真实原因是频率</li>
 *   <li><b>一条没跑成时返回 0 而不是 null</b>——界面一片红,人去查断言,
 *       真实原因是限流或者环境连不上</li>
 * </ul>
 */
class RunPassRateBucketsTest {

    private static Map<String, Object> row(String kind, long total, long passed, long blocked) {
        Map<String, Object> m = new HashMap<>();
        m.put("caseKind", kind);
        m.put("total", total);
        m.put("passed", passed);
        m.put("blocked", blocked);
        return m;
    }

    private static RunPassRateBuckets.Bucket pick(List<RunPassRateBuckets.Bucket> bs, String name) {
        return bs.stream().filter(b -> b.bucket().equals(name)).findFirst().orElse(null);
    }

    // ---------- 分桶 ----------

    @Test
    @DisplayName("分支用例和回归用例分开 —— 这是整个类存在的理由")
    void 两类分开() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("BRANCH", 10, 5, 0),
                row("REGRESSION", 10, 10, 0)));
        assertThat(bs).hasSize(2);
        assertThat(pick(bs, "BRANCH").passRate())
                .as("混在一起算的话是 75%，而真相是「这次改动挂了一半，回归全绿」")
                .isEqualTo(50.0);
        assertThat(pick(bs, "REGRESSION").passRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("未分类的和回归用例同桶 —— 它本来就是按回归跑的")
    void 未分类并入回归() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("REGRESSION", 4, 4, 0),
                row("UNCLASSIFIED", 6, 3, 0)));
        assertThat(bs).hasSize(1);
        assertThat(pick(bs, "REGRESSION").total()).isEqualTo(10);
        assertThat(pick(bs, "REGRESSION").passed()).isEqualTo(7);
    }

    /** 这条是整个改动里最容易错的一个边界 */
    @Test
    @DisplayName("case_kind 为空＝没记录,必须单独成桶,不能并进回归")
    void 空值不能并进回归() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("REGRESSION", 10, 10, 0),
                row(null, 10, 0, 0)));
        assertThat(pick(bs, "REGRESSION").passRate())
                .as("走 ApiCaseKind.of(null) 会得到 UNCLASSIFIED，桶是 REGRESSION，"
                        + "于是回归通过率从 100% 变成 50%——而那 10 条是加字段之前的历史运行，"
                        + "根本不知道是什么")
                .isEqualTo(100.0);
        assertThat(pick(bs, RunPassRateBuckets.UNRECORDED)).isNotNull();
        assertThat(pick(bs, RunPassRateBuckets.UNRECORDED).total()).isEqualTo(10);
    }

    @Test
    @DisplayName("认不出来的脏值也归到没记录,不能悄悄进回归桶")
    void 脏值归到没记录() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("BRANCHH", 5, 0, 0),
                row("REGRESSION", 5, 5, 0)));
        assertThat(pick(bs, "REGRESSION").passRate()).isEqualTo(100.0);
        assertThat(pick(bs, RunPassRateBuckets.UNRECORDED).total()).isEqualTo(5);
    }

    @Test
    @DisplayName("空白串和空值一样算没记录")
    void 空白串() {
        var bs = RunPassRateBuckets.fold(List.of(row("   ", 3, 1, 0)));
        assertThat(bs).hasSize(1);
        assertThat(bs.get(0).bucket()).isEqualTo(RunPassRateBuckets.UNRECORDED);
    }

    @Test
    @DisplayName("大小写不影响分桶")
    void 大小写() {
        var bs = RunPassRateBuckets.fold(List.of(row("branch", 2, 2, 0)));
        assertThat(bs.get(0).bucket()).isEqualTo("BRANCH");
    }

    // ---------- BLOCKED ----------

    @Test
    @DisplayName("BLOCKED 不进分母 —— 和已有的 passRate 口径一致")
    void blocked不进分母() {
        var b = RunPassRateBuckets.fold(List.of(row("REGRESSION", 10, 6, 4))).get(0);
        assertThat(b.ran()).isEqualTo(6);
        assertThat(b.passRate())
                .as("算进分母是 60%，而那 4 条一条断言都没跑到")
                .isEqualTo(100.0);
    }

    @Test
    @DisplayName("全部 BLOCKED 时通过率是 null,不是 0 也不是 100")
    void 全部被拦() {
        var b = RunPassRateBuckets.fold(List.of(row("BRANCH", 8, 0, 8))).get(0);
        assertThat(b.ran()).isZero();
        assertThat(b.hasRun()).isFalse();
        assertThat(b.passRate())
                .as("返回 0 的话界面一片红，人去查断言，而真实原因是限流；"
                        + "返回 100 更糟，等于谎报全绿")
                .isNull();
    }

    // ---------- 输出形状 ----------

    @Test
    @DisplayName("没出现过的桶不返回 —— 否则界面上会出现一个空的分支用例栏")
    void 没出现过的桶不返回() {
        var bs = RunPassRateBuckets.fold(List.of(row("REGRESSION", 5, 5, 0)));
        assertThat(bs).hasSize(1);
        assertThat(pick(bs, "BRANCH"))
                .as("这次运行里根本没有分支用例，不该显示「分支用例 通过率 —」")
                .isNull();
    }

    /**
     * 上一条测不出 {@code a[0] > 0} 这个判据:{@code GROUP BY} 出来的每组
     * {@code COUNT(1)} 必然 ≥1,所以那个桶根本不在 map 里,去掉判据也不变。
     * 这条用 total=0 的行把它单独钉住——fold 是个公开的纯函数,
     * 将来有人把 SQL 改成 LEFT JOIN 就会出现 total=0 的组。
     */
    @Test
    @DisplayName("total=0 的桶也不返回 —— 它会在界面上变成一栏「通过率 —」")
    void total为零的桶不返回() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("BRANCH", 0, 0, 0),
                row("REGRESSION", 5, 5, 0)));
        assertThat(bs)
                .as("去掉 a[0] > 0 的话，这里会多出一个 total=0 的 BRANCH 桶")
                .hasSize(1);
        assertThat(pick(bs, "BRANCH")).isNull();
    }

    @Test
    @DisplayName("顺序固定 —— 报表两次跑出来栏目顺序不一样的话人没法对比")
    void 顺序固定() {
        var bs = RunPassRateBuckets.fold(List.of(
                row(null, 1, 1, 0),
                row("REGRESSION", 1, 1, 0),
                row("BRANCH", 1, 1, 0)));
        assertThat(bs).extracting(RunPassRateBuckets.Bucket::bucket)
                .containsExactly("BRANCH", "REGRESSION", RunPassRateBuckets.UNRECORDED);
    }

    @Test
    @DisplayName("同一个桶的多行要累加 —— REGRESSION 和 UNCLASSIFIED 是两行")
    void 同桶累加() {
        var bs = RunPassRateBuckets.fold(List.of(
                row("REGRESSION", 3, 3, 0),
                row("UNCLASSIFIED", 7, 4, 2)));
        var b = pick(bs, "REGRESSION");
        assertThat(b.total()).isEqualTo(10);
        assertThat(b.passed()).isEqualTo(7);
        assertThat(b.blocked()).isEqualTo(2);
        assertThat(b.ran()).isEqualTo(8);
    }

    // ---------- 健壮性 ----------

    @Test
    @DisplayName("null / 空输入不炸")
    void 空输入() {
        assertThat(RunPassRateBuckets.fold(null)).isEmpty();
        assertThat(RunPassRateBuckets.fold(List.of())).isEmpty();
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(row("BRANCH", 1, 1, 0));
        assertThat(RunPassRateBuckets.fold(withNull)).hasSize(1);
    }

    @Test
    @DisplayName("下划线键名也认 —— 不同 mapper 的 resultType 风格不一致")
    void 下划线键名() {
        Map<String, Object> m = new HashMap<>();
        m.put("case_kind", "BRANCH");
        m.put("total", 4);
        m.put("passed", 2);
        var b = RunPassRateBuckets.fold(List.of(m)).get(0);
        assertThat(b.bucket()).isEqualTo("BRANCH");
        assertThat(b.blocked()).as("缺的键按 0 算，不是抛").isZero();
        assertThat(b.passRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("计数是字符串时也能算 —— 有些驱动把 SUM 返回成 BigDecimal/字符串")
    void 字符串计数() {
        Map<String, Object> m = new HashMap<>();
        m.put("caseKind", "REGRESSION");
        m.put("total", "10");
        m.put("passed", new java.math.BigDecimal("8"));
        m.put("blocked", "0");
        var b = RunPassRateBuckets.fold(List.of(m)).get(0);
        assertThat(b.passRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("通过率保留一位小数")
    void 一位小数() {
        var b = RunPassRateBuckets.fold(List.of(row("BRANCH", 3, 1, 0))).get(0);
        assertThat(b.passRate()).isEqualTo(33.3);
    }
}
