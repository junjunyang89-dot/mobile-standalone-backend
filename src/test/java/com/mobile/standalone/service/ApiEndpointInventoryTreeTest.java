package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apitest.ApiProjectCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiEndpointInventoryTreeTest {

    @Mock
    private ApiProjectMapper projectMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ApiProjectService projectService;

    @Test
    void buildEndpointInventoryTree_listsAllAppCatalogSlots() {
        stubCategoryRegistry();

        ApiProject dataCenter = new ApiProject();
        dataCenter.setCode("app-data-center");
        dataCenter.setName("data-center");
        dataCenter.setKind(ApiProject.KIND_BACKEND);
        dataCenter.setEnabled(true);
        when(projectMapper.findAll(null)).thenReturn(List.of(dataCenter));

        ApiEndpoint ep = new ApiEndpoint();
        ep.setProject("data-center");
        ep.setEnabledCaseCount(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) projectService
                        .buildEndpointInventoryTree(List.of(ep))
                        .get("categories");

        Map<String, Object> appCat = categories.stream()
                .filter(c -> ApiProjectCatalog.APP.key().equals(c.get("key")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) appCat.get("children");

        assertThat(children).hasSizeGreaterThanOrEqualTo(ApiProjectCatalog.appProjectSlots().size());
        assertThat(children.stream().map(c -> c.get("project"))).contains("app-seckill", "app-data-center");

        Map<String, Object> dc = children.stream()
                .filter(c -> "app-data-center".equals(c.get("project")))
                .findFirst()
                .orElseThrow();
        assertThat(dc.get("total")).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private void stubCategoryRegistry() {
        when(jdbcTemplate.query(
                contains("api_project_categories"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
                .thenReturn(List.of(
                        categoryRow("APP", "APP 后端", 10),
                        categoryRow("PVP", "PVP · 客户端", 20),
                        categoryRow("WEB", "WEB", 30),
                        categoryRow("OTHER", "其他", 40)));
    }

    private static Map<String, Object> categoryRow(String key, String label, int sortOrder) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("sortOrder", sortOrder);
        row.put("builtin", true);
        return row;
    }
}
