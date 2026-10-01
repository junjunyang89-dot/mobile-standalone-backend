package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReleaseGoLiveServiceTest {

    private ReleaseGoLiveService svc() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setBranchKeywords(List.of(
                "app-engine", "esports-api", "esport-api", "app-community", "data-center"));
        return new ReleaseGoLiveService(
                new ReleaseDocService(props),
                props,
                mock(JdbcTemplate.class));
    }

    @Test
    void aggregateByProjectFromBranchLines() {
        IssueRow row = new IssueRow();
        row.setIssueKey("APP-1");
        row.setSummary("测试需求");
        row.setIssueUrl("https://jira.example/APP-1");
        row.setBranches("""
                app-engine: dyl/20260917/foo
                esport-api、app-community：dyl/20260917/shared，顺序发版
                """);
        row.setRemarks("注意：先发 app-engine，顺序发版");

        ReleaseGoLiveService s = svc();
        Map<String, ReleaseGoLiveService.ProjectAgg> agg = s.aggregateByProject(List.of(row));
        assertTrue(agg.containsKey("app-engine"));
        assertTrue(agg.get("app-engine").branches.contains("dyl/20260917/foo"));
        assertTrue(agg.containsKey("esports-api"), "esport-api 应归一为 esports-api");
        assertTrue(agg.containsKey("app-community"));
        assertTrue(agg.get("esports-api").branches.contains("dyl/20260917/shared"));
        List<String> hints = s.extractOrderHints(List.of(row));
        assertFalse(hints.isEmpty());
        assertTrue(hints.stream().anyMatch(h -> h.contains("顺序发版") || h.contains("发版顺序")));
    }

    @Test
    void parseSimpleProjectColonBranch() {
        ReleaseGoLiveService s = svc();
        List<ReleaseGoLiveService.ProjectBranch> pairs =
                s.parseProjectBranches("data-center: zq/2026-09-16/x", propsKeywords());
        assertEquals(1, pairs.size());
        assertEquals("data-center", pairs.get(0).project());
        assertEquals("zq/2026-09-16/x", pairs.get(0).branch());
    }

    @Test
    void frontendProjectsSortFirst() {
        List<Map<String, Object>> projects = new java.util.ArrayList<>();
        for (String p : List.of("data-center", "app-swift-v3", "esports-api", "app-swift")) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("project", p);
            projects.add(m);
        }
        ReleaseGoLiveService.sortProjectsFrontendFirst(projects);
        assertEquals("app-swift", projects.get(0).get("project"));
        assertEquals("app-swift-v3", projects.get(1).get("project"));
        assertTrue(ReleaseGoLiveService.isFrontendProject("app-swift"));
        assertFalse(ReleaseGoLiveService.isFrontendProject("data-center"));
    }

    private List<String> propsKeywords() {
        return List.of("app-engine", "esports-api", "esport-api", "app-community", "data-center");
    }
}
