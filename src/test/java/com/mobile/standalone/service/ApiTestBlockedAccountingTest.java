package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「没跑成」和「没通过」要分开算。服务挂了的那一批压根没走到断言，
 * 进了通过率分母，人就会去查用例而不是去看服务。
 */
class ApiTestBlockedAccountingTest {

    /** 10 条里 6 条没跑成、4 条全过：通过率是 100%，不是 40% */
    @Test
    void blockedExcludedFromPassRateDenominator() {
        assertEquals(100.0, ApiTestStatsService.passRate(4, 10, 6));
        assertEquals(50.0, ApiTestStatsService.passRate(2, 10, 6));
    }

    /** 全部没跑成：给不出通过率，显示「—」而不是 0% */
    @Test
    void allBlockedHasNoPassRate() {
        assertNull(ApiTestStatsService.passRate(0, 5, 5));
        assertNull(ApiTestStatsService.passRate(0, 0, 0));
    }
}
