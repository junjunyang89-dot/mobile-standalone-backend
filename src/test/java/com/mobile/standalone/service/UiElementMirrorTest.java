package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.mapper.UiElementMapper;
import com.mobile.standalone.mapper.UiFlowMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UiElementMirrorTest {

    private final UiElementMapper mapper = mock(UiElementMapper.class);
    private final UiElementService service =
            new UiElementService(mapper, mock(UiFlowMapper.class), mock(UiPageService.class));

    private static UiElement element(String platform, String source) {
        UiElement e = new UiElement();
        e.setAppId("DOMESTIC_ESPORTS");
        e.setPlatform(platform);
        e.setNameZh("登录按钮");
        e.setNameEn("login_btn");
        e.setDescription("首页右上角");
        e.setPageId("CS_DOTA_MERCHANDISE");
        e.setLocatorType("id");
        e.setLocatorValue("com.pwrd:id/login");
        e.setSource(source);
        e.setStatus("ACTIVE");
        return e;
    }

    @Test
    void androidElementGetsEmptyIosTwin() {
        List<String> done = service.mirrorToSiblingPlatforms(element("ANDROID", "MANUAL"));

        assertThat(done).containsExactly("IOS");
        ArgumentCaptor<UiElement> saved = ArgumentCaptor.forClass(UiElement.class);
        verify(mapper).insert(saved.capture());
        UiElement twin = saved.getValue();
        assertThat(twin.getPlatform()).isEqualTo("IOS");
        assertThat(twin.getAppId()).isEqualTo("DOMESTIC_ESPORTS");
        assertThat(twin.getNameEn()).isEqualTo("login_btn");
        assertThat(twin.getNameZh()).isEqualTo("登录按钮");
        assertThat(twin.getPageId()).isEqualTo("CS_DOTA_MERCHANDISE");
        assertThat(twin.getDescription()).isEqualTo("首页右上角");
        assertThat(twin.getLocatorValue()).isEmpty();
        assertThat(twin.getStatus()).isEqualTo("CANDIDATE");
        assertThat(twin.getSource()).isEqualTo("PLATFORM_COPY");
    }

    @Test
    void iosElementGetsAndroidTwin() {
        assertThat(service.mirrorToSiblingPlatforms(element("IOS", "HUMAN_AI"))).containsExactly("ANDROID");
    }

    @Test
    void existingTwinIsLeftAlone() {
        when(mapper.findByAppPlatformKey(eq("DOMESTIC_ESPORTS"), eq("IOS"), eq("login_btn"), isNull()))
                .thenReturn(element("IOS", "MANUAL"));

        assertThat(service.mirrorToSiblingPlatforms(element("ANDROID", "MANUAL"))).isEmpty();
        verify(mapper, never()).insert(any());
    }

    @Test
    void harmonyAndBatchSourcesAreNotMirrored() {
        UiElement harmony = element("HARMONYOS", "MANUAL");
        harmony.setAppId("DOMESTIC_ESPORTS_HARMONY");

        assertThat(service.mirrorToSiblingPlatforms(harmony)).isEmpty();
        assertThat(service.mirrorToSiblingPlatforms(element("ANDROID", "RECORDED"))).isEmpty();
        assertThat(service.mirrorToSiblingPlatforms(element("ANDROID", "PLATFORM_COPY"))).isEmpty();
        verify(mapper, never()).insert(any());
    }
}
