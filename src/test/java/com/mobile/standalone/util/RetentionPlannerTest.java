package com.mobile.standalone.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清理计划的单测。
 *
 * <p>最该守住的一条:**目录不能按自己的 mtime 判断**。
 * 一个看起来很旧的目录里可能躺着今天刚生成的截图,而报告页正指着它们——
 * 按目录 mtime 删的话,人看到的是一片破图,而且不会怀疑是清理干的。
 */
class RetentionPlannerTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static RetentionPlanner.Entry file(String path, int daysAgo, long size) {
        return new RetentionPlanner.Entry(path, false, NOW - daysAgo * DAY, size);
    }

    private static RetentionPlanner.Entry dir(String path, int daysAgo) {
        return new RetentionPlanner.Entry(path, true, NOW - daysAgo * DAY, 0);
    }

    // ---------- 基本 ----------

    @Test
    @DisplayName("超过保留天数的文件要删,期内的要留")
    void 按天数删文件() {
        var plan = RetentionPlanner.plan(List.of(
                file("old.png", 5, 100),
                file("new.png", 1, 200)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("old.png");
        assertThat(plan.freedBytes()).isEqualTo(100);
    }

    @Test
    @DisplayName("刚好等于保留天数的不删 —— 边界上宁可多留一天")
    void 边界不删() {
        var plan = RetentionPlanner.plan(List.of(file("a.png", 2, 10)), NOW, 2);
        assertThat(plan.deletePaths()).isEmpty();
    }

    // ---------- 递归:这次修的就是它 ----------

    @Test
    @DisplayName("子目录里的旧文件要清掉 —— 原来的 Files.list 不递归,perf_* 一张都不清")
    void 递归清子目录() {
        var plan = RetentionPlanner.plan(List.of(
                dir("perf_task1", 5),
                file("perf_task1/01.png", 5, 100),
                file("perf_task1/02.png", 5, 100),
                file("top.png", 5, 50)), NOW, 2);
        assertThat(plan.deletePaths())
                .contains("perf_task1/01.png", "perf_task1/02.png", "top.png");
        assertThat(plan.freedBytes()).isEqualTo(250);
    }

    @Test
    @DisplayName("多层嵌套也要清到底")
    void 多层嵌套() {
        var plan = RetentionPlanner.plan(List.of(
                dir("a", 9), dir("a/b", 9), dir("a/b/c", 9),
                file("a/b/c/deep.png", 9, 10)), NOW, 2);
        assertThat(plan.deletePaths()).contains("a/b/c/deep.png", "a/b/c/", "a/b/", "a/");
    }

    // ---------- 目录的 mtime 不算数 ----------

    @Test
    @DisplayName("目录很旧但里面有新文件 —— 目录和新文件都必须留下")
    void 旧目录里有新文件() {
        var plan = RetentionPlanner.plan(List.of(
                dir("perf_task1", 30),          // 目录 mtime 很旧
                file("perf_task1/fresh.png", 0, 100)), NOW, 2);
        assertThat(plan.deletePaths())
                .as("按目录 mtime 删的话，会连今天刚生成、报告正在引用的图一起删掉")
                .isEmpty();
    }

    @Test
    @DisplayName("目录里一半旧一半新 —— 只删旧的,目录留着")
    void 半新半旧的目录() {
        var plan = RetentionPlanner.plan(List.of(
                dir("perf_task1", 10),
                file("perf_task1/old.png", 10, 100),
                file("perf_task1/new.png", 0, 200)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("perf_task1/old.png");
        assertThat(plan.deletePaths()).doesNotContain("perf_task1/");
    }

    @Test
    @DisplayName("里面全删光了,空目录才跟着删")
    void 空掉的目录才删() {
        var plan = RetentionPlanner.plan(List.of(
                dir("perf_task1", 10),
                file("perf_task1/a.png", 10, 1),
                file("perf_task1/b.png", 10, 1)), NOW, 2);
        assertThat(plan.deletePaths()).contains("perf_task1/");
    }

    @Test
    @DisplayName("父目录只有在子目录也空掉之后才删")
    void 父目录等子目录() {
        var plan = RetentionPlanner.plan(List.of(
                dir("a", 10), dir("a/keep", 10), dir("a/gone", 10),
                file("a/keep/fresh.png", 0, 1),
                file("a/gone/old.png", 10, 1)), NOW, 2);
        assertThat(plan.deletePaths()).contains("a/gone/old.png", "a/gone/");
        assertThat(plan.deletePaths())
                .as("a 下面还有 keep，不能删 a").doesNotContain("a/");
        assertThat(plan.deletePaths()).doesNotContain("a/keep/");
    }

    @Test
    @DisplayName("本来就是空的目录也会被回收")
    void 本来就空的目录() {
        var plan = RetentionPlanner.plan(List.of(dir("empty_dir", 10)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("empty_dir/");
    }

    // ---------- 顶层与子目录的保留期不同 ----------

    @Test
    @DisplayName("子目录是报告证据，保留期要能比顶层临时图长")
    void 子目录用更长的保留期() {
        // 顶层留 2 天、子目录留 7 天
        var plan = RetentionPlanner.plan(List.of(
                file("inspect_tmp.png", 4, 10),           // 顶层，4 天 → 该删
                dir("perf_task1", 4),
                file("perf_task1/img_0.png", 4, 20)),     // 子目录，4 天 → 要留
                NOW, 2, 7);
        assertThat(plan.deletePaths())
                .as("报告页正指着 perf_* 里的图，按临时图的 2 天清掉就是一片破图")
                .containsExactly("inspect_tmp.png");
    }

    @Test
    @DisplayName("子目录超过它自己的保留期，照样清")
    void 子目录超期也清() {
        var plan = RetentionPlanner.plan(List.of(
                dir("perf_task1", 10),
                file("perf_task1/img_0.png", 10, 20)),
                NOW, 2, 7);
        assertThat(plan.deletePaths()).contains("perf_task1/img_0.png", "perf_task1/");
    }

    @Test
    @DisplayName("单参数版本等于两个保留期相同")
    void 单参数版本兼容() {
        var entries = List.of(
                file("a.png", 4, 1),
                dir("d", 4), file("d/b.png", 4, 1));
        assertThat(RetentionPlanner.plan(entries, NOW, 2).deletePaths())
                .isEqualTo(RetentionPlanner.plan(entries, NOW, 2, 2).deletePaths());
    }

    // ---------- 删除顺序 ----------

    @Test
    @DisplayName("必须自底向上 —— 先删父目录会因为非空而失败")
    void 自底向上() {
        var plan = RetentionPlanner.plan(List.of(
                dir("a", 10), dir("a/b", 10),
                file("a/b/x.png", 10, 1)), NOW, 2);
        var paths = plan.deletePaths();
        assertThat(paths.indexOf("a/b/x.png")).isLessThan(paths.indexOf("a/b/"));
        assertThat(paths.indexOf("a/b/")).isLessThan(paths.indexOf("a/"));
    }

    @Test
    @DisplayName("同一批输入的输出顺序必须稳定 —— dry-run 清单要能 diff")
    void 输出确定性() {
        // 文件是从 HashSet 里倒出来的，不排序的话两次跑的顺序可能不同，
        // 而 dry-run 的结果是给人看的：顺序不稳就没法和上次比
        var entries = List.of(
                dir("z_dir", 10), file("z_dir/b.png", 10, 1), file("z_dir/a.png", 10, 1),
                dir("a_dir", 10), file("a_dir/y.png", 10, 1), file("a_dir/x.png", 10, 1),
                file("root.png", 10, 1));
        var first = RetentionPlanner.plan(entries, NOW, 2).deletePaths();
        for (int i = 0; i < 20; i++) {
            assertThat(RetentionPlanner.plan(entries, NOW, 2).deletePaths())
                    .as("第 %d 次的顺序和第一次不一样", i + 2)
                    .containsExactlyElementsOf(first);
        }
        // 真正只有排序能保证的性质：整个清单按路径深度递减。
        // 不排序的话，深度 1 的 root.png 会混在深度 2 的条目之前
        // （文件是先全部倒出来的，不管深浅）
        int prevDepth = Integer.MAX_VALUE;
        for (String path : first) {
            int d = (int) path.chars().filter(c -> c == '/').count()
                    + (path.endsWith("/") ? 0 : 1);
            assertThat(d)
                    .as("清单没有按深度递减，%s 出现在更深的条目之后", path)
                    .isLessThanOrEqualTo(prevDepth);
            prevDepth = d;
        }
    }

    // ---------- 拿不到 mtime ----------

    @Test
    @DisplayName("拿不到 mtime 的文件不删,并且要计数")
    void 未知时间不删() {
        var plan = RetentionPlanner.plan(List.of(
                new RetentionPlanner.Entry("weird.png", false, 0, 100),
                new RetentionPlanner.Entry("weird2.png", false, -1, 100),
                file("old.png", 10, 50)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("old.png");
        assertThat(plan.keptUnknown())
                .as("持续不为 0 说明文件系统有问题，该让人看见").isEqualTo(2);
    }

    @Test
    @DisplayName("目录里有拿不到时间的文件,目录也不能删")
    void 未知时间的文件拦住目录() {
        var plan = RetentionPlanner.plan(List.of(
                dir("d", 10),
                new RetentionPlanner.Entry("d/weird.png", false, 0, 1),
                file("d/old.png", 10, 1)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("d/old.png");
        assertThat(plan.deletePaths()).doesNotContain("d/");
    }

    // ---------- 边界 ----------

    @Test
    @DisplayName("空输入 / null 不炸")
    void 空输入() {
        assertThat(RetentionPlanner.plan(List.of(), NOW, 2).deletePaths()).isEmpty();
        assertThat(RetentionPlanner.plan(null, NOW, 2).deletePaths()).isEmpty();
        assertThat(RetentionPlanner.plan(null, NOW, 2).freedBytes()).isZero();
    }

    @Test
    @DisplayName("非法条目被跳过而不是炸掉整轮清理")
    void 非法条目() {
        var plan = RetentionPlanner.plan(java.util.Arrays.asList(
                null,
                new RetentionPlanner.Entry(null, false, NOW - 10 * DAY, 1),
                new RetentionPlanner.Entry("  ", false, NOW - 10 * DAY, 1),
                file("ok.png", 10, 5)), NOW, 2);
        assertThat(plan.deletePaths()).containsExactly("ok.png");
    }

    @Test
    @DisplayName("未来时间的文件当成 0 天,不删")
    void 未来时间() {
        var plan = RetentionPlanner.plan(List.of(
                new RetentionPlanner.Entry("future.png", false, NOW + 5 * DAY, 1)), NOW, 2);
        assertThat(plan.deletePaths()).isEmpty();
    }

    @Test
    @DisplayName("保留 0 天表示只留今天的")
    void 保留零天() {
        var plan = RetentionPlanner.plan(List.of(
                file("today.png", 0, 1),
                file("yesterday.png", 1, 1)), NOW, 0);
        assertThat(plan.deletePaths()).containsExactly("yesterday.png");
    }

    @Test
    @DisplayName("负数大小不会算出负的释放量")
    void 负数大小() {
        var plan = RetentionPlanner.plan(List.of(
                new RetentionPlanner.Entry("a.png", false, NOW - 10 * DAY, -100)), NOW, 2);
        assertThat(plan.freedBytes()).isZero();
    }

    @Test
    @DisplayName("fileCount 只数文件,不数目录")
    void 文件数不含目录() {
        var plan = RetentionPlanner.plan(List.of(
                dir("d", 10), file("d/a.png", 10, 1), file("d/b.png", 10, 1)), NOW, 2);
        assertThat(plan.deletePaths()).hasSize(3);
        assertThat(plan.fileCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("天数计算")
    void 天数() {
        assertThat(RetentionPlanner.ageDays(NOW - 3 * DAY, NOW)).isEqualTo(3);
        assertThat(RetentionPlanner.ageDays(NOW, NOW)).isZero();
        assertThat(RetentionPlanner.ageDays(NOW + DAY, NOW)).isZero();
        // 不满一天算 0 天
        assertThat(RetentionPlanner.ageDays(NOW - DAY + 1000, NOW)).isZero();
    }
}
