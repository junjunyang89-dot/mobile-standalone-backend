package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次装包策略。
 * <p>
 * 装包从流程里提出来，是因为它不是用例：版本属于「这次执行」，不属于流程；
 * 一批只该装一次，靠「排在第一条」保证太脆；混在勾选列表里还会稀释通过率。
 */
class BatchInstallStrategyTest {

    @Test
    void 各档策略解析() {
        assertEquals(BatchInstallStrategy.LATEST, BatchInstallStrategy.from("LATEST"));
        assertEquals(BatchInstallStrategy.SPECIFIC, BatchInstallStrategy.from("specific"));
        assertEquals(BatchInstallStrategy.PREVIOUS_THEN_LATEST,
                BatchInstallStrategy.from(" previous_then_latest "));
    }

    /**
     * 认不出来按「不装」处理。
     * 宁可用设备上现有的包跑一轮，也不要因为一个拼错的策略名去装一个没人预期的版本——
     * 后者会让整批结果验的是错的包，而报告上看不出来。
     */
    @Test
    void 认不出的策略退回不装() {
        assertEquals(BatchInstallStrategy.NONE, BatchInstallStrategy.from("装最新"));
        assertEquals(BatchInstallStrategy.NONE, BatchInstallStrategy.from(null));
        assertEquals(BatchInstallStrategy.NONE, BatchInstallStrategy.from(""));
    }

    /** 升级测试是唯一要先垫一个旧版本的 */
    @Test
    void 只有升级测试要先装旧版() {
        assertTrue(BatchInstallStrategy.PREVIOUS_THEN_LATEST.needsPreviousFirst());
        assertFalse(BatchInstallStrategy.LATEST.needsPreviousFirst());
        assertFalse(BatchInstallStrategy.NONE.needsPreviousFirst());
        assertFalse(BatchInstallStrategy.SPECIFIC.needsPreviousFirst());
    }

    @Test
    void 最终装最新版的有两档() {
        assertTrue(BatchInstallStrategy.LATEST.installsLatest());
        assertTrue(BatchInstallStrategy.PREVIOUS_THEN_LATEST.installsLatest());
        assertFalse(BatchInstallStrategy.SPECIFIC.installsLatest(), "指定版本就是指定，不该被最新版覆盖");
        assertFalse(BatchInstallStrategy.NONE.installsLatest());
    }

    /** 每一档都要有人话说明——策略名本身说不清「先装旧版再覆盖」这种事 */
    @Test
    void 每档都有可读说明() {
        for (BatchInstallStrategy s : BatchInstallStrategy.values()) {
            assertFalse(s.describe().isBlank(), s.name());
        }
        assertTrue(BatchInstallStrategy.PREVIOUS_THEN_LATEST.describe().contains("升级"),
                BatchInstallStrategy.PREVIOUS_THEN_LATEST.describe());
    }
}
