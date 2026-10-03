package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiElementMapper;
import com.mobile.standalone.mapper.UiFlowMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新建流程时，NOT NULL 的列一个都不能漏。
 *
 * <p>这个测试是补出来的：{@code create()} 给 steps / catalog / environment / envBound /
 * isComponent / enabled / sortOrder 都兜了底，唯独漏了 {@code releaseStage}——
 * 而 {@code ui_flows.release_stage} 是 NOT NULL。
 * 于是任何不自己设这个字段的调用方，INSERT 都会被数据库直接打回来。
 *
 * <p>列上写着 {@code DEFAULT 'ONLINE'} 也救不了：INSERT 语句显式列出了这一列并传了 null，
 * 而 DEFAULT 只在整列被<b>省略</b>时才生效。这是个很容易看漏的区别。
 *
 * <p>这类问题编译期和现有单测都抓不到——它只在真的连上数据库时才炸。
 * 所以这里改成在<b>写库之前</b>拦一道：把要落库的对象截下来，逐个断言非空。
 */
class UiFlowCreateDefaultsTest {

    private UiFlow createWith(UiFlow input, UiFlowMapper mapper) {
        UiFlowService service = new UiFlowService(mapper, mock(UiElementMapper.class), mock(com.mobile.standalone.mapper.UiAppMapper.class), mock(JdbcTemplate.class));
        return service.create(input);
    }

    private static UiFlow minimal() {
        UiFlow f = new UiFlow();
        f.setName("录制-登录冒烟");
        return f;
    }

    /** 只给一个名字就建，落库的对象里 NOT NULL 的列必须全都有值 */
    @Test
    void 只给名字时所有必填列都被兜底() {
        UiFlowMapper mapper = mock(UiFlowMapper.class);
        createWith(minimal(), mapper);

        ArgumentCaptor<UiFlow> captor = ArgumentCaptor.forClass(UiFlow.class);
        verify(mapper).insert(captor.capture());
        UiFlow saved = captor.getValue();

        assertNotNull(saved.getId(), "id");
        assertNotNull(saved.getSteps(), "steps");
        assertNotNull(saved.getCatalog(), "catalog");
        assertNotNull(saved.getEnvironment(), "environment");
        assertNotNull(saved.getEnvBound(), "envBound");
        assertNotNull(saved.getIsComponent(), "isComponent");
        assertNotNull(saved.getEnabled(), "enabled");
        assertNotNull(saved.getSortOrder(), "sortOrder");
        assertNotNull(saved.getCreatedAt(), "createdAt");
        assertNotNull(saved.getUpdatedAt(), "updatedAt");
        // 就是这一条漏了，导致「保存草稿」直接报 Column 'release_stage' cannot be null
        assertNotNull(saved.getReleaseStage(), "releaseStage —— NOT NULL 列，不兜底就会被数据库打回来");
    }

    /** 调用方显式传了就按它的来，别被兜底覆盖掉 */
    @Test
    void 显式指定的releaseStage不被覆盖() {
        UiFlowMapper mapper = mock(UiFlowMapper.class);
        UiFlow f = minimal();
        f.setReleaseStage("TESTING");
        createWith(f, mapper);

        ArgumentCaptor<UiFlow> captor = ArgumentCaptor.forClass(UiFlow.class);
        verify(mapper).insert(captor.capture());
        assertEquals("TESTING", captor.getValue().getReleaseStage());
    }

    /** 认不出的写法归一到 ONLINE：存量流程本来就在线上回归里跑着，一条都不能掉出去 */
    @Test
    void 认不出的releaseStage归一到ONLINE() {
        UiFlowMapper mapper = mock(UiFlowMapper.class);
        UiFlow f = minimal();
        f.setReleaseStage("乱写的");
        createWith(f, mapper);

        ArgumentCaptor<UiFlow> captor = ArgumentCaptor.forClass(UiFlow.class);
        verify(mapper).insert(captor.capture());
        assertEquals("ONLINE", captor.getValue().getReleaseStage());
    }

    /** 编辑时改成已上线必须写回。以前 update 丢掉这个字段，保存后再打开仍是本版新功能 */
    @Test
    void 更新时releaseStage要写回去() {
        UiFlowMapper mapper = mock(UiFlowMapper.class);
        UiFlow existing = new UiFlow();
        existing.setId("uif-1");
        existing.setName("浏览周边商城页面");
        existing.setReleaseStage("TESTING");
        existing.setEnvironment("TEST");
        when(mapper.findById("uif-1")).thenReturn(existing);

        UiFlowService service = new UiFlowService(mapper, mock(UiElementMapper.class), mock(com.mobile.standalone.mapper.UiAppMapper.class), mock(JdbcTemplate.class));
        UiFlow patch = new UiFlow();
        patch.setReleaseStage("ONLINE");
        service.update("uif-1", patch);

        ArgumentCaptor<UiFlow> captor = ArgumentCaptor.forClass(UiFlow.class);
        verify(mapper).update(captor.capture());
        assertEquals("ONLINE", captor.getValue().getReleaseStage());
    }

    @Test
    void 归一化本身() {
        assertEquals("TESTING", UiFlowService.normalizeReleaseStage("TESTING"));
        assertEquals("TESTING", UiFlowService.normalizeReleaseStage("testing"));
        assertEquals("ONLINE", UiFlowService.normalizeReleaseStage(null));
        assertEquals("ONLINE", UiFlowService.normalizeReleaseStage(""));
        assertEquals("ONLINE", UiFlowService.normalizeReleaseStage("ONLINE"));
    }
}
