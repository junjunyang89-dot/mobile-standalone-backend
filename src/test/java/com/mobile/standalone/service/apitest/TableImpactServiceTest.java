package com.mobile.standalone.service.apitest;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TableImpactServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TableImpactService service;

    private static Map<String, Object> ep(String id, String method, String path, String tablesJson, int cases) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("http_method", method);
        m.put("path", path);
        m.put("handler", "H");
        m.put("status", "ACTIVE");
        m.put("tables_json", tablesJson);
        m.put("case_count", cases);
        return m;
    }

    @Test
    void 反查读写某表的接口() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                ep("ep-1", "POST", "/order/create", "[\"orders\",\"order_item\"]", 3),
                ep("ep-2", "GET", "/order/list", "[\"orders\"]", 2),
                ep("ep-3", "GET", "/user/info", "[\"user\"]", 5)));

        var impact = service.forTable("orders", null);

        assertThat(impact.endpointCount()).isEqualTo(2);
        assertThat(impact.caseCount()).isEqualTo(5);
        assertThat(impact.endpoints()).extracting(TableImpactService.ImpactedEndpoint::path)
                .containsExactly("/order/create", "/order/list");
    }

    /** 精确匹配表名，不能用 LIKE：查 user 不该命中 user_log */
    @Test
    void 表名精确匹配不误伤前缀相同的表() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                ep("ep-1", "GET", "/log", "[\"user_log\"]", 1),
                ep("ep-2", "GET", "/user", "[\"user\"]", 1)));

        var impact = service.forTable("user", null);

        assertThat(impact.endpoints()).extracting(TableImpactService.ImpactedEndpoint::path)
                .containsExactly("/user");
    }

    /** 大小写不敏感：orders / ORDERS 是同一张表 */
    @Test
    void 表名大小写不敏感() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                ep("ep-1", "POST", "/x", "[\"Orders\"]", 1)));

        assertThat(service.forTable("orders", null).endpointCount()).isEqualTo(1);
    }

    @Test
    void 没有接口读写该表时给出可行动提示() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        var impact = service.forTable("nonexistent", null);
        assertThat(impact.endpointCount()).isZero();
        assertThat(impact.hint()).contains("没有已入库的接口").contains("对比分析");
    }

    @Test
    void 空表名报错() {
        assertThatThrownBy(() -> service.forTable("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名");
    }

    /** 一个接口同时动了多张变更表时，用例只算一次 */
    @Test
    void 多表合并影响时用例去重() {
        // orders 命中 ep-1；order_item 也命中 ep-1 —— 同一接口
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                ep("ep-1", "POST", "/order/create", "[\"orders\",\"order_item\"]", 3)));
        // 去重后按 endpoint_id IN (...) 查总用例数
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(3);

        Map<String, Object> out = service.forTables(List.of("orders", "order_item"), null);

        assertThat(out.get("distinctEndpoints")).isEqualTo(1);
        assertThat(out.get("distinctCases")).isEqualTo(3);
    }

    @Test
    void 提示语区分有无影响() {
        assertThat(TableImpactService.buildHint("orders", 2, 5))
                .contains("orders").contains("2 个接口").contains("回归");
        assertThat(TableImpactService.buildHint("orders", 0, 0))
                .contains("没有已入库的接口");
    }
}
