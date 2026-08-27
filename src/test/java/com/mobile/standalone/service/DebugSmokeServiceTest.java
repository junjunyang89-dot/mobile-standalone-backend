package com.mobile.standalone.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebugSmokeServiceTest {

    private DebugSmokeService service;

    @BeforeEach
    void setUp() {
        service = new DebugSmokeService();
    }

    @Test
    void jobChain_createThenGet_marksDone() {
        Map<String, Object> ping = service.ping();
        String traceId = String.valueOf(ping.get("traceId"));

        Map<String, Object> created = service.createJob(Map.of(
                "name", "unit-job",
                "traceId", traceId
        ));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) created.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) data.get("job");
        String id = String.valueOf(job.get("id"));

        Map<String, Object> got = service.getJob(id);
        assertNotNull(got);
        @SuppressWarnings("unchecked")
        Map<String, Object> gotJob = (Map<String, Object>) ((Map<?, ?>) got.get("data")).get("job");
        assertEquals("DONE", gotJob.get("status"));
    }
}
