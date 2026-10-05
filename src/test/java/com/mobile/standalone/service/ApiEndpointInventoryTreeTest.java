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

import java.util.ArrayList;
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
        ep.setHandler("AiController.aiTranslate");
        ep.setSourceBranch("fch/20260804/APP-8928");
        ep.setHttpMethod("POST");
        ep.setPath("/appdatacenter/ai/aiTranslate");

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

        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("type")).isEqualTo("KIND");
        assertThat(children.get(0).get("label")).isEqualTo("后端");
        List<Object> projects = ((List<Map<String, Object>>) children.get(0).get("children"))
                .stream().map(c -> c.get("project")).toList();
        assertThat(projects).contains("app-data-center").doesNotContain("app-seckill");

        Map<String, Object> dc = ((List<Map<String, Object>>) children.get(0).get("children")).stream()
                .filter(c -> "app-data-center".equals(c.get("project")))
                .findFirst()
                .orElseThrow();
        assertThat(dc.get("total")).isEqualTo(1);
        assertThat(dc.containsKey("rails")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 电竞APP槽位里的项目手动挂到POGPOG后只出现在POGPOG() {
        stubCategoryRegistry();

        ApiProject seckill = new ApiProject();
        seckill.setCode("app-seckill");
        seckill.setName("APP-SECKILL");
        seckill.setKind(ApiProject.KIND_BACKEND);
        seckill.setEnabled(true);
        seckill.setCategory("POGPOG");
        when(projectMapper.findAll(null)).thenReturn(List.of(seckill));

        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) projectService.buildEndpointInventoryTree(List.of()).get("categories");

        assertThat(categories).noneMatch(c -> ApiProjectCatalog.APP.key().equals(c.get("key")));
        List<Object> pog = childProjects(categories, "POGPOG");
        assertThat(pog).containsExactly("app-seckill");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 网关和前端不进接口清单_空类型不占位() {
        stubCategoryRegistry();

        ApiProject gw = new ApiProject();
        gw.setCode("app-gateway");
        gw.setName("网关");
        gw.setKind(ApiProject.KIND_BACKEND);
        gw.setEnabled(true);
        ApiProject fe = new ApiProject();
        fe.setCode("app-xiaomai");
        fe.setName("小麦");
        fe.setKind(ApiProject.KIND_FRONTEND);
        fe.setEnabled(true);
        ApiProject be = new ApiProject();
        be.setCode("app-activity");
        be.setName("活动");
        be.setKind(ApiProject.KIND_BACKEND);
        be.setEnabled(true);
        when(projectMapper.findAll(null)).thenReturn(List.of(gw, fe, be));

        List<Map<String, Object>> categories =
                (List<Map<String, Object>>) projectService.buildEndpointInventoryTree(List.of()).get("categories");
        List<Object> app = childProjects(categories, ApiProjectCatalog.APP.key());
        assertThat(app).containsExactly("app-activity");
        Map<String, Object> appCat = categories.stream()
                .filter(c -> ApiProjectCatalog.APP.key().equals(c.get("key")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) appCat.get("children");
        assertThat(kinds).extracting(k -> k.get("label")).containsExactly("后端");
    }

    @Test
    void groupByKind_空类型不占位_网关仍在后端() {
        Map<String, Object> gw = new LinkedHashMap<>();
        gw.put("code", "app-gateway");
        gw.put("kind", "BACKEND");
        gw.put("missing", false);
        Map<String, Object> fe = new LinkedHashMap<>();
        fe.put("code", "app-xiaomai");
        fe.put("kind", "FRONTEND");
        fe.put("missing", false);
        List<Map<String, Object>> kinds = ApiProjectService.groupByKind(List.of(gw, fe));
        assertThat(kinds).extracting(k -> k.get("label")).containsExactly("后端", "前端");
        assertThat(kinds).extracting(k -> k.get("count")).containsExactly(1, 1);
        assertThat(ApiProjectService.groupByKind(List.of(gw)))
                .extracting(k -> k.get("label"))
                .containsExactly("后端");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> childProjects(List<Map<String, Object>> categories, String key) {
        Map<String, Object> cat = categories.stream()
                .filter(c -> key.equals(c.get("key")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> children = (List<Map<String, Object>>) cat.get("children");
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> n : children) {
            if ("KIND".equals(n.get("type"))) {
                List<Map<String, Object>> inner = (List<Map<String, Object>>) n.get("children");
                for (Map<String, Object> p : inner) {
                    out.add(p.get("project"));
                }
            } else {
                out.add(n.get("project"));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void stubCategoryRegistry() {
        when(jdbcTemplate.query(
                contains("api_project_categories"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any()))
                .thenReturn(List.of(
                        categoryRow("APP", "电竞APP", 10),
                        categoryRow("POGPOG", "POGPOG", 100)));
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
