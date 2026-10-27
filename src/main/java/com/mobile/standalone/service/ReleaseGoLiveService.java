package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import com.mobile.standalone.service.releasemerge.ReleaseMergeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发版当天上线看板：按项目汇总分支，分支级勾选「待合并/已合并」「待上线/已上线」（前端项目置顶）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseGoLiveService {

    private static final Pattern PROJECT_BRANCH = Pattern.compile("^(.+?)\\s*[:：]\\s*(.+)$");
    private static final Pattern ORDER_NOISE = Pattern.compile(
            "[，,]\\s*(按)?顺序发版.*$|[，,]\\s*(发版顺序|发版次序|注意).*$|\\s+(按)?顺序发版\\s*$");
    /** 前端仓置顶顺序 */
    private static final List<String> FRONTEND_ORDER = List.of("app-swift", "app-swift-v3");
    private static final String STATE_SEP = "\t";

    private final ReleaseDocService releaseDocService;
    private final ReleaseDocProperties props;
    private final JdbcTemplate jdbcTemplate;
    private final ReleaseMergeService releaseMergeService;
    private final ReleaseJenkinsPackageService jenkinsPackageService;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS release_go_live_state (
                        wiki_page_id VARCHAR(64) NOT NULL,
                        project_key VARCHAR(128) NOT NULL,
                        branch_key VARCHAR(512) NOT NULL DEFAULT '',
                        merged_master TINYINT(1) NOT NULL DEFAULT 0,
                        released TINYINT(1) NOT NULL DEFAULT 0,
                        master_check_status VARCHAR(32) NULL,
                        master_check_message VARCHAR(512) NULL,
                        master_check_ahead INT NULL,
                        merged_at DATETIME NULL,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (wiki_page_id, project_key, branch_key),
                        INDEX idx_rgl_page (wiki_page_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            migrateBranchKeyColumn();
            migrateMasterCheckColumns();
            log.info("[release-go-live] 表已就绪");
        } catch (Exception e) {
            log.warn("[release-go-live] 建表失败: {}", e.getMessage());
        }
    }

    /** 兼容旧表（无 branch_key）升级为分支级主键。 */
    private void migrateBranchKeyColumn() {
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList("""
                    SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'release_go_live_state'
                       AND COLUMN_NAME = 'branch_key'
                    """);
            if (cols.isEmpty()) {
                jdbcTemplate.execute("""
                        ALTER TABLE release_go_live_state
                          ADD COLUMN branch_key VARCHAR(512) NOT NULL DEFAULT '' AFTER project_key
                        """);
                try {
                    jdbcTemplate.execute("""
                            ALTER TABLE release_go_live_state
                              DROP PRIMARY KEY,
                              ADD PRIMARY KEY (wiki_page_id, project_key, branch_key)
                            """);
                } catch (Exception e) {
                    log.debug("[release-go-live] 调整主键: {}", e.getMessage());
                }
                log.info("[release-go-live] 已迁移 branch_key 列");
            }
        } catch (Exception e) {
            log.debug("[release-go-live] 迁移检查跳过: {}", e.getMessage());
        }
    }

    private void migrateMasterCheckColumns() {
        ensureColumn("master_check_status", "VARCHAR(32) NULL");
        ensureColumn("master_check_message", "VARCHAR(512) NULL");
        ensureColumn("master_check_ahead", "INT NULL");
        ensureColumn("merged_at", "DATETIME NULL");
    }

    private void ensureColumn(String name, String def) {
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList("""
                    SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'release_go_live_state'
                       AND COLUMN_NAME = ?
                    """, name);
            if (cols.isEmpty()) {
                jdbcTemplate.execute("ALTER TABLE release_go_live_state ADD COLUMN " + name + " " + def);
                log.info("[release-go-live] 已加列 {}", name);
            }
        } catch (Exception e) {
            log.debug("[release-go-live] 加列 {} 跳过: {}", name, e.getMessage());
        }
    }

    public Map<String, Object> board(String wikiUrl) {
        List<IssueRow> rows = releaseDocService.loadIssueRowsFromWikiUrl(wikiUrl);
        String pageId = ReleaseDocService.extractPageId(wikiUrl);
        Map<String, ProjectAgg> byProject = aggregateByProject(rows);
        Map<String, ProjectState> states = loadStates(pageId);
        List<String> orderHints = extractOrderHints(rows);

        List<Map<String, Object>> projects = new ArrayList<>();
        int branchTotal = 0;
        int mergedCount = 0;
        int releasedCount = 0;
        for (Map.Entry<String, ProjectAgg> e : byProject.entrySet()) {
            String key = e.getKey();
            ProjectAgg agg = e.getValue();
            List<Map<String, Object>> branchItems = new ArrayList<>();
            boolean allMerged = true;
            boolean allReleased = true;
            for (String branch : agg.branches) {
                ProjectState st = resolveState(states, key, branch);
                Map<String, Object> bi = new LinkedHashMap<>();
                bi.put("branch", branch);
                bi.put("mergedMaster", st.mergedMaster);
                bi.put("released", st.released);
                bi.put("issues", new ArrayList<>(agg.issuesForBranch(branch)));
                bi.put("masterCheck", masterCheckVo(st));
                bi.put("mergedAt", st.mergedAt);
                branchItems.add(bi);
                if (!isPlaceholderBranch(branch)) {
                    branchTotal++;
                    if (st.mergedMaster) {
                        mergedCount++;
                    } else {
                        allMerged = false;
                    }
                    if (st.released) {
                        releasedCount++;
                    } else {
                        allReleased = false;
                    }
                }
            }
            if (branchItems.isEmpty()) {
                allMerged = false;
                allReleased = false;
            }
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("project", key);
            vo.put("layer", isFrontendProject(key) ? "frontend" : "backend");
            vo.put("branches", new ArrayList<>(agg.branches));
            vo.put("branchItems", branchItems);
            vo.put("issues", agg.issues);
            vo.put("mergedMaster", allMerged && !branchItems.isEmpty());
            vo.put("released", allReleased && !branchItems.isEmpty());
            vo.put("branchCount", (int) agg.branches.stream().filter(b -> !isPlaceholderBranch(b)).count());
            vo.put("mergedCount", (int) branchItems.stream()
                    .filter(bi -> !isPlaceholderBranch(String.valueOf(bi.get("branch")))
                            && Boolean.TRUE.equals(bi.get("mergedMaster")))
                    .count());
            vo.put("releasedBranchCount", (int) branchItems.stream()
                    .filter(bi -> !isPlaceholderBranch(String.valueOf(bi.get("branch")))
                            && Boolean.TRUE.equals(bi.get("released")))
                    .count());
            projects.add(vo);
        }
        sortProjectsFrontendFirst(projects);
        attachJenkinsStatus(projects);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wikiPageId", pageId);
        out.put("wikiPageUrl", wikiUrl);
        out.put("issueCount", rows.size());
        out.put("projectCount", projects.size());
        out.put("branchCount", branchTotal);
        out.put("mergedCount", mergedCount);
        out.put("releasedCount", releasedCount);
        out.put("orderHints", orderHints);
        out.put("hasOrderHints", !orderHints.isEmpty());
        out.put("projects", projects);
        return out;
    }

    /** 给每个项目挂上 Jenkins 大包最新状态（有配置才查）。 */
    private void attachJenkinsStatus(List<Map<String, Object>> projects) {
        if (projects == null || projects.isEmpty()) return;
        List<String> codes = projects.stream()
                .map(p -> String.valueOf(p.get("project")))
                .filter(s -> s != null && !s.isBlank())
                .toList();
        Map<String, Map<String, Object>> byProj = jenkinsPackageService.statusForProjects(codes);
        for (Map<String, Object> p : projects) {
            String code = String.valueOf(p.get("project"));
            Map<String, Object> st = byProj.get(code);
            if (st != null) {
                p.put("jenkins", st);
            }
        }
    }

    public Map<String, Object> saveState(
            String wikiPageId, String project, String branch, Boolean mergedMaster, Boolean released) {
        String pageId = blankToEmpty(wikiPageId);
        String proj = blankToEmpty(project);
        String br = cleanBranch(blankToEmpty(branch));
        if (pageId.isEmpty() || proj.isEmpty()) {
            throw new IllegalArgumentException("wikiPageId 与 project 不能为空");
        }
        if (br.isEmpty()) {
            throw new IllegalArgumentException("branch 不能为空（请按分支勾选）");
        }
        ProjectState cur = resolveState(loadStates(pageId), proj, br);
        boolean mm = mergedMaster != null ? mergedMaster : cur.mergedMaster;
        boolean rel = released != null ? released : cur.released;

        Map<String, Object> masterCheck;
        if (mergedMaster != null && mergedMaster) {
            // 点成「已合并」时立刻对照 remote master 核对，结果写回库并返回前端展示
            masterCheck = verifyAgainstMaster(proj, br);
        } else if (mergedMaster != null) {
            masterCheck = clearMasterCheck();
        } else {
            masterCheck = masterCheckVo(cur);
        }

        // 刚点成已合并 → 记 merged_at；取消合并 → 清空；仅改上线则保留原时间
        String mergedAtSql;
        Object mergedAtParam;
        if (mergedMaster != null && mergedMaster) {
            mergedAtSql = "NOW()";
            mergedAtParam = null;
        } else if (mergedMaster != null) {
            mergedAtSql = "NULL";
            mergedAtParam = null;
        } else if (cur.mergedAt != null && !cur.mergedAt.isBlank()) {
            mergedAtSql = "?";
            mergedAtParam = cur.mergedAt;
        } else {
            mergedAtSql = "NULL";
            mergedAtParam = null;
        }

        if (mergedAtParam != null) {
            jdbcTemplate.update("""
                    INSERT INTO release_go_live_state
                      (wiki_page_id, project_key, branch_key, merged_master, released,
                       master_check_status, master_check_message, master_check_ahead, merged_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                      merged_master = VALUES(merged_master),
                      released = VALUES(released),
                      master_check_status = VALUES(master_check_status),
                      master_check_message = VALUES(master_check_message),
                      master_check_ahead = VALUES(master_check_ahead),
                      merged_at = VALUES(merged_at),
                      updated_at = NOW()
                    """,
                    pageId, proj, br, mm ? 1 : 0, rel ? 1 : 0,
                    masterCheck.get("status"),
                    masterCheck.get("message"),
                    masterCheck.get("ahead"),
                    mergedAtParam);
        } else if ("NOW()".equals(mergedAtSql)) {
            jdbcTemplate.update("""
                    INSERT INTO release_go_live_state
                      (wiki_page_id, project_key, branch_key, merged_master, released,
                       master_check_status, master_check_message, master_check_ahead, merged_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE
                      merged_master = VALUES(merged_master),
                      released = VALUES(released),
                      master_check_status = VALUES(master_check_status),
                      master_check_message = VALUES(master_check_message),
                      master_check_ahead = VALUES(master_check_ahead),
                      merged_at = IF(VALUES(merged_master)=1,
                                     COALESCE(merged_at, NOW()),
                                     NULL),
                      updated_at = NOW()
                    """,
                    pageId, proj, br, mm ? 1 : 0, rel ? 1 : 0,
                    masterCheck.get("status"),
                    masterCheck.get("message"),
                    masterCheck.get("ahead"));
        } else {
            jdbcTemplate.update("""
                    INSERT INTO release_go_live_state
                      (wiki_page_id, project_key, branch_key, merged_master, released,
                       master_check_status, master_check_message, master_check_ahead, merged_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW())
                    ON DUPLICATE KEY UPDATE
                      merged_master = VALUES(merged_master),
                      released = VALUES(released),
                      master_check_status = VALUES(master_check_status),
                      master_check_message = VALUES(master_check_message),
                      master_check_ahead = VALUES(master_check_ahead),
                      merged_at = IF(VALUES(merged_master)=1, merged_at, NULL),
                      updated_at = NOW()
                    """,
                    pageId, proj, br, mm ? 1 : 0, rel ? 1 : 0,
                    masterCheck.get("status"),
                    masterCheck.get("message"),
                    masterCheck.get("ahead"));
        }

        ProjectState after = resolveState(loadStates(pageId), proj, br);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("wikiPageId", pageId);
        out.put("project", proj);
        out.put("branch", br);
        out.put("mergedMaster", mm);
        out.put("released", rel);
        out.put("mergedAt", after.mergedAt);
        out.put("masterCheck", masterCheck);
        return out;
    }

    /**
     * 每日发版校对：按「项目：分支」文本生成当日校对表（含状态 + Jenkins 构建号）。
     */
    public Map<String, Object> dailyChecklist(String date, String text) {
        String day = blankToEmpty(date);
        if (day.isEmpty()) {
            day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        }
        String pageId = "daily-" + day;
        List<ProjectBranch> pairs = parseProjectBranches(text, props.getBranchKeywords() != null
                ? props.getBranchKeywords() : List.of());
        // 也支持无关键字时的简单「项目：分支」行
        if (pairs.isEmpty() && text != null && !text.isBlank()) {
            for (String rawLine : text.split("\\R")) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher m = PROJECT_BRANCH.matcher(line);
                if (!m.find()) continue;
                String project = normalizeProject(m.group(1));
                String branch = cleanBranch(m.group(2));
                if (!project.isBlank() && !branch.isBlank()) {
                    pairs.add(new ProjectBranch(project, branch));
                }
            }
        }
        Map<String, ProjectState> states = loadStates(pageId);
        List<String> projectCodes = pairs.stream().map(ProjectBranch::project).distinct().toList();
        Map<String, Map<String, Object>> jenkins = jenkinsPackageService.statusForProjects(projectCodes);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectBranch pb : pairs) {
            ProjectState st = resolveState(states, pb.project(), pb.branch());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", pb.project());
            row.put("branch", pb.branch());
            row.put("mergedMaster", st.mergedMaster);
            row.put("released", st.released);
            row.put("mergedAt", st.mergedAt);
            row.put("masterCheck", masterCheckVo(st));
            row.put("jenkins", jenkins.get(pb.project()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day);
        out.put("wikiPageId", pageId);
        out.put("rowCount", rows.size());
        out.put("rows", rows);
        return out;
    }

    /**
     * 单独再核对一次是否已在 master（不改勾选状态）。
     */
    public Map<String, Object> verifyMaster(String wikiPageId, String project, String branch) {
        String pageId = blankToEmpty(wikiPageId);
        String proj = blankToEmpty(project);
        String br = cleanBranch(blankToEmpty(branch));
        if (pageId.isEmpty() || proj.isEmpty() || br.isEmpty()) {
            throw new IllegalArgumentException("wikiPageId、project、branch 不能为空");
        }
        Map<String, Object> masterCheck = verifyAgainstMaster(proj, br);
        ProjectState cur = resolveState(loadStates(pageId), proj, br);
        jdbcTemplate.update("""
                INSERT INTO release_go_live_state
                  (wiki_page_id, project_key, branch_key, merged_master, released,
                   master_check_status, master_check_message, master_check_ahead, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                  master_check_status = VALUES(master_check_status),
                  master_check_message = VALUES(master_check_message),
                  master_check_ahead = VALUES(master_check_ahead),
                  updated_at = NOW()
                """,
                pageId, proj, br, cur.mergedMaster ? 1 : 0, cur.released ? 1 : 0,
                masterCheck.get("status"),
                masterCheck.get("message"),
                masterCheck.get("ahead"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("wikiPageId", pageId);
        out.put("project", proj);
        out.put("branch", br);
        out.put("masterCheck", masterCheck);
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> verifyAgainstMaster(String project, String branch) {
        try {
            Map<String, Object> checked = releaseMergeService.check(project + ": " + branch, null, null);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) checked.get("rows");
            if (rows == null || rows.isEmpty()) {
                return masterCheck("ERROR", "核对无结果", null);
            }
            Map<String, Object> row = rows.get(0);
            String status = String.valueOf(row.getOrDefault("status", ""));
            Integer ahead = row.get("ahead") instanceof Number n ? n.intValue() : null;
            if ("ALREADY".equals(status)) {
                return masterCheck("IN_MASTER", "已确认在 master 里", 0);
            }
            if ("OK".equals(status) || "WARN".equals(status)) {
                int a = ahead != null ? ahead : -1;
                return masterCheck("NOT_IN_MASTER",
                        a >= 0 ? ("还没进 master，还差 " + a + " 个提交") : "还没进 master",
                        ahead);
            }
            List<String> blockers = row.get("blockers") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            String msg = blockers.isEmpty()
                    ? ("核对失败：" + status)
                    : String.join("；", blockers);
            if (msg.length() > 500) {
                msg = msg.substring(0, 500);
            }
            return masterCheck("ERROR", msg, ahead);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.length() > 500) {
                msg = msg.substring(0, 500);
            }
            log.warn("[release-go-live] 核对 master 失败 {} {}: {}", project, branch, msg);
            return masterCheck("ERROR", msg, null);
        }
    }

    private static Map<String, Object> clearMasterCheck() {
        return masterCheck(null, null, null);
    }

    private static Map<String, Object> masterCheck(String status, String message, Integer ahead) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        m.put("ahead", ahead);
        return m;
    }

    private static Map<String, Object> masterCheckVo(ProjectState st) {
        if (st == null || st.masterCheckStatus == null || st.masterCheckStatus.isBlank()) {
            return clearMasterCheck();
        }
        return masterCheck(st.masterCheckStatus, st.masterCheckMessage, st.masterCheckAhead);
    }

    public Map<String, Object> resetStates(String wikiPageId) {
        String pageId = blankToEmpty(wikiPageId);
        if (pageId.isEmpty()) {
            throw new IllegalArgumentException("wikiPageId 不能为空");
        }
        int n = jdbcTemplate.update("DELETE FROM release_go_live_state WHERE wiki_page_id = ?", pageId);
        return Map.of("ok", true, "deleted", n);
    }

    Map<String, ProjectAgg> aggregateByProject(List<IssueRow> rows) {
        Map<String, ProjectAgg> byProject = new LinkedHashMap<>();
        List<String> keywords = props.getBranchKeywords() != null
                ? props.getBranchKeywords() : List.of();
        for (IssueRow row : rows) {
            if (row == null) continue;
            List<ProjectBranch> pairs = parseProjectBranches(row.getBranches(), keywords);
            if (pairs.isEmpty()) {
                // 无解析出的项目时，挂到「未识别项目」，避免需求被吃掉
                ProjectAgg agg = byProject.computeIfAbsent("未识别项目", k -> new ProjectAgg());
                String placeholder = "(见需求备注)";
                agg.branches.add(placeholder);
                Map<String, Object> brief = issueBrief(row);
                agg.addIssue(brief);
                agg.linkBranchIssue(placeholder, brief);
                continue;
            }
            for (ProjectBranch pb : pairs) {
                ProjectAgg agg = byProject.computeIfAbsent(pb.project, k -> new ProjectAgg());
                Map<String, Object> brief = issueBrief(row);
                agg.addIssue(brief);
                if (pb.branch != null && !pb.branch.isBlank()) {
                    agg.branches.add(pb.branch);
                    // 保留 Wiki 行级「分支 ↔ 需求」对应（分支名无 APP-xxx 时前端也能展示）
                    agg.linkBranchIssue(pb.branch, brief);
                }
            }
        }
        return byProject;
    }

    List<String> extractOrderHints(List<IssueRow> rows) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (rows == null) return List.of();
        for (IssueRow row : rows) {
            if (row == null || row.getRemarks() == null) continue;
            for (String line : row.getRemarks().split("\\R")) {
                String t = line == null ? "" : line.trim();
                if (t.isEmpty()) continue;
                if (t.contains("发版顺序") || t.contains("顺序发版") || t.contains("按顺序发版")
                        || t.contains("发版次序")) {
                    String prefix = row.getIssueKey() != null && !row.getIssueKey().isBlank()
                            ? "[" + row.getIssueKey() + "] "
                            : "";
                    hints.add(prefix + t);
                }
            }
            // 分支行里偶发「，顺序发版」
            if (row.getBranches() != null) {
                for (String line : row.getBranches().split("\\R")) {
                    String t = line == null ? "" : line.trim();
                    if (t.contains("顺序发版") || t.contains("按顺序发版") || t.contains("发版顺序")) {
                        String prefix = row.getIssueKey() != null && !row.getIssueKey().isBlank()
                                ? "[" + row.getIssueKey() + "] "
                                : "";
                        hints.add(prefix + t);
                    }
                }
            }
        }
        return new ArrayList<>(hints);
    }

    List<ProjectBranch> parseProjectBranches(String branchesCell, List<String> keywords) {
        List<ProjectBranch> out = new ArrayList<>();
        if (branchesCell == null || branchesCell.isBlank() || "无".equals(branchesCell.trim())) {
            return out;
        }
        for (String rawLine : branchesCell.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || "无".equals(line) || "-".equals(line)) continue;

            List<String> entries = releaseDocService.splitBranchEntries(line, keywords);
            if (entries.isEmpty()) {
                Matcher m = PROJECT_BRANCH.matcher(line);
                if (m.find()) {
                    String left = m.group(1).trim();
                    String branch = cleanBranch(m.group(2));
                    if (branch.isBlank()) continue;
                    // 无关键字命中时：顿号/逗号拆多项目共用分支
                    String[] parts = left.split("[、,]");
                    if (parts.length > 1) {
                        for (String part : parts) {
                            String project = normalizeProject(part);
                            if (!project.isBlank()) {
                                out.add(new ProjectBranch(project, branch));
                            }
                        }
                    } else {
                        String project = normalizeProject(left);
                        if (!project.isBlank()) {
                            out.add(new ProjectBranch(project, branch));
                        }
                    }
                }
                continue;
            }
            for (String entry : entries) {
                Matcher m = PROJECT_BRANCH.matcher(entry.trim());
                if (!m.find()) continue;
                String project = normalizeProject(m.group(1));
                String branch = cleanBranch(m.group(2));
                if (!project.isBlank() && !branch.isBlank()) {
                    out.add(new ProjectBranch(project, branch));
                }
            }
        }
        return out;
    }

    static String cleanBranch(String raw) {
        if (raw == null) return "";
        String b = ORDER_NOISE.matcher(raw.trim()).replaceFirst("").trim();
        b = b.replaceAll("[；;].*$", "").trim();
        // Wiki 偶发用括号包住分支名：(rjy/…/refine)
        while (b.length() >= 2 && b.startsWith("(") && b.endsWith(")")) {
            b = b.substring(1, b.length() - 1).trim();
        }
        return b;
    }

    static boolean isPlaceholderBranch(String branch) {
        if (branch == null || branch.isBlank()) return true;
        return branch.contains("见需求备注") || branch.equals("-") || branch.equals("无");
    }

    private static String normalizeProject(String raw) {
        if (raw == null) return "";
        String p = raw.trim().replaceAll("[:：]\\s*$", "").trim();
        // 多项目顿号串不应整段当项目名；splitBranchEntries 已拆开
        if (p.contains("、") || p.contains(",")) {
            return p;
        }
        // 与 ReleaseDocService 别名规则对齐，避免同一仓两行
        if (p.equalsIgnoreCase("esport-api") || p.equalsIgnoreCase("esportsapi")) {
            return "esports-api";
        }
        if (p.equalsIgnoreCase("eventcenter") || p.equalsIgnoreCase("event_center")) {
            return "event-center";
        }
        if (p.equalsIgnoreCase("appuser") || p.equalsIgnoreCase("app_user")) {
            return "app-user";
        }
        if (p.equalsIgnoreCase("commentservice") || p.equalsIgnoreCase("comment_service")) {
            return "comment-service";
        }
        if (p.equalsIgnoreCase("datacenter") || p.equalsIgnoreCase("data_center")) {
            return "data-center";
        }
        if (p.equalsIgnoreCase("appadmin") || p.equalsIgnoreCase("app_admin")) {
            return "app-admin";
        }
        return p;
    }

    static boolean isFrontendProject(String project) {
        String p = normalizeProject(project);
        for (String fe : FRONTEND_ORDER) {
            if (fe.equalsIgnoreCase(p)) return true;
        }
        return false;
    }

    static void sortProjectsFrontendFirst(List<Map<String, Object>> projects) {
        if (projects == null || projects.size() < 2) return;
        projects.sort((a, b) -> {
            String pa = String.valueOf(a.get("project"));
            String pb = String.valueOf(b.get("project"));
            boolean fa = isFrontendProject(pa);
            boolean fb = isFrontendProject(pb);
            if (fa != fb) return fa ? -1 : 1;
            if (fa) {
                return Integer.compare(frontendRank(pa), frontendRank(pb));
            }
            return pa.compareToIgnoreCase(pb);
        });
    }

    private static int frontendRank(String project) {
        String p = normalizeProject(project);
        for (int i = 0; i < FRONTEND_ORDER.size(); i++) {
            if (FRONTEND_ORDER.get(i).equalsIgnoreCase(p)) return i;
        }
        return FRONTEND_ORDER.size();
    }

    private Map<String, Object> issueBrief(IssueRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issueKey", row.getIssueKey());
        m.put("summary", row.getSummary());
        m.put("issueUrl", row.getIssueUrl());
        m.put("qaAssignee", row.getQaAssignee());
        return m;
    }

    private ProjectState resolveState(Map<String, ProjectState> states, String project, String branch) {
        ProjectState exact = states.get(stateKey(project, branch));
        if (exact != null) return exact;
        // 兼容旧数据：仅按项目存过状态时，落到该项目下所有分支
        ProjectState legacy = states.get(stateKey(project, ""));
        return legacy != null ? legacy : ProjectState.EMPTY;
    }

    private static String stateKey(String project, String branch) {
        return blankToEmpty(project) + STATE_SEP + blankToEmpty(branch);
    }

    private Map<String, ProjectState> loadStates(String pageId) {
        Map<String, ProjectState> map = new LinkedHashMap<>();
        if (pageId == null || pageId.isBlank()) return map;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT project_key, branch_key, merged_master, released,
                           master_check_status, master_check_message, master_check_ahead, merged_at
                      FROM release_go_live_state
                     WHERE wiki_page_id = ?
                    """, pageId);
            for (Map<String, Object> r : rows) {
                String key = String.valueOf(r.get("project_key"));
                String branch = r.get("branch_key") == null ? "" : String.valueOf(r.get("branch_key"));
                map.put(stateKey(key, branch), projectStateFromRow(r));
            }
        } catch (Exception e) {
            // 旧表无 master_check / branch_key 时回退
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT project_key, branch_key, merged_master, released
                          FROM release_go_live_state
                         WHERE wiki_page_id = ?
                        """, pageId);
                for (Map<String, Object> r : rows) {
                    String key = String.valueOf(r.get("project_key"));
                    String branch = r.get("branch_key") == null ? "" : String.valueOf(r.get("branch_key"));
                    map.put(stateKey(key, branch), new ProjectState(
                            toBool(r.get("merged_master")), toBool(r.get("released")),
                            null, null, null, null));
                }
            } catch (Exception e2) {
                log.debug("[release-go-live] 读状态失败: {}", e2.getMessage());
            }
        }
        return map;
    }

    private static ProjectState projectStateFromRow(Map<String, Object> r) {
        Integer ahead = null;
        Object a = r.get("master_check_ahead");
        if (a instanceof Number n) {
            ahead = n.intValue();
        }
        String st = r.get("master_check_status") == null ? null : String.valueOf(r.get("master_check_status"));
        String msg = r.get("master_check_message") == null ? null : String.valueOf(r.get("master_check_message"));
        String mergedAt = null;
        Object ma = r.get("merged_at");
        if (ma != null) {
            mergedAt = String.valueOf(ma).replace('T', ' ');
            if (mergedAt.length() > 19) mergedAt = mergedAt.substring(0, 19);
        }
        return new ProjectState(
                toBool(r.get("merged_master")),
                toBool(r.get("released")),
                blankToNull(st),
                blankToNull(msg),
                ahead,
                blankToNull(mergedAt));
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) return null;
        return s.trim();
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    static final class ProjectAgg {
        final LinkedHashSet<String> branches = new LinkedHashSet<>();
        final List<Map<String, Object>> issues = new ArrayList<>();
        final Set<String> issueKeys = new LinkedHashSet<>();
        /** branch → 来自哪些需求行（同一分支可对应多需求） */
        final Map<String, List<Map<String, Object>>> branchIssues = new LinkedHashMap<>();
        final Map<String, Set<String>> branchIssueKeys = new LinkedHashMap<>();

        void addIssue(Map<String, Object> brief) {
            String ik = String.valueOf(brief.getOrDefault("issueKey", brief.get("summary")));
            if (issueKeys.add(ik)) {
                issues.add(brief);
            }
        }

        void linkBranchIssue(String branch, Map<String, Object> brief) {
            if (branch == null || branch.isBlank() || brief == null) return;
            String ik = String.valueOf(brief.getOrDefault("issueKey", brief.get("summary")));
            Set<String> keys = branchIssueKeys.computeIfAbsent(branch, k -> new LinkedHashSet<>());
            if (!keys.add(ik)) return;
            branchIssues.computeIfAbsent(branch, k -> new ArrayList<>()).add(brief);
        }

        List<Map<String, Object>> issuesForBranch(String branch) {
            List<Map<String, Object>> list = branchIssues.get(branch);
            return list != null ? list : List.of();
        }
    }

    record ProjectBranch(String project, String branch) {
    }

    record ProjectState(
            boolean mergedMaster,
            boolean released,
            String masterCheckStatus,
            String masterCheckMessage,
            Integer masterCheckAhead,
            String mergedAt) {
        static final ProjectState EMPTY = new ProjectState(false, false, null, null, null, null);
    }
}
