package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.config.ReleaseJenkinsProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseJenkinsPackageServiceTest {

    @Test
    void resolvesConfiguredJobsAndSkipsUnknown() {
        ReleaseJenkinsProperties props = new ReleaseJenkinsProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://jenkins.example");
        // 不配账号：只验证 job 映射与返回结构，不打真实 Jenkins
        props.setUsername("");
        props.setPassword("");
        Map<String, String> jobs = new LinkedHashMap<>();
        jobs.put("app-swift", "appswift_package_prod");
        props.setJobs(jobs);

        ReleaseJenkinsPackageService svc = new ReleaseJenkinsPackageService(props, new ObjectMapper());
        Map<String, Map<String, Object>> batch = svc.statusForProjects(java.util.List.of("app-swift", "unknown-x"));
        assertTrue(batch.containsKey("app-swift"));
        assertFalse(batch.containsKey("unknown-x"));
        assertEquals(true, batch.get("app-swift").get("configured"));
        assertEquals("appswift_package_prod", batch.get("app-swift").get("job"));
        assertEquals("未配置 Jenkins 账号", batch.get("app-swift").get("error"));
    }
}
