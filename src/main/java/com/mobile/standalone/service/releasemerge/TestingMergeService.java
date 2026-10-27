package com.mobile.standalone.service.releasemerge;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.ReleaseDocService;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并到 testing：从 JIRA 单备注解析「项目：分支」，核对后由测试逐条确认再合进 testing。
 * GitLab 令牌、上线目录与「合并到 master」共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestingMergeService {

    public static final String TARGET = "testing";
    private static final int NOTES_MAX = 6000;

    private final ReleaseMergeService releaseMerge;
    private final ReleaseDocService releaseDoc;
    private final ApiProjectMapper projectMapper;

    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>(releaseMerge.settings());
        m.put("target", TARGET);
        m.put("settingsSharedWith", "release-merge");
        return m;
    }

    /**
     * 贴 JIRA 单号 / browse 链接，拉备注并解析分支行；不去重失败的单，匹配不上的行也返回便于改项目。
     */
    public Map<String, Object> resolve(String text) {
        List<String> keys = ReleaseBatchService.issueKeys(text);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("没认出 JIRA 单号：贴单子地址（…/browse/APP-1234）或直接写 APP-1234");
        }
        List<ApiProject> projects = projectMapper.findAll(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            try {
                ReleaseDocService.IssueDetail d = releaseDoc.fetchIssueDetail(key);
                String notes = d.text() == null ? "" : d.text();
                if (notes.length() > NOTES_MAX) {
                    notes = notes.substring(0, NOTES_MAX);
                }
                List<Target> targets = ReleaseBatchService.resolveTargets(notes, projects);
                if (targets.isEmpty()) {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("issueKey", key);
                    empty.put("issueSummary", d.summary());
                    empty.put("issueUrl", d.url());
                    empty.put("projectCode", null);
                    empty.put("branch", null);
                    empty.put("rawProject", null);
                    empty.put("status", "BLOCK");
                    empty.put("blockers", List.of("备注里没解析出「项目：分支」，手工补项目和分支后再核对"));
                    empty.put("warnings", List.of());
                    empty.put("target", TARGET);
                    rows.add(empty);
                    continue;
                }
                for (Target t : targets) {
                    String dedupe = (t.getProjectCode() == null ? t.getRawProject() : t.getProjectCode())
                            + "|" + (t.getBranch() == null ? "" : t.getBranch());
                    String dedupeKey = key + "|" + dedupe.toLowerCase();
                    if (!seen.add(dedupeKey)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("issueKey", key);
                    row.put("issueSummary", d.summary());
                    row.put("issueUrl", d.url());
                    row.put("rawProject", t.getRawProject());
                    row.put("note", t.getNote());
                    row.put("projectCode", t.getProjectCode());
                    row.put("projectName", t.getProjectName());
                    row.put("kind", t.getKind());
                    row.put("matchHow", t.getMatchHow());
                    row.put("branch", t.getBranch());
                    row.put("target", TARGET);
                    row.put("blockers", new ArrayList<String>());
                    row.put("warnings", new ArrayList<>());
                    if (t.getProjectCode() == null) {
                        row.put("status", "BLOCK");
                        @SuppressWarnings("unchecked")
                        List<String> blockers = (List<String>) row.get("blockers");
                        blockers.add("项目管理里没认出「" + t.getRawProject() + "」，在下拉里选一下");
                    }
                    rows.add(row);
                }
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failed.add(key + "：" + why);
                log.warn("[testing-merge] 拉 JIRA {} 失败: {}", key, why);
            }
        }
        if (failed.size() == keys.size()) {
            throw new IllegalArgumentException("拉 JIRA 失败（要连公司内网）：" + failed.get(0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("failed", failed);
        out.put("issueCount", keys.size());
        out.put("target", TARGET);
        out.put("tokenSet", Boolean.TRUE.equals(settings().get("tokenSet")));
        return out;
    }

    public Map<String, Object> check(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("没有要核对的行，先从 JIRA 拉分支");
        }
        return releaseMerge.check(null, rows, TARGET);
    }

    public Map<String, Object> merge(String projectCode, String branch, String sha, List<String> acked) {
        return releaseMerge.merge(projectCode, branch, sha, acked, TARGET);
    }

    public List<Map<String, Object>> logs(int limit) {
        return releaseMerge.logs(limit, TARGET);
    }
}
