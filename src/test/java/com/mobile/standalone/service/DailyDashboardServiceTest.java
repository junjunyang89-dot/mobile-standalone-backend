package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyDashboardServiceTest {

    @Test
    void 用例来源按生成器写进created_by的标记识别() {
        assertEquals("AI", DailyDashboardService.caseSource("AI 生成"));
        assertEquals("SOURCE", DailyDashboardService.caseSource("源码生成"));
        assertEquals("CAPTURE", DailyDashboardService.caseSource("抓包"));
        assertEquals("ENDPOINT", DailyDashboardService.caseSource("涉及接口"));
        assertEquals("ENDPOINT", DailyDashboardService.caseSource("接口清单"));
        assertEquals("SEED", DailyDashboardService.caseSource("seed-appuser"));
        assertEquals("MANUAL", DailyDashboardService.caseSource("yangjun"));
        assertEquals("MANUAL", DailyDashboardService.caseSource(null));
    }
}
