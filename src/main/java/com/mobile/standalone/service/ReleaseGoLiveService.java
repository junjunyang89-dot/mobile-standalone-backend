package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
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
 * 发版当天上线看板：按项目汇总分支、勾选防漏（前端项目置顶）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseGoLiveService {

    private static final Pattern PROJECT_BRANCH = Pattern.compile("^(.+?)\\s*[:：]\\s*(.+)$");
    private static final Pattern ORDER_NOISE = Pattern.compile("[，,]\\s*(顺序发版|发版顺序|发版次序|注意).*$");
    /** 前端仓置顶顺序 */
    private static final List<String> FRONTEND_ORDER = List.of("app-swift", "app-swift-v3");

    private final ReleaseDocService releaseDocService;
    private final ReleaseDocProperties props;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS release_go_live_state (
                        wiki_page_id VARCHAR(64) NOT NULL,
                        project_key VARCHAR(128) NOT NULL,
                        merged_master TINYINT(1) NOT NULL DEFAULT 0,
                        released TINYINT(1) NOT NULL DEFAULT 0,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (wiki_page_id, project_key),
                        INDEX idx_rgl_page (wiki_page_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[release-go-live] 表已就绪");
        } catch (Exception e) {
            log.warn("[release-go-live] 建表失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> board(String wikiUrl) {
        List<IssueRow> rows = releaseDocService.loadIssueRowsFromWikiUrl(wikiUrl);
        String pageId = ReleaseDocService.extractPageId(wikiUrl);
        Map<String, ProjectAgg> byProject = aggregateByProject(rows);
        Map<String, ProjectState> states = loadStates(pageId);
        List<String> orderHints = extractOrderHints(rows);

        List<Map<String, Object>> projects = new ArrayList<>();
        int releasedCount = 0;
        for (Map.Entry<String, ProjectAgg> e : byProject.entrySet()) {
            String key = e.getKey();
            ProjectAgg agg = e.getValue();
            ProjectState st = states.getOrDefault(key, ProjectState.EMPTY);
            if (st.released) {
                releasedCount++;
            }
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("project", key);
            vo.put("layer", isFrontendProject(key) ? "frontend" : "backend");
            vo.put("branches", new ArrayList<>(agg.branches));
            vo.put("issues", agg.issues);
            vo.put("mergedMaster", st.mergedMaster);
            vo.put("released", st.released);
            projects.add(vo);
        }
        sortProjectsFrontendFirst(projects);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wikiPageId", pageId);
        out.put("wikiPageUrl", wikiUrl);
        out.put("issueCount", rows.size());
        out.put("projectCount", projects.size());
        out.put("releasedCount", releasedCount);
        out.put("orderHints", orderHints);
        out.put("hasOrderHints", !orderHints.isEmpty());
        out.put("projects", projects);
        return out;
    }

    public Map<String, Object> saveState(String wikiPageId, String project, Boolean mergedMaster, Boolean released) {
        String pageId = blankToEmpty(wikiPageId);
        String proj = blankToEmpty(project);
        if (pageId.isEmpty() || proj.isEmpty()) {
            throw new IllegalArgumentException("wikiPageId 与 project 不能为空");
        }
        ProjectState cur = loadStates(pageId).getOrDefault(proj, ProjectState.EMPTY);
        boolean mm = mergedMaster != null ? mergedMaster : cur.mergedMaster;
        boolean rel = released != null ? released : cur.released;
        jdbcTemplate.update("""
                INSERT INTO release_go_live_state (wiki_page_id, project_key, merged_master, released, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                  merged_master = VALUES(merged_master),
                  released = VALUES(released),
                  updated_at = NOW()
                """, pageId, proj, mm ? 1 : 0, rel ? 1 : 0);
        return Map.of(
                "ok", true,
                "wikiPageId", pageId,
                "project", proj,
                "mergedMaster", mm,
                "released", rel);
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
                agg.branches.add("(见需求备注)");
                agg.issues.add(issueBrief(row));
                continue;
            }
            for (ProjectBranch pb : pairs) {
                ProjectAgg agg = byProject.computeIfAbsent(pb.project, k -> new ProjectAgg());
                if (pb.branch != null && !pb.branch.isBlank()) {
                    agg.branches.add(pb.branch);
                }
                Map<String, Object> brief = issueBrief(row);
                if (!agg.issueKeys.contains(String.valueOf(brief.getOrDefault("issueKey", brief.get("summary"))))) {
                    agg.issueKeys.add(String.valueOf(brief.getOrDefault("issueKey", brief.get("summary"))));
                    agg.issues.add(brief);
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
                if (t.contains("发版顺序") || t.contains("顺序发版") || t.contains("发版次序")) {
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
                    if (t.contains("顺序发版") || t.contains("发版顺序")) {
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

    private static String cleanBranch(String raw) {
        if (raw == null) return "";
        String b = ORDER_NOISE.matcher(raw.trim()).replaceFirst("").trim();
        b = b.replaceAll("[；;].*$", "").trim();
        return b;
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

    private Map<String, ProjectState> loadStates(String pageId) {
        Map<String, ProjectState> map = new LinkedHashMap<>();
        if (pageId == null || pageId.isBlank()) return map;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT project_key, merged_master, released
                      FROM release_go_live_state
                     WHERE wiki_page_id = ?
                    """, pageId);
            for (Map<String, Object> r : rows) {
                String key = String.valueOf(r.get("project_key"));
                boolean mm = toBool(r.get("merged_master"));
                boolean rel = toBool(r.get("released"));
                map.put(key, new ProjectState(mm, rel));
            }
        } catch (Exception e) {
            log.debug("[release-go-live] 读状态失败: {}", e.getMessage());
        }
        return map;
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
    }

    record ProjectBranch(String project, String branch) {
    }

    record ProjectState(boolean mergedMaster, boolean released) {
        static final ProjectState EMPTY = new ProjectState(false, false);
    }
}
