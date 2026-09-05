package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步是跟着每次「接口对比分析」自动跑的，所以它出错的方式是静默的：
 * 清单看着更新了，实际标错了一片。这些是最容易标错的几种。
 */
class ApiEndpointSyncTest {

    private EndpointChange ch(String method, String path, String type, String flag) {
        return new EndpointChange(method, path, "SomeController", "说明", "Some.java", type, flag, "备注",
                java.util.List.of("code", "data"), java.util.List.of("some_table"));
    }

    private ApiEndpoint existing(String id) {
        ApiEndpoint e = new ApiEndpoint();
        e.setId(id);
        return e;
    }

    @Test
    void 新接口插进清单() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "feat/x", List.of(ch("GET", "/a", "ADDED", "NEW")));

        assertThat(r.created()).isEqualTo(1);
        verify(mapper).insert(any());
    }

    /**
     * 每次同步先清掉上一轮的标记。
     * <p>
     * 不清的话「上次新增」会一直挂着，几个版本之后整份清单全是高亮，等于没有高亮。
     */
    @Test
    void 同步前先清掉上一轮的标记() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        new ApiEndpointService(mapper, null).syncFromChangeReport("p", "b", List.of());

        verify(mapper).clearChangeFlags("p");
    }

    /** 删除的接口标废弃，不删——挂在下面的用例不能变孤儿 */
    @Test
    void 删除的接口标废弃而不是删掉() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey("p", "GET", "/gone")).thenReturn(existing("ep-1"));

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("GET", "/gone", "REMOVED", null)));

        assertThat(r.deprecated()).isEqualTo(1);
        verify(mapper, never()).deleteById(anyString());
        ArgumentCaptor<ApiEndpoint> cap = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("DEPRECATED");
    }

    /** 上次标了废弃、这次又出现：多半是分支切换或合错导致的误判，要恢复成活的 */
    @Test
    void 废弃的接口再次出现时恢复() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey("p", "GET", "/back")).thenReturn(existing("ep-2"));

        new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("GET", "/back", "MODIFIED", "FIELD_CHANGED")));

        ArgumentCaptor<ApiEndpoint> cap = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    /** 清单里本来就没有、这次说它被删了：没什么可记的，别建一条一出生就是废弃的 */
    @Test
    void 不存在的接口被删时不建新行() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("GET", "/never-existed", "REMOVED", null)));

        assertThat(r.created()).isZero();
        verify(mapper, never()).insert(any());
    }

    /** 路径为空的脏数据跳过，别在清单里留一行点不开的空记录 */
    @Test
    void 脏数据跳过() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport("p", "b", List.of(
                ch("GET", "", "ADDED", "NEW"),
                ch(null, "/a", "ADDED", "NEW")));

        assertThat(r.created()).isZero();
        verify(mapper, never()).insert(any());
    }

    /**
     * 报告里只有<b>变更过</b>的接口，所以同步只能动这些行。
     * 绝不能因为「某条没出现在报告里」就把它标成废弃——那会把整份清单标掉。
     */
    @Test
    void 不碰报告里没出现的接口() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("GET", "/a", "ADDED", "NEW")));

        verify(mapper, never()).markDeprecatedNotIn(anyString(), any());
    }

    @Test
    void 项目为空时不做任何事() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                null, "b", List.of(ch("GET", "/a", "ADDED", "NEW")));

        assertThat(r.created()).isZero();
        verify(mapper, never()).clearChangeFlags(anyString());
    }
}
