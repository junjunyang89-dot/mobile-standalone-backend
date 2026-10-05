package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迁移会在<b>每次启动时</b>自动跑到正式库上，所以这两条必须钉死：
 * 已经挂好的不能重复建，一条用例都不能被改动。
 */
class ApiEndpointMigrationTest {

    private ApiEndpoint draft(String project, String method, String path) {
        ApiEndpoint e = new ApiEndpoint();
        e.setProject(project);
        e.setHttpMethod(method);
        e.setPath(path);
        return e;
    }

    /** 用例全都挂好了就直接返回，一次查询都不该再发 */
    @Test
    void 没有待迁移的用例时什么都不做() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.countUnlinkedCases()).thenReturn(0);

        int created = new ApiEndpointService(mapper, null).migrateExistingCases();

        assertThat(created).isZero();
        verify(mapper, never()).distinctEndpointsFromCases();
        verify(mapper, never()).insert(any());
    }

    @Test
    void 首次迁移按方法加路径建接口并把用例挂上() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.countUnlinkedCases()).thenReturn(3);
        when(mapper.distinctEndpointsFromCases()).thenReturn(List.of(
                draft("data-center", "GET", "/a"),
                draft("data-center", "POST", "/b")));
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        int created = new ApiEndpointService(mapper, null).migrateExistingCases();

        assertThat(created).isEqualTo(2);
        verify(mapper, times(2)).insert(any());
        // 每个接口都要把它下面的用例挂上，否则清单是空壳、用例还是孤儿
        verify(mapper, times(2)).linkCasesToEndpoint(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 接口已经存在时复用它，不再插一条。
     * <p>
     * 不复用的话，第二次启动会因为唯一键冲突整个迁移失败，
     * 而失败是被 catch 掉的——表现就是「清单莫名其妙只有一半」。
     */
    @Test
    void 接口已存在时复用不重复建() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.countUnlinkedCases()).thenReturn(1);
        when(mapper.distinctEndpointsFromCases()).thenReturn(List.of(draft("p", "GET", "/a")));
        ApiEndpoint existing = draft("p", "GET", "/a");
        existing.setId("ep-existing");
        when(mapper.findByKey("p", "GET", "/a")).thenReturn(existing);

        int created = new ApiEndpointService(mapper, null).migrateExistingCases();

        assertThat(created).isZero();
        verify(mapper, never()).insert(any());
        // 仍然要挂：接口在、用例没挂上，正是「上一轮迁移中途断了」的状态
        verify(mapper).linkCasesToEndpoint("ep-existing", "p", "GET", "/a");
    }

    /** 迁移只增不改：一条 update / delete 都不该发出去 */
    @Test
    void 迁移不修改也不删除任何用例() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.countUnlinkedCases()).thenReturn(2);
        when(mapper.distinctEndpointsFromCases()).thenReturn(List.of(draft("p", "GET", "/a")));
        when(mapper.findByKey(anyString(), anyString(), anyString())).thenReturn(null);

        new ApiEndpointService(mapper, null).migrateExistingCases();

        verify(mapper, never()).update(any());
        verify(mapper, never()).deleteById(anyString());
    }

    /** 表还没建好时（比如列还没补上）安静跳过，不能把整个启动带崩 */
    @Test
    void 查不到时安静跳过() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.countUnlinkedCases()).thenThrow(new RuntimeException("Unknown column"));

        assertThat(new ApiEndpointService(mapper, null).migrateExistingCases()).isZero();
    }
}
