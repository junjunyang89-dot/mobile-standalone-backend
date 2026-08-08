package com.mobile.standalone.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class ApiTestDtos {
    private ApiTestDtos() {
    }

    @Data
    public static class RunRequest {
        private List<String> caseIds;
        /** TEST | PROD，默认 TEST */
        private String environment;
        /** 覆盖环境默认 token */
        private String tokenOverride;
        /** 本次执行统一改用该 URL（资产管理里的 id）；优先于用例自身 urlId */
        private String urlIdOverride;
        /**
         * 跑 PROD 时必须为 true，防止误点。
         */
        private Boolean confirmProd;
    }

    @Data
    public static class RunResult {
        private boolean ok;
        private String message;
        private String environment;
        private int total;
        private int passed;
        private int failed;
        private long durationMs;
        /** 落库后的执行记录 id */
        private String runId;
        private List<CaseResult> cases = new ArrayList<>();
    }

    @Data
    public static class CaseResult {
        private String id;
        private String name;
        private String method;
        private String url;
        private String status;
        private Integer httpStatus;
        private Long durationMs;
        private String error;
        private List<String> assertionResults = new ArrayList<>();
        /** 响应体截断预览 */
        private String bodyPreview;
    }

    @Data
    public static class StatsResponse {
        private LocalDateTime updatedAt;
        private long cases;
        private long casesDelta7d;
        private Double passRate;
        private Double passRateDelta;
        private long failed;
        private long failedDelta;
        private Long avgLatencyMs;
        private Long avgLatencyDeltaMs;
        private List<RecentRunItem> recentRuns = new ArrayList<>();
        private List<CollectionStat> collections = new ArrayList<>();
    }

    @Data
    public static class RecentRunItem {
        private String id;
        private String caseId;
        private String name;
        private String method;
        private String path;
        private String status;
        private Long durationMs;
        private LocalDateTime createdAt;
    }

    @Data
    public static class CollectionStat {
        private String project;
        private String name;
        private long caseCount;
        private Double passRate;
    }
}
