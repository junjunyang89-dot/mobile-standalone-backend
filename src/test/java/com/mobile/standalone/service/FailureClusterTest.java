package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败归类。
 * <p>
 * 一次回归红 20 条，逐条查是最费时间的做法——那 20 条里往往 18 条是同一个原因。
 * 打开报告第一眼该看到「实际上是 3 个问题」，而不是 20 行红。
 */
class FailureClusterTest {

    private static FailureCluster.Failure f(String label, String step, String msg) {
        return new FailureCluster.Failure(label, step, msg);
    }

    /** 本组核心：同一步同一个错，无论多少条都是一个问题 */
    @Test
    void 同一个原因归成一类() {
        List<FailureCluster.Cluster> out = FailureCluster.cluster(List.of(
                f("登录流程", "2·输入", "401 未授权"),
                f("发评论", "2·输入", "401 未授权"),
                f("看赛程", "2·输入", "401 未授权")));

        assertEquals(1, out.size(), "三条失败其实是一个问题");
        assertEquals(3, out.get(0).getCount());
        assertEquals(3, out.get(0).getSamples().size());
    }

    /**
     * 不抹掉变化的数字就白做了：「等待 5000ms 超时」和「等待 8000ms 超时」
     * 会被算成两类，20 条失败还是 20 类。
     */
    @Test
    void 数字不同仍算同一类() {
        List<FailureCluster.Cluster> out = FailureCluster.cluster(List.of(
                f("A", "3·等待", "等待元素超时 5000ms"),
                f("B", "3·等待", "等待元素超时 8000ms")));

        assertEquals(1, out.size());
    }

    /** 同一步的不同错是两个问题，不能混 */
    @Test
    void 同一步不同错分成两类() {
        List<FailureCluster.Cluster> out = FailureCluster.cluster(List.of(
                f("A", "2·登录", "401 未授权"),
                f("B", "2·登录", "找不到元素 login_btn")));

        assertEquals(2, out.size());
    }

    /** 条数多的排前面——它挡住的东西最多，也最可能是根因 */
    @Test
    void 条数多的排最前() {
        List<FailureCluster.Cluster> out = FailureCluster.cluster(List.of(
                f("A", "5·点击", "找不到元素"),
                f("B", "2·登录", "401"),
                f("C", "2·登录", "401"),
                f("D", "2·登录", "401")));

        assertEquals(3, out.get(0).getCount());
        assertEquals(1, out.get(0).getRank());
    }

    @Test
    void 样例最多列五个() {
        List<FailureCluster.Failure> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(f("流程" + i, "2·登录", "401"));
        }

        FailureCluster.Cluster c = FailureCluster.cluster(many).get(0);

        assertEquals(20, c.getCount());
        assertEquals(5, c.getSamples().size(), "样例是给人扫一眼的，列二十个等于没列");
    }

    // ==================== 建议 ====================

    @Test
    void 登录类给出先查登录态的建议() {
        assertTrue(FailureCluster.hintFor("401 未授权").contains("登录态"));
        assertTrue(FailureCluster.hintFor("token 已过期").contains("登录态"));
    }

    @Test
    void 超时类提醒先排除环境() {
        assertTrue(FailureCluster.hintFor("等待超时").contains("环境"),
                "全是超时时先怀疑环境，别急着当功能 bug");
    }

    @Test
    void 元素类指向元素管理() {
        assertTrue(FailureCluster.hintFor("找不到元素 login_btn").contains("元素管理"));
    }

    /** 认不出就不给——瞎给一句「请检查配置」会让人以为系统知道原因 */
    @Test
    void 认不出的不给建议() {
        assertNull(FailureCluster.hintFor("某个没见过的错"));
        assertNull(FailureCluster.hintFor(null));
    }

    // ==================== 结论 ====================

    @Test
    void 只有一类时直接说是同一个问题() {
        List<FailureCluster.Cluster> one = FailureCluster.cluster(List.of(
                f("A", "2·登录", "401"), f("B", "2·登录", "401")));

        assertTrue(FailureCluster.summarize(2, one).contains("同一个问题"));
    }

    @Test
    void 多类时说清有几类和最大那类() {
        List<FailureCluster.Cluster> multi = FailureCluster.cluster(List.of(
                f("A", "2·登录", "401"), f("B", "2·登录", "401"), f("C", "5·点击", "找不到元素")));

        String s = FailureCluster.summarize(3, multi);
        assertTrue(s.contains("2 类"), s);
        assertTrue(s.contains("2 条"), s);
    }

    @Test
    void 空输入不抛() {
        assertTrue(FailureCluster.cluster(null).isEmpty());
        assertTrue(FailureCluster.cluster(List.of()).isEmpty());
        assertNotNull(FailureCluster.summarize(0, List.of()));
    }
}
