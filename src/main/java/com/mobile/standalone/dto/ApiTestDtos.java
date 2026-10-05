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
        /** MANUAL | SCHEDULE，只由服务端设置：客户端能填的话，报告上的「触发方式」就不可信了 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String trigger;
    }

    @Data
    public static class RunResult {
        private boolean ok;
        private String message;
        private String environment;
        private int total;
        private int passed;
        private int failed;
        /**
         * 没跑成的条数：连不上、超时、证书错、用例不存在——<b>一条断言都没跑到</b>。
         * <p>不进通过率的分母。服务挂了的时候 200 条用例全红，
         * 看起来像 200 个 bug，实际是 1 个环境故障。
         */
        private int blocked;
        /** 线上执行时跳过的「只在测试跑」用例条数，不进 total */
        private int skipped;
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
        /**
         * 运行时这条用例的种类（BRANCH / REGRESSION / UNCLASSIFIED）。
         * <p>
         * 用例查不到时留空——那种情况下它确实是未知的，
         * 不要兜成某一类，否则一条「用例不存在」的 BLOCKED 会被算进某个桶的分母。
         */
        private String caseKind;
        /** NORMAL / NEGATIVE：报告里分得清「异常输入返回了报错」是对的，不是挂了 */
        private String intent;
        private String expectNote;
        private java.util.Map<String, String> reqHeaders;
        private String reqBody;
        private java.util.Map<String, String> respHeaders;
        /** 完整响应体，只给进程内用（按真实返回写断言），不落库不下发 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String rawBody;
        /**
         * 本步从响应 extract 出来的变量（仅进程内 / UI 流程回填用）。
         * 接口回归批次一般不看它——那里靠集合内 runtime 往下传。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private java.util.Map<String, String> extracted;
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
