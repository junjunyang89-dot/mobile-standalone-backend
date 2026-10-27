package com.mobile.standalone.service.analysis;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisDeliveryStatusTest {
    @Test void completedResultIsFetchableWithoutCallback() {
        var value=AnalysisDeliveryStatus.describe("job-1","SUCCEEDED");
        assertEquals(true,value.get("resultReady"));
        assertEquals("/api/open/v1/analysis-jobs/job-1/result",value.get("resultUrl"));
        assertEquals(0,value.get("pollAfterSeconds"));
    }
    @Test void pendingWorkSuggestsPolling() {
        for(String state:new String[]{"QUEUED","RUNNING"}) {
            var value=AnalysisDeliveryStatus.describe("job-1",state);
            assertEquals(false,value.get("resultReady"));assertEquals(false,value.get("terminal"));
            assertEquals("",value.get("resultUrl"));assertEquals(5,value.get("pollAfterSeconds"));
        }
    }
    @Test void blockedAndFailedWorkStopPollingWithoutClaimingSuccess() {
        for(String state:new String[]{"FAILED","BLOCKED","CANCELLED"}) {
            var value=AnalysisDeliveryStatus.describe("job-1",state);
            assertEquals(false,value.get("resultReady"));assertEquals(true,value.get("terminal"));
            assertEquals(0,value.get("pollAfterSeconds"));
        }
    }
}
