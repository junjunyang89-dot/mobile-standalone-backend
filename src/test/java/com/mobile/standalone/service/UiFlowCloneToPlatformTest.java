package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiAppMapper;
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
 * 按 Android 流程生成 iOS 流程：步骤带走、platform 改 iOS，启动包名按目标端改写，并给出元素覆盖报告。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UiFlowCloneToPlatformTest {

    @Mock UiFlowMapper uiFlowMapper;
    @Mock UiElementMapper uiElementMapper;
    @Mock UiAppMapper uiAppMapper;
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

    private static UiFlow androidFlowWithColdStart() {
        UiFlow f = androidFlow();
        f.setAppId("DOMESTIC_ESPORTS");
        f.setSteps("[{\"keywordCode\":\"COLD_START\",\"params\":{"
                + "\"appId\":\"DOMESTIC_ESPORTS\","
                + "\"packageName\":\"com.pwrd.steam.esports\","
                + "\"activity\":\".ui.activity.launcher.LauncherActivity\"}}]");
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

    @Test
    void 克隆到iOS_冷启动包名改成BundleId_并去掉activity() {
        when(uiFlowMapper.findById("f1")).thenReturn(androidFlowWithColdStart());
        UiApp app = new UiApp();
        app.setId("DOMESTIC_ESPORTS");
        app.setAndroidPackage("com.pwrd.steam.esports");
        app.setIosBundleId("com.wmzq.esportapp");
        when(uiAppMapper.findById("DOMESTIC_ESPORTS")).thenReturn(app);

        var r = service.cloneToPlatform("f1", "IOS", null);

        assertThat(r.flow().getSteps()).contains("com.wmzq.esportapp");
        assertThat(r.flow().getSteps()).doesNotContain("com.pwrd.steam.esports");
        assertThat(r.flow().getSteps()).doesNotContain("activity");
        assertThat(r.flow().getSteps()).doesNotContain("LauncherActivity");
    }

    @Test
    void 克隆纠正源流程幽灵元素名() {
        UiFlow src = new UiFlow();
        src.setId("f1");
        src.setName("周边商城浏览");
        src.setAppId("DOMESTIC_ESPORTS");
        src.setPlatform("ANDROID");
        src.setEnvironment("PROD");
        // AI 编排编了个库里不存在的名字；真元素是 discover_top_nav_slide_area
        src.setSteps("[{\"keywordCode\":\"SWIPE_IN_ELEMENT\",\"elementKey\":\"cs_discover_top_nav_bar\"}]");
        when(uiFlowMapper.findById("f1")).thenReturn(src);

        UiElement real = el();
        real.setNameEn("discover_top_nav_slide_area");
        when(uiElementMapper.findByFilters(
                eq("DOMESTIC_ESPORTS"), eq("IOS"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq("ACTIVE"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(java.util.List.of(real));
        when(uiElementMapper.findActiveByKey("DOMESTIC_ESPORTS", "ANDROID", "discover_top_nav_slide_area"))
                .thenReturn(real);
        when(uiElementMapper.findActiveByKey("DOMESTIC_ESPORTS", "IOS", "discover_top_nav_slide_area"))
                .thenReturn(real);

        var r = service.cloneToPlatform("f1", "IOS", null);

        assertThat(r.remapped()).containsEntry("cs_discover_top_nav_bar", "discover_top_nav_slide_area");
        assertThat(r.flow().getSteps()).contains("discover_top_nav_slide_area");
        assertThat(r.flow().getSteps()).doesNotContain("cs_discover_top_nav_bar");
        assertThat(r.ready()).contains("discover_top_nav_slide_area");
        assertThat(r.sourceMissing()).isEmpty();
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void uniqueSimilarName_词重叠够才命中() {
        assertThat(UiFlowService.uniqueSimilarName(
                "cs_discover_top_nav_bar",
                java.util.List.of("discover_top_nav_slide_area", "mall_category_tab")))
                .isEqualTo("discover_top_nav_slide_area");
        assertThat(UiFlowService.uniqueSimilarName(
                "totally_unrelated_key",
                java.util.List.of("discover_top_nav_slide_area")))
                .isNull();
    }
}
