package com.mobile.standalone.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * backend-0825 提测演练：内存任务链 ping → create job → get job。
 */
@Service
public class DebugSmokeService {

    private final ConcurrentHashMap<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    public Map<String, Object> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("module", "debug-smoke");
        out.put("traceId", "smk-" + shortId());
        out.put("ts", System.currentTimeMillis());
        return out;
    }

    public Map<String, Object> createJob(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        String name = stringVal(body.get("name"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String traceId = stringVal(body.get("traceId"));
        if (traceId.isBlank()) {
            traceId = "smk-" + shortId();
        }

        String id = "job-" + shortId();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", id);
        job.put("name", name);
        job.put("status", "QUEUED");
        job.put("traceId", traceId);
        job.put("createdAt", System.currentTimeMillis());
        jobs.put(id, job);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job", new LinkedHashMap<>(job));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("message", "ok");
        out.put("data", data);
        return out;
    }

    public Map<String, Object> getJob(String id) {
        Map<String, Object> job = jobs.get(id);
        if (job == null) {
            return null;
        }
        if ("QUEUED".equals(job.get("status"))) {
            job.put("status", "DONE");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("passed", true);
            result.put("checks", 3);
            job.put("result", result);
            job.put("finishedAt", System.currentTimeMillis());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job", new LinkedHashMap<>(job));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("message", "ok");
        out.put("data", data);
        return out;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String stringVal(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
