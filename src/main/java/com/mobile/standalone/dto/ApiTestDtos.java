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
        /**
         * 按集合执行。给了它就不用给 caseIds——集合内的启用用例会按 sortOrder 顺序全跑，
         * 顺序在这里是有意义的：登录用例 extract 出来的 token，后面的用例才引用得到。
         */
        private String collectionId;
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
        /** 本次是按哪个集合跑的（直接选用例跑时为空） */
        private String collectionId;
        private String collectionName;
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
        /**
         * 实际发出去的请求头/请求体，和实际收到的响应头。
         * <p>
         * 只给一个「断言失败」，人还得自己去猜发了什么、收到了什么——
         * 于是拿 Postman 再手工重放一遍。那一步是这套东西本该省掉的。
         */
        private java.util.Map<String, String> reqHeaders;
        private String reqBody;
        private java.util.Map<String, String> respHeaders;
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
