package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestSchedule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleRecentRunsTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 4, 8, 1);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 5, 8, 1);

    @Test
    void 新的在前且最多留七次() {
        String json = null;
        for (int i = 1; i <= 9; i++) {
            json = ScheduleRecentRuns.push(json, "PASSED", "run-" + i, T1.plusDays(i), null);
        }
        ApiTestSchedule s = new ApiTestSchedule();
        s.setRecentResults(json);
        List<ApiTestSchedule.RecentRun> runs = ScheduleRecentRuns.hydrate(s);
        assertEquals(7, runs.size());
        assertEquals("run-9", runs.get(0).getRunId());
        assertEquals("run-3", runs.get(6).getRunId());
    }

    @Test
    void 没有历史时用最后一次顶上() {
        ApiTestSchedule s = new ApiTestSchedule();
        s.setLastStatus("FAILED");
        s.setLastRunId("atr-old");
        s.setLastRunAt(T1);
        s.setLastMessage("2 条失败");
        List<ApiTestSchedule.RecentRun> runs = ScheduleRecentRuns.hydrate(s);
        assertEquals(1, runs.size());
        assertEquals("FAILED", runs.get(0).getStatus());
        assertEquals("atr-old", runs.get(0).getRunId());
        assertEquals(T1, runs.get(0).getAt());
    }

    @Test
    void 没有跑过就是空() {
        assertTrue(ScheduleRecentRuns.hydrate(new ApiTestSchedule()).isEmpty());
    }

    @Test
    void 失败也要留下好画红点() {
        String json = ScheduleRecentRuns.push(null, "FAILED", "atr-x", T2, "登录挂了");
        ApiTestSchedule s = new ApiTestSchedule();
        s.setRecentResults(json);
        s.setLastStatus("PASSED");
        List<ApiTestSchedule.RecentRun> runs = ScheduleRecentRuns.hydrate(s);
        assertEquals(1, runs.size());
        assertEquals("FAILED", runs.get(0).getStatus());
        assertEquals("登录挂了", runs.get(0).getMessage());
        assertNull(ScheduleRecentRuns.hydrate(new ApiTestSchedule()).stream()
                .findFirst().orElse(null));
    }
}
