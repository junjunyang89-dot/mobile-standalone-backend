package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.util.RunTrack;
import com.mobile.standalone.util.TestEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归候选的两个维度：轨道（catalog）与环境（environment）。
 * <p>
 * 这两维<b>正交</b>：轨道说「哪个入口用」，环境说「验哪个包」。曾经想用 catalog 一个字段
 * 同时表达两件事，两种拆法都出过事——所以这里逐条钉死它们互不影响。
 * <p>
 * 另一批最要紧的是<b>存量不受影响</b>：迁移前建的行 environment 为空，必须仍按生产处理，
 * 否则线上回归会在发版当天少用例。
 */
class RegressionEnvironmentTest {

    private final UiFlowService service = new UiFlowService(null, null);

    private static UiFlow flow(String catalog, String environment) {
        UiFlow f = new UiFlow();
        f.setId("uif-1");
        f.setName("登录");
        f.setCatalog(catalog);
        f.setEnvironment(environment);
        f.setEnabled(true);
        f.setIsComponent(false);
        return f;
    }

    // ==================== 两个维度是正交的 ====================

    /**
     * catalog 只表达轨道，跟环境无关。
     * <p>
     * 之前想用一个字段表达两件事，两种拆法都出过事：把 SHARED 当「两环境共用」，线上用例漏进了
     * 测试批次；把 AUTOMATION 当「测试环境回归」，自动化轨道被整个占掉、线上自动化用例没地方放。
     */
    @Test
    void 回归轨道与环境无关() {
        assertEquals(List.of("ONLINE", "SHARED"), UiFlowService.regressionCatalogs());
    }

    /** 同一个用途在两个环境各有一批用例，互不干扰 */
    @Test
    void 同一用途的流程按环境分流() {
        for (String catalog : List.of("ONLINE", "SHARED")) {
            assertTrue(service.isRegressionEligible(flow(catalog, "PROD"), TestEnvironment.PROD), catalog);
            assertFalse(service.isRegressionEligible(flow(catalog, "PROD"), TestEnvironment.TEST), catalog);
            assertTrue(service.isRegressionEligible(flow(catalog, "TEST"), TestEnvironment.TEST), catalog);
            assertFalse(service.isRegressionEligible(flow(catalog, "TEST"), TestEnvironment.PROD), catalog);
        }
    }

    /**
     * 「回归 + 自动化编排」（SHARED）跨的是轨道不是环境：
     * 一条 SHARED + TEST 的流程，测试回归批次和测试自动化都能用到它，但线上那边看不到。
     */
    @Test
    void 回归加自动化的流程跨轨道不跨环境() {
        UiFlow sharedTest = flow("SHARED", "TEST");

        assertTrue(service.isRegressionEligible(sharedTest, TestEnvironment.TEST));
        assertFalse(service.isRegressionEligible(sharedTest, TestEnvironment.PROD));
        // 轨道这一维仍然认它
        assertTrue(UiFlowService.regressionCatalogs().contains("SHARED"));
    }

    /** 仅自动化编排：哪个环境的回归批次都不收 */
    @Test
    void 仅自动化编排的流程不进任何回归批次() {
        for (String env : List.of(TestEnvironment.PROD, TestEnvironment.TEST)) {
            assertFalse(service.isRegressionEligible(flow("AUTOMATION", env), env), env);
        }
    }

    @Test
    void 仅性能测试的流程不进回归批次() {
        assertFalse(service.isRegressionEligible(flow("PERFORMANCE", "PROD"), TestEnvironment.PROD));
    }

    // ==================== 存量数据：生产不能有任何变化 ====================

    /** 迁移前建的行 environment 为空，必须仍按生产收进线上回归 */
    @Test
    void 环境为空的存量流程按生产处理() {
        assertTrue(service.isRegressionEligible(flow("ONLINE", null), TestEnvironment.PROD));
        assertTrue(service.isRegressionEligible(flow("SHARED", ""), TestEnvironment.PROD));
        assertFalse(service.isRegressionEligible(flow("ONLINE", null), TestEnvironment.TEST));
    }

    @Test
    void 不带环境的旧调用仍按生产判定() {
        assertTrue(service.isRegressionEligible(flow("ONLINE", "PROD")));
        assertFalse(service.isRegressionEligible(flow("ONLINE", "TEST")));
    }

    @Test
    void 无法识别的环境值按生产处理() {
        assertEquals(TestEnvironment.PROD, TestEnvironment.normalize("xxx"));
        assertTrue(service.isRegressionEligible(flow("ONLINE", "xxx"), TestEnvironment.PROD));
    }

    /** 停用与组件在哪个环境、哪个轨道都不该被选中 */
    @Test
    void 停用与组件在任何环境都不可用() {
        UiFlow disabled = flow("SHARED", "TEST");
        disabled.setEnabled(false);
        UiFlow component = flow("SHARED", "TEST");
        component.setIsComponent(true);

        for (String env : List.of(TestEnvironment.PROD, TestEnvironment.TEST)) {
            assertFalse(service.isRegressionEligible(disabled, env), env);
            assertFalse(service.isRegressionEligible(component, env), env);
        }
    }

    // ==================== 轨道这一维 ====================

    /** 三个维度各管各的：轨道看 catalog、环境看 environment、启用/组件各自一条 */
    @Test
    void 轨道与环境是两个独立的过滤条件() {
        UiFlow autoTest = flow("AUTOMATION", "TEST");

        assertTrue(service.isBatchEligible(autoTest, TestEnvironment.TEST, RunTrack.AUTOMATION));
        // 环境对不上
        assertFalse(service.isBatchEligible(autoTest, TestEnvironment.PROD, RunTrack.AUTOMATION));
        // 轨道对不上
        assertFalse(service.isBatchEligible(autoTest, TestEnvironment.TEST, RunTrack.REGRESSION));
    }

    /** 「回归 + 自动化编排」跨轨道不跨环境 */
    @Test
    void 回归加自动化的流程两条轨道都收但只在自己环境() {
        UiFlow sharedProd = flow("SHARED", "PROD");

        assertTrue(service.isBatchEligible(sharedProd, TestEnvironment.PROD, RunTrack.REGRESSION));
        assertTrue(service.isBatchEligible(sharedProd, TestEnvironment.PROD, RunTrack.AUTOMATION));
        assertFalse(service.isBatchEligible(sharedProd, TestEnvironment.TEST, RunTrack.AUTOMATION));
    }

    /** 轨道传空＝回归，存量调用行为不变 */
    @Test
    void 轨道传空按回归处理() {
        assertTrue(service.isBatchEligible(flow("ONLINE", "PROD"), TestEnvironment.PROD, null));
        assertFalse(service.isBatchEligible(flow("AUTOMATION", "PROD"), TestEnvironment.PROD, null));
    }

    // ==================== 标签 ====================

    /** 生产的任务名不带后缀——存量报告的名字不能因为加了这个功能集体变样 */
    @Test
    void 只有测试环境带标签() {
        assertEquals("", TestEnvironment.tag(null));
        assertEquals("", TestEnvironment.tag(TestEnvironment.PROD));
        assertEquals("[测试环境]", TestEnvironment.tag(TestEnvironment.TEST));
        assertEquals("生产环境", TestEnvironment.label(null));
        assertEquals("测试环境", TestEnvironment.label("TEST"));
    }
}
