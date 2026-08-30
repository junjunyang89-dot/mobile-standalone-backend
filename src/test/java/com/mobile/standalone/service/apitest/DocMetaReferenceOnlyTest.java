package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口文档导进来的字段，必须被标成<b>仅参考</b>。
 * <p>
 * 各家开发文档格式不一，写的字段常和线上对不上——拿它生成断言，
 * 等于把「开发说的」当成「实测的事实」，而这正是这套东西一直在避免的。
 * <p>
 * 盖章盖在 {@code applyDocMeta} 里而不是各个导入器里：以后再来 Swagger、
 * Confluence 导入，谁都不用记得带这个标记。忘了一个，那一批字段就悄悄
 * 变成了「事实」，而没有任何地方看得出来。
 */
class DocMetaReferenceOnlyTest {

    private final ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApiEndpointService service = new ApiEndpointService(mapper, jdbc);

    private Map<String, Object> docMeta() {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", "好友列表");
        m.put("fields", List.of("code", "data"));
        return m;
    }

    private void stubExistingEndpoint() {
        ApiEndpoint row = new ApiEndpoint();
        row.setId("ep-1");
        row.setHttpMethod("GET");
        row.setPath("/gld/deadlock/friend/list");
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(row);
        when(mapper.findById("ep-1")).thenReturn(row);
    }

    @Test
    void 文档字段一律盖上仅参考的章() {
        stubExistingEndpoint();

        service.applyDocMeta("app-global-data", "GET", "/gld/deadlock/friend/list", docMeta());

        ArgumentCaptor<ApiEndpoint> captor = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getDocMeta())
                .as("导入器忘了带标记时，这里必须自己补上")
                .contains("\"referenceOnly\":true");
    }

    @Test
    void 导入时间也记下来() {
        // 文档是哪天导的，决定了它还可不可信——半年前的文档和上周的不是一回事
        stubExistingEndpoint();

        service.applyDocMeta("app-global-data", "GET", "/gld/deadlock/friend/list", docMeta());

        ArgumentCaptor<ApiEndpoint> captor = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getDocMeta()).contains("importedAt");
    }

    @Test
    void 文档不覆盖已有的说明() {
        // 清单里的说明可能是人核过的，文档是机器导的。机器不该盖掉人工确认过的东西
        ApiEndpoint row = new ApiEndpoint();
        row.setId("ep-1");
        row.setSummary("人工核过的说明");
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(row);
        when(mapper.findById("ep-1")).thenReturn(row);

        service.applyDocMeta("app-global-data", "GET", "/x", docMeta());

        ArgumentCaptor<ApiEndpoint> captor = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getSummary()).isEqualTo("人工核过的说明");
    }

    @Test
    void 空的文档元数据不写库() {
        // 导了个空文档就把 doc_meta 清掉，会让上一次导入的结果凭空消失
        assertThat(service.applyDocMeta("p", "GET", "/x", Map.of())).isNull();
        verify(mapper, org.mockito.Mockito.never()).update(any());
    }
}
