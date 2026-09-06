package com.mobile.standalone.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class WebTestDtos {
    private WebTestDtos() {}

    @Data
    public static class RunRequest {
        /** 旧：临时 URL 冒烟 */
        private List<CaseSpec> cases = new ArrayList<>();
        /** 新：库内编排用例 id */
        private List<String> caseIds = new ArrayList<>();
        /** 执行时覆盖 token（可选） */
        private String tokenOverride;
        private Boolean chromiumOnly = true;
    }

    @Data
    public static class CaseSpec {
        private String name;
        private String channel = "H5";
        private String url;
        private String authMode = "none";
        private String token;
        private String authQueryKey = "token";
        private String cookieDomain;
        /** Cookie 登录态，JSON 对象「名→值」。有些活动页要好几个 cookie 都在才认 */
        private String authCookies;
        private String h5PageId;
    }

    @Data
    public static class RunResult {
        private boolean ok;
        private String message;
        private int total;
        private int passed;
        private int failed;
        private int skipped;
        private long durationMs;
        private String reportDir;
        private List<CaseResult> cases = new ArrayList<>();
        private String logTail;
    }

    @Data
    public static class CaseResult {
        private String name;
        private String channel;
        private String url;
        private String status;
        private String error;
        private String screenshotPath;
        private String requirementRef;
    }

    @Data
    public static class StatusResponse {
        private boolean enabled;
        private boolean projectReady;
        private boolean nodeModulesPresent;
        private String projectDir;
        private String message;
        private List<String> supportedChannels = List.of("H5", "WEB");
        private List<String> plannedChannels = List.of("MINIPROGRAM");
    }
}
