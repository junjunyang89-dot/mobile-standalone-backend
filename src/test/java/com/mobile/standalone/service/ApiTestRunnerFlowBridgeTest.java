package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiTestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTestRunnerFlowBridgeTest {

    @Test
    void extract覆盖与用例自带合并且本步优先() {
        ApiTestCase c = new ApiTestCase();
        c.setId("c1");
        c.setName("详情");
        c.setExtractJson("{\"teamA\":\"$.old.a\",\"keep\":\"$.k\"}");

        ApiTestCase merged = ApiTestRunnerService.withExtractOverride(c,
                "{\"teamA\":\"$.result.match.team1DTO.name\",\"teamB\":\"$.result.match.team2DTO.name\"}");

        assertNotSame(c, merged);
        assertTrue(merged.getExtractJson().contains("team1DTO"));
        assertTrue(merged.getExtractJson().contains("\"keep\""));
        assertTrue(merged.getExtractJson().contains("teamB"));
        // 原用例未被改写
        assertEquals("{\"teamA\":\"$.old.a\",\"keep\":\"$.k\"}", c.getExtractJson());
    }

    @Test
    void extract非法JSON抛错() {
        ApiTestCase c = new ApiTestCase();
        assertThrows(IllegalArgumentException.class,
                () -> ApiTestRunnerService.withExtractOverride(c, "not-json"));
    }
}
