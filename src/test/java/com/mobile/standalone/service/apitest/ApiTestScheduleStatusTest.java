package com.mobile.standalone.service.apitest;

import com.mobile.standalone.dto.ApiTestDtos.RunResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTestScheduleStatusTest {

    private static RunResult result(int passed, int failed, int blocked) {
        RunResult r = new RunResult();
        r.setTotal(passed + failed + blocked);
        r.setPassed(passed);
        r.setFailed(failed);
        r.setBlocked(blocked);
        r.setOk(failed == 0 && blocked == 0);
        return r;
    }

    /** 服务挂了那天：一条断言都没挂，只是没跑成，定时任务不该整条标红 */
    @Test
    void onlyBlockedIsBlockedNotFailed() {
        assertEquals("BLOCKED", ApiTestScheduleService.scheduleStatus(result(3, 0, 2)));
        assertEquals("BLOCKED", ApiTestScheduleService.scheduleStatus(result(0, 0, 5)));
    }

    /** 有真失败时仍要标红：那一条才是要人看的 */
    @Test
    void realFailureStaysFailed() {
        assertEquals("FAILED", ApiTestScheduleService.scheduleStatus(result(3, 1, 2)));
        assertEquals("PASSED", ApiTestScheduleService.scheduleStatus(result(5, 0, 0)));
    }
}
