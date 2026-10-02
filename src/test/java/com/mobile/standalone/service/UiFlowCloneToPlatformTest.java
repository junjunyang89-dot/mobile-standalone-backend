package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiElementMapper;
import com.mobile.standalone.mapper.UiFlowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 按 Android 流程生成 iOS 流程：步骤原样带走、platform 改 iOS，并给出元素覆盖报告。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UiFlowCloneToPlatformTest {

    @Mock UiFlowMapper uiFlowMapper;
    @Mock UiElementMapper uiElementMapper;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks UiFlowService service;

    private static UiFlow androidFlow() {
        UiFlow f = new UiFlow();
        f.setId("f1");
        f.setName("周边商城浏览");
        f.setAppId("app1");
        f.setPlatform("ANDROID");
        f.setEnvironment("PROD");
        f.setSteps("[{\"keywordCode\":\"CLICK\",\"elementKey\":\"mall_entry\"},"
                + "{\"keywordCode\":\"ASSERT_TEXT\",\"elementKey\":\"mall_title\"},"
                + "{\"keywordCode\":\"CLICK\",\"elementKey\":\"mall_missing\"}]");
        return f;
    }

    private static UiElement el() {
        UiElement e = new UiElement();
        e.setId("e1");
        return e;
    }

    @Test
    void 克隆到iOS_步骤带走_平台改为iOS() {
        when(uiFlowMapper.findById("f1")).thenReturn(androidFlow());

        var r = service.cloneToPlatform("f1", "IOS", null);

        assertThat(r).isNotNull();
        assertThat(r.flow().getPlatform()).isEqualTo("IOS");
        assertThat(r.flow().getName()).contains("(iOS)");
        // 步骤原样带走：三个 elementKey 都在
        assertThat(r.flow().getSteps()).contains("mall_entry").contains("mall_title").contains("mall_missing");
        assertThat(r.flow().getEnvironment()).isEqualTo("PROD");
    }

    @Test
    void 覆盖报告区分_已就绪_待定位_缺失() {
        when(uiFlowMapper.findById("f1")).thenReturn(androidFlow());
        // mall_entry：iOS 已有 ACTIVE 元素 → ready
        when(uiElementMapper.findActiveByKey("app1", "IOS", "mall_entry")).thenReturn(el());
        // mall_title：无 ACTIVE，但同名占位已在 → needLocator
        when(uiElementMapper.findActiveByKey("app1", "IOS", "mall_title")).thenReturn(null);
        when(uiElementMapper.findByAppPlatformKey("app1", "IOS", "mall_title", null)).thenReturn(el());
        // mall_missing：都没有 → missing
        when(uiElementMapper.findActiveByKey("app1", "IOS", "mall_missing")).thenReturn(null);
        when(uiElementMapper.findByAppPlatformKey("app1", "IOS", "mall_missing", null)).thenReturn(null);

        var r = service.cloneToPlatform("f1", "ios", null);

        assertThat(r.elementCount()).isEqualTo(3);
        assertThat(r.ready()).containsExactly("mall_entry");
        assertThat(r.needLocator()).containsExactly("mall_title");
        assertThat(r.missing()).containsExactly("mall_missing");
    }

    @Test
    void 源流程不存在返回null() {
        when(uiFlowMapper.findById("nope")).thenReturn(null);
        assertThat(service.cloneToPlatform("nope", "IOS", null)).isNull();
    }

    @Test
    void 目标平台为空报错() {
        when(uiFlowMapper.findById("f1")).thenReturn(androidFlow());
        assertThatThrownBy(() -> service.cloneToPlatform("f1", "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标平台");
    }

    @Test
    void 环境可覆盖() {
        when(uiFlowMapper.findById("f1")).thenReturn(androidFlow());
        var r = service.cloneToPlatform("f1", "IOS", "TEST");
        assertThat(r.flow().getEnvironment()).isEqualTo("TEST");
    }
}
