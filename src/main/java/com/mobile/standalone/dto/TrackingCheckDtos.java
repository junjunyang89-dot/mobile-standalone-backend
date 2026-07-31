package com.mobile.standalone.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TrackingCheckDtos {
    private TrackingCheckDtos() {}

    @Data
    public static class VerifyRequest {
        /** Wiki 埋点文档 URL，如 https://wiki.shdev.net/pages/viewpage.action?pageId=74142172 */
        private String wikiUrl;
        /** 版本号，如 v4.9 */
        private String version;
        /** 可选：只校验某一个 element_name；空则校验该版本下全部埋点 */
        private String elementName;
        /**
         * 可选设备端：iOS / Android / H5；空则不限。
         * 按 Kibana 日志里的 os 字段过滤。
         */
        private String os;
        /** Kibana 导出/过滤后的 JSON 文本（单条、数组、NDJSON、hits 均可） */
        private String kibanaJson;
    }

    @Data
    public static class WikiTrackingRow {
        private String elementId;
        private String elementName;
        private String elementClassName;
        private String value;
        private String version;
        private String remarks;
        private String sectionTitle;
    }

    @Data
    public static class MatchResult {
        private WikiTrackingRow expected;
        /** found / missing / partial */
        private String status;
        private String message;
        private List<Map<String, Object>> matchedLogs = new ArrayList<>();
        /** 命中日志里出现的设备端，如 iOS / Android / H5 */
        private List<String> matchedOs = new ArrayList<>();
        /** value 字段要求的额外属性检查，如 tab_name */
        private List<String> valueChecks = new ArrayList<>();
    }

    @Data
    public static class VerifyResult {
        private String wikiPageId;
        private String wikiTitle;
        private String version;
        private String elementNameFilter;
        /** 设备端过滤：iOS / Android / H5 / 空=不限 */
        private String osFilter;
        private int wikiRowCount;
        private int kibanaLogCount;
        /** 按设备过滤后的日志条数 */
        private int kibanaLogCountAfterOsFilter;
        private int foundCount;
        private int missingCount;
        private String message;
        private List<MatchResult> results = new ArrayList<>();
        private List<String> logs = new ArrayList<>();
    }
}
