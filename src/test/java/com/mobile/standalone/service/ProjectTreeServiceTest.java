package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectTreeServiceTest {

    @Mock
    private ApiProjectMapper projectMapper;
    @Mock
    private ApiProjectService projectService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ProjectTreeService service;

    private static ApiProject project(String code, String name, String category, String kind) {
        ApiProject p = new ApiProject();
        p.setCode(code);
        p.setName(name);
        p.setCategory(category);
        p.setKind(kind);
        p.setEnabled(true);
        return p;
    }

    private static Map<String, Object> countRow(String k, long n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", k);
        m.put("n", n);
        return m;
    }

    private static Map<String, Object> categoryDef(String key, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        return m;
    }

    private void stubBase() {
        when(projectService.listCategoryRegistry()).thenReturn(List.of(
                categoryDef("APP", "APP"),
                categoryDef("POGPOG", "POGPOG")));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> tree) {
        return (List<Map<String, Object>>) tree.get("nodes");
    }

    @Test
    void build_nestsCategoryProjectModule() {
        stubBase();
        when(projectMapper.findAll(null)).thenReturn(List.of(
                project("app-esports-ios", "APP-ESPORTS-iOS", "APP", "CLIENT")));

        List<Map<String, Object>> cats = nodes(service.build(true));

        Map<String, Object> app = cats.stream()
                .filter(c -> "APP".equals(c.get("key"))).findFirst().orElseThrow();
        assertThat(app.get("type")).isEqualTo("CATEGORY");

        Map<String, Object> proj = children(app).get(0);
        assertThat(proj.get("type")).isEqualTo("PROJECT");
        assertThat(proj.get("kind")).isEqualTo("CLIENT");
        // 展示名读 name 列而不是从 code 推导——推导会把 iOS 变成 IOS
        assertThat(proj.get("label")).isEqualTo("APP-ESPORTS-iOS");
        assertThat(children(proj)).allSatisfy(m -> assertThat(m.get("type")).isEqualTo("MODULE"));
    }

    @Test
    void build_rollsChildCountsUpToCategory() {
        stubBase();
        when(projectMapper.findAll(null)).thenReturn(List.of(
                project("app-a", "A", "APP", "BACKEND"),
                project("app-b", "B", "APP", "BACKEND")));
        when(jdbcTemplate.queryForList(contains("api_test_cases")))
                .thenReturn(List.of(countRow("app-a", 10), countRow("app-b", 5)));

        Map<String, Object> app = nodes(service.build(true)).stream()
                .filter(c -> "APP".equals(c.get("key"))).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) app.get("counts");
        assertThat(counts.get("apiCases")).isEqualTo(15L);
        assertThat(app.get("projectCount")).isEqualTo(2);
    }

    /** 空项目默认保留：「一条用例都没有」正是要看见的信息 */
    @Test
    void build_keepsEmptyProjectsByDefaultAndDropsThemWhenAsked() {
        stubBase();
        when(projectMapper.findAll(null)).thenReturn(List.of(
                project("app-empty", "EMPTY", "APP", "BACKEND")));

        assertThat(children(nodes(service.build(true)).get(0))).hasSize(1);
        assertThat(children(nodes(service.build(false)).get(0))).isEmpty();
    }

    /** 注册表里没有的分类值不能静默吞掉，否则那些项目会整个从树上消失 */
    @Test
    void build_stillShowsProjectsUnderUnregisteredCategory() {
        stubBase();
        when(projectMapper.findAll(null)).thenReturn(List.of(
                project("weird", "W", "NOT_REGISTERED", "BACKEND")));

        List<Map<String, Object>> cats = nodes(service.build(true));
        assertThat(cats).anySatisfy(c -> assertThat(c.get("key")).isEqualTo("NOT_REGISTERED"));
    }

    @Test
    void build_skipsDisabledProjects() {
        stubBase();
        ApiProject off = project("app-off", "OFF", "APP", "BACKEND");
        off.setEnabled(false);
        when(projectMapper.findAll(null)).thenReturn(List.of(off));

        assertThat(children(nodes(service.build(true)).get(0))).isEmpty();
    }

    /** 某张来源表查不出来时，该项计 0，树照常返回 */
    @Test
    void build_survivesCountQueryFailure() {
        stubBase();
        when(projectMapper.findAll(null)).thenReturn(List.of(
                project("app-a", "A", "APP", "BACKEND")));
        when(jdbcTemplate.queryForList(contains("ui_elements")))
                .thenThrow(new RuntimeException("表还没建"));

        Map<String, Object> proj = children(nodes(service.build(true)).get(0)).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) proj.get("counts");
        assertThat(counts.get("uiElements")).isEqualTo(0L);
    }
}
