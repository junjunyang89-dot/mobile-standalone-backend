package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageGapServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CoverageGapService service;

    private static Map<String, Object> ep(String path, String source, boolean captured) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("httpMethod", "GET");
        m.put("path", path);
        m.put("source", source);
        m.put("capturedAt", captured ? LocalDateTime.now() : null);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> node, String key) {
        return (List<Map<String, Object>>) node.get(key);
    }

    /**
     * 这条是整个差集的核心：SYNC 且从未抓到＝改了但没人调。
     * 光看 source 分不出它和「改了且覆盖了」，靠的是 captured_at。
     */
    @Test
    void forRequirement_splitsChangedNotCapturedFromCovered() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(
                ep("/a", "SYNC", false),
                ep("/b", "SYNC", true),
                ep("/c", "CAPTURE", true)));

        Map<String, Object> out = service.forRequirement("APP-8999", false);

        assertThat(out.get("changedNotCapturedCount")).isEqualTo(1);
        assertThat(out.get("coveredCount")).isEqualTo(1);
        assertThat(out.get("capturedNotChangedCount")).isEqualTo(1);
        assertThat(list(out, "changedNotCaptured")).singleElement()
                .satisfies(m -> {
                    assertThat(m.get("path")).isEqualTo("/a");
                    assertThat(m.get("gap")).isEqualTo(CoverageGapService.GAP_NOT_CAPTURED);
                });
    }

    /** MANUAL 既不代表代码改了也不代表前端调了，单独计数，不能混进任何一边 */
    @Test
    void forRequirement_keepsManualOutOfBothGaps() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(
                ep("/m", "MANUAL", false),
                ep("/s", "SYNC", false)));

        Map<String, Object> out = service.forRequirement("APP-8999", false);

        assertThat(out.get("manualCount")).isEqualTo(1L);
        assertThat(out.get("changedNotCapturedCount")).isEqualTo(1);
        assertThat(out.get("capturedNotChangedCount")).isEqualTo(0);
        assertThat(list(out, "changedNotCaptured")).extracting(m -> m.get("path"))
                .containsExactly("/s");
    }

    /** 默认排除已上线归档的——上线之后再看差集没有意义 */
    @Test
    void forRequirement_excludesArchivedUnlessAsked() {
        when(jdbcTemplate.queryForList(contains("lifecycle"), anyString())).thenReturn(List.of());
        service.forRequirement("APP-8999", false);

        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
        service.forRequirement("APP-8999", true);
        // 带 includeArchived 时 SQL 里不该出现 lifecycle 过滤
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.never())
                .queryForList(contains("lifecycle <> 'ARCHIVED' ORDER"), anyString());
    }

    @Test
    void forRequirement_rejectsBlankRequirementNo() {
        assertThatThrownBy(() -> service.forRequirement("  ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需求单号");
    }

    /** 差集报告最容易变成两列看不懂的数字，提示要直接说下一步干什么 */
    @Test
    void buildHint_tellsWhatToDoNext() {
        assertThat(CoverageGapService.buildHint(0, 0, 0)).contains("还没有数据");
        assertThat(CoverageGapService.buildHint(0, 0, 5)).contains("覆盖完整");
        assertThat(CoverageGapService.buildHint(3, 0, 1))
                .contains("3 个").contains("漏测").contains("后端白改");
        assertThat(CoverageGapService.buildHint(0, 2, 1))
                .contains("2 个").contains("回归风险面");
        assertThat(CoverageGapService.buildHint(3, 2, 1))
                .contains("3 个").contains("2 个");
    }
}
