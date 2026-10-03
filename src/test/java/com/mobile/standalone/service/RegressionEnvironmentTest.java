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

    private final UiFlowService service = new UiFlowService(null, null, null, null);

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

    /**
     * 轨道仍然是硬过滤：仅自动化编排的流程进不了回归批次。
     * <p>
     * 但<b>环境对自动化流程不再是过滤条件</b>——见下一条。
     */
    @Test
    void 轨道是硬过滤() {
        UiFlow autoTest = flow("AUTOMATION", "TEST");

        assertTrue(service.isBatchEligible(autoTest, TestEnvironment.TEST, RunTrack.AUTOMATION));
        assertFalse(service.isBatchEligible(autoTest, TestEnvironment.TEST, RunTrack.REGRESSION),
                "自动化流程不该混进回归批次");
    }

    /**
     * 自动化流程不绑环境。
     * <p>
     * 一条用例（「协议勾选框可勾选」）在测试包和线上包上是同一件事，它的自动化实现也该是同一条流程。
     * 绑了环境，同一个用例要写两遍流程，而且一条用例得挂两条流程——关联关系从 N:1 退化成 N:N。
     * 环境差异只在「装哪个包、用哪套数据」，那是执行时的参数。
     */
    @Test
    void 自动化流程两个环境都能跑() {
        UiFlow autoTest = flow("AUTOMATION", "TEST");

        assertTrue(service.isBatchEligible(autoTest, TestEnvironment.TEST, RunTrack.AUTOMATION));
        assertTrue(service.isBatchEligible(autoTest, TestEnvironment.PROD, RunTrack.AUTOMATION),
                "流程上标着 TEST 也要能跑线上包——环境不再是流程的属性");
    }

    // ==================== 包里有没有这个功能 ====================

    /**
     * 本组最要紧的一条。
     * <p>
     * 「发版前线上回归」和「APP 线上回归」<b>都是生产环境</b>，但跑的包不同：
     * 前者是本次待发布的 debug 包（有新功能），后者是上个版本的线上包（没有）。
     * 本版新功能的流程跑到线上包上必然失败，而失败原因是「包里没这功能」，跟质量无关——
     * 这种红会把真问题淹掉。environment 这一维表达不了这个区别。
     */
    @Test
    void 跑已发布线上包时排除本版新功能的流程() {
        UiFlow newFeature = flow("ONLINE", "PROD");
        newFeature.setReleaseStage("TESTING");

        assertTrue(service.isBatchEligible(newFeature, TestEnvironment.PROD, RunTrack.REGRESSION, false),
                "发版前线上回归跑的是 debug 包，新功能流程要跑");
        assertFalse(service.isBatchEligible(newFeature, TestEnvironment.PROD, RunTrack.REGRESSION, true),
                "APP 线上回归跑的是上个版本线上包，里面没有这个功能");
    }

    /** 存量流程一条都不能掉出线上回归——没设过 releaseStage 的按 ONLINE 处理 */
    @Test
    void 没设过阶段的按线上处理() {
        UiFlow legacy = flow("ONLINE", "PROD");

        assertTrue(service.isBatchEligible(legacy, TestEnvironment.PROD, RunTrack.REGRESSION, true),
                "默认值判错会让存量流程整批从线上回归里消失，而且不报错");
    }

    @Test
    void 已上线的流程两种包都跑() {
        UiFlow stable = flow("ONLINE", "PROD");
        stable.setReleaseStage("ONLINE");

        assertTrue(service.isBatchEligible(stable, TestEnvironment.PROD, RunTrack.REGRESSION, false));
        assertTrue(service.isBatchEligible(stable, TestEnvironment.PROD, RunTrack.REGRESSION, true));
    }

    // ==================== 纯功能验证流程可以不绑环境 ====================

    /**
     * 真实场景：测试环境回归通过后，同一个 debug 包切到生产环境再跑一遍「发版前线上回归」。
     * 这一轮要跑的流程和测试环境回归<b>完全一样</b>——差别只在后端连哪边，
     * 而后端是包里配的，不是流程的属性。按环境隔离的话，刚在测试环境写好的新流程
     * 在线上这边看不见，只能重写一遍。
     */
    @Test
    void 不绑环境的回归流程两个环境都能跑() {
        UiFlow f = flow("ONLINE", "TEST");
        f.setEnvBound(false);

        assertTrue(service.isBatchEligible(f, TestEnvironment.TEST, RunTrack.REGRESSION));
        assertTrue(service.isBatchEligible(f, TestEnvironment.PROD, RunTrack.REGRESSION),
                "标了 TEST 也要能进线上回归——功能验证跟连哪个后端无关");
    }

    /**
     * 这一条守住的是安全边界：装包类流程<b>必须</b>绑环境。
     * 测试环境装蒲公英 debug 包、线上装渠道包，是两套完全不同的路径，
     * 混了会在线上回归里装上一个 debug 包。
     */
    @Test
    void 默认仍然绑环境() {
        UiFlow f = flow("ONLINE", "PROD");

        assertFalse(service.isBatchEligible(f, TestEnvironment.TEST, RunTrack.REGRESSION),
                "没显式标不绑的一律按绑处理——存量流程一条都不能改行为");
    }

    @Test
    void 显式标了绑定就按绑处理() {
        UiFlow f = flow("ONLINE", "PROD");
        f.setEnvBound(true);

        assertFalse(service.isBatchEligible(f, TestEnvironment.TEST, RunTrack.REGRESSION));
    }

    /**
     * 回归流程<b>照旧绑环境</b>：线上回归和测试回归本来就走不同的流程
     * （装包方式、渠道都不同），这一条不能跟着一起放开。
     */
    @Test
    void 回归流程仍然按环境隔离() {
        UiFlow onlineFlow = flow("ONLINE", "PROD");

        assertTrue(service.isBatchEligible(onlineFlow, TestEnvironment.PROD, RunTrack.REGRESSION));
        assertFalse(service.isBatchEligible(onlineFlow, TestEnvironment.TEST, RunTrack.REGRESSION),
                "线上回归的流程不能混进测试环境回归");
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

    /** 任务名两边都带环境标记，列表里不用点开就能分清 */
    @Test
    void 任务名带环境标记() {
        assertEquals("[生产环境]", TestEnvironment.tag(null));
        assertEquals("[生产环境]", TestEnvironment.tag(TestEnvironment.PROD));
        assertEquals("[测试环境]", TestEnvironment.tag(TestEnvironment.TEST));
        assertEquals("生产环境", TestEnvironment.label(null));
        assertEquals("测试环境", TestEnvironment.label("TEST"));
    }
}
