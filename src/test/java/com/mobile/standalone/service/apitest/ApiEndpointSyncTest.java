package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
     * 每次同步先清掉<b>这个分支</b>上一轮的标记，别的分支不动。
     * <p>
     * 不清的话「上次新增」会一直挂着；清全项目的话，分析 B 分支会把 A 分支的必测接口清掉。
     */
    @Test
    void 同步前只清这个分支上一轮的标记() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        new ApiEndpointService(mapper, null).syncFromChangeReport("p", "b", List.of());

        verify(mapper).deleteChangesByBranch("p", "b");
        verify(mapper, never()).clearChangeFlags(anyString());
        verify(mapper).recomputeChangeFlags(List.of("p"));
    }

    @Test
    void 每条变更按分支记一笔且带分支版本() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint ep = existing("ep-2");
        ep.setCodePath("/back");
        when(mapper.findByKey("p", "GET", "/back")).thenReturn(ep);

        new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "feat/x", List.of(ch("GET", "/back", "MODIFIED", "FIELD_CHANGED")));

        verify(mapper).upsertChangeWithSnapshot(eq("ep-2"), eq("p"), eq("feat/x"), eq("FIELD_CHANGED"), eq("备注"),
                eq("MODIFIED"), eq("SomeController"), eq("说明"), eq("Some.java"), anyString(), anyString());
    }

    /** 分支没上线前全量那条保持 master 的样子，两个分支并行提测也不会互相覆盖 */
    @Test
    void 分支改动不覆盖全量里的接口() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint ep = existing("ep-2");
        ep.setCodePath("/back");
        when(mapper.findByKey("p", "GET", "/back")).thenReturn(ep);

        new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "feat/x", List.of(ch("GET", "/back", "MODIFIED", "FIELD_CHANGED")));

        verify(mapper, never()).update(any());
    }

    /** 报告里是代码路径；清单里那条是抓包建的（带网关前缀）——认它，不另建 */
    @Test
    void 按代码路径认上抓包建的接口() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint cap = existing("ep-cap");
        cap.setProject("p");
        cap.setHttpMethod("POST");
        cap.setPath("/steamcn/community/task/signIn");
        cap.setCaptureMeta("{\"capturedAt\":\"x\"}");
        when(mapper.listByProject("p", null)).thenReturn(List.of(cap));

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("POST", "/community/task/signIn", "MODIFIED", "FIELD_CHANGED")));

        assertThat(r.created()).isZero();
        verify(mapper, never()).insert(any());
        ArgumentCaptor<ApiEndpoint> c = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(c.capture());
        assertThat(c.getValue().getId()).isEqualTo("ep-cap");
        assertThat(c.getValue().getCodePath()).isEqualTo("/community/task/signIn");
    }

    /** 分支删掉的接口只记一笔「分支删除」，全量那条上线前不动，更不会被删 */
    @Test
    void 分支删除的接口上线前不标废弃() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        ApiEndpoint ep = existing("ep-1");
        ep.setCodePath("/gone");
        when(mapper.findByKey("p", "GET", "/gone")).thenReturn(ep);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                "p", "b", List.of(ch("GET", "/gone", "REMOVED", null)));

        assertThat(r.deprecated()).isEqualTo(1);
        verify(mapper, never()).deleteById(anyString());
        verify(mapper, never()).update(any());
        verify(mapper).upsertChangeWithSnapshot(eq("ep-1"), eq("p"), eq("b"), eq("REMOVED"), anyString(),
                eq("REMOVED"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void 上线时老接口换成分支版本() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.listBranchSnapshots("p", "feat/x")).thenReturn(List.of(Map.of(
                "endpointId", "ep-2", "changeFlag", "FIELD_CHANGED", "changeType", "MODIFIED",
                "handler", "NewController.list", "fieldsSnapshot", "[\"code\",\"data\",\"extra\"]")));
        when(mapper.clearSourceBranchOf("p", "feat/x")).thenReturn(2);

        var r = new ApiEndpointService(mapper, null).promoteBranchToMaster("p", "feat/x");

        ArgumentCaptor<ApiEndpoint> cap = ArgumentCaptor.forClass(ApiEndpoint.class);
        verify(mapper).update(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo("ep-2");
        assertThat(cap.getValue().getHandler()).isEqualTo("NewController.list");
        assertThat(cap.getValue().getFieldsSnapshot()).isEqualTo("[\"code\",\"data\",\"extra\"]");
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.added()).isEqualTo(2);
        verify(mapper).deleteChangesByBranch("p", "feat/x");
        verify(mapper).recomputeChangeFlags(List.of("p"));
    }

    @Test
    void 上线时分支删除的接口才标废弃() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.listBranchSnapshots("p", "b")).thenReturn(List.of(Map.of(
                "endpointId", "ep-1", "changeFlag", "REMOVED", "changeType", "REMOVED")));

        var r = new ApiEndpointService(mapper, null).promoteBranchToMaster("p", "b");

        verify(mapper).markDeprecated("ep-1");
        verify(mapper, never()).update(any());
        assertThat(r.deprecated()).isEqualTo(1);
    }

    /** 新增接口那一行本身就是分支版本，上线只是去掉分支归属 */
    @Test
    void 上线时新增接口不按快照覆盖() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.listBranchSnapshots("p", "b")).thenReturn(List.of(Map.of(
                "endpointId", "ep-new", "changeFlag", "NEW", "changeType", "ADDED")));
        when(mapper.clearSourceBranchOf("p", "b")).thenReturn(1);

        var r = new ApiEndpointService(mapper, null).promoteBranchToMaster("p", "b");

        verify(mapper, never()).update(any());
        assertThat(r.added()).isEqualTo(1);
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

        verify(mapper).insert(any());
        verify(mapper, never()).update(any());
        verify(mapper, never()).updateGone(anyString(), any(), any());
        verify(mapper, never()).deleteById(anyString());
    }

    @Test
    void 项目为空时不做任何事() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);

        var r = new ApiEndpointService(mapper, null).syncFromChangeReport(
                null, "b", List.of(ch("GET", "/a", "ADDED", "NEW")));

        assertThat(r.created()).isZero();
        verify(mapper, never()).deleteChangesByBranch(any(), any());
    }
}
