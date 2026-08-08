package com.mobile.standalone.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class ChangeReviewDtos {
    private ChangeReviewDtos() {}

    @Data
    public static class DiffRequest {
        /** 可选：发版文档标题，仅回显 */
        private String weekLabel;
        private List<ProjectSpec> projects = new ArrayList<>();
        /** 可选：手写必测清单，用于标「改动未覆盖」 */
        private List<ChecklistItem> checklist = new ArrayList<>();
        /** 可选：发版文档里的 DB 变更备注全文，与 SQL 文件 diff 对照 */
        private String releaseDbNotes;
    }

    @Data
    public static class ProjectSpec {
        /** 展示名，如 完美电竞 Android */
        private String name;
        /** APP / H5 / JAVA / GOLANG / DB / OTHER */
        private String type;
        /** 本地仓库绝对路径 */
        private String path;
        /** 基线 ref，默认 master */
        private String baseRef = "master";
        /** 本周分支 / tag / commit */
        private String headRef;
        /** 可选 pathspec，限制 diff 范围，如 db/ migration/ */
        private List<String> pathspecs = new ArrayList<>();
    }

    @Data
    public static class ChecklistItem {
        private String id;
        /** 必测场景描述 */
        private String title;
        /** 用于匹配路径/模块的关键字，逗号或空格分隔 */
        private String keywords;
        /** 关联项目类型，空=全部 */
        private String projectType;
    }

    @Data
    public static class DiffResponse {
        private String weekLabel;
        private List<ProjectResult> projects = new ArrayList<>();
        private List<GapHint> gaps = new ArrayList<>();
        private DbCompareResult dbCompare;
    }

    @Data
    public static class ProjectResult {
        private String name;
        private String type;
        private String path;
        private String baseRef;
        private String headRef;
        private boolean ok;
        private String message;
        private int filesChanged;
        private int insertions;
        private int deletions;
        private List<FileChange> files = new ArrayList<>();
        private List<ModuleAgg> modules = new ArrayList<>();
        private String rawStat;
    }

    @Data
    public static class FileChange {
        private String path;
        private String status;
        private String category;
        private String module;
        private int insertions;
        private int deletions;
        /** 是否命中某条必测清单关键字 */
        private boolean checklistHit;
        private List<String> matchedChecklistIds = new ArrayList<>();
    }

    @Data
    public static class ModuleAgg {
        private String module;
        private int fileCount;
        private List<String> categories = new ArrayList<>();
        private boolean checklistHit;
    }

    @Data
    public static class GapHint {
        /** MODULE_UNCOVERED / CHECKLIST_NO_DIFF / SQL_NOT_IN_NOTES / NOTES_NO_SQL_FILE */
        private String kind;
        private String projectName;
        private String title;
        private String detail;
        private String severity;
    }

    @Data
    public static class DbCompareResult {
        private List<String> sqlFilesInDiff = new ArrayList<>();
        private List<String> sqlFilesMissingInNotes = new ArrayList<>();
        private List<String> noteLinesWithoutFileHint = new ArrayList<>();
        private String summary;
    }

    @Data
    public static class ImportBranchesRequest {
        /** 发版文档预览行：branches 字段文本 */
        private List<BranchRow> rows = new ArrayList<>();
    }

    @Data
    public static class BranchRow {
        private String summary;
        private String branches;
        private String dbChanges;
    }

    @Data
    public static class ImportBranchesResponse {
        private List<ParsedBranch> items = new ArrayList<>();
        private String releaseDbNotes;
    }

    @Data
    public static class ParsedBranch {
        private String repoHint;
        private String branch;
        private String fromSummary;
        private String suggestedType;
    }
}
