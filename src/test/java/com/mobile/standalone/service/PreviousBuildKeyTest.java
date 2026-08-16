package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.entity.UiApp;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 升级测试要装的「上一版」必须显式解析出 buildKey。
 * <p>
 * 不传 buildKey 时安装走的是「装最新版」——那就成了拿新版覆盖新版，
 * 升级路径一步都没走到，而这一批照样跑绿。绿着漏掉的东西最难发现，
 * 所以这里专门钉住：拿到的必须是上一版那个 key。
 */
class PreviousBuildKeyTest {

    private AppPackageInstallService serviceWith(UiApp app, PgyerAppService.PgyerBuildVo prev) {
        UiAppService uiAppService = mock(UiAppService.class);
        when(uiAppService.get(anyString())).thenReturn(app);

        PgyerAppService pgyer = mock(PgyerAppService.class);
        when(pgyer.previousBuild(anyString(), anyString(), any())).thenReturn(prev);

        AppPackageInstallService svc = new AppPackageInstallService(
                null, null, null, uiAppService, null, null);
        ReflectionTestUtils.setField(svc, "pgyerAppService", pgyer);
        return svc;
    }

    private UiApp appWithKeys() {
        UiApp app = new UiApp();
        app.setPgyerAppKeyAndroid("key-android");
        app.setPgyerAppKeyIos("key-ios");
        return app;
    }

    private Device device(Device.Platform platform) {
        Device d = new Device();
        d.setId("dev-1");
        d.setPlatform(platform);
        return d;
    }

    @Test
    void 取到上一版的buildKey() {
        PgyerAppService.PgyerBuildVo prev = new PgyerAppService.PgyerBuildVo();
        prev.setBuildKey("bk-previous");

        String key = serviceWith(appWithKeys(), prev).resolvePreviousBuildKey("app-1", device(Device.Platform.ANDROID));

        assertThat(key).isEqualTo("bk-previous");
    }

    /** 只有一个版本时没有「上一版」——返回 null，让调用方判失败而不是悄悄装最新版 */
    @Test
    void 只有一个版本时返回null() {
        assertThat(serviceWith(appWithKeys(), null).resolvePreviousBuildKey("app-1", device(Device.Platform.ANDROID)))
                .isNull();
    }

    /** 没配 appKey 时也返回 null，同理：宁可报错，不要装成最新版 */
    @Test
    void 没配appKey时返回null() {
        UiApp app = new UiApp();
        PgyerAppService.PgyerBuildVo prev = new PgyerAppService.PgyerBuildVo();
        prev.setBuildKey("bk-previous");

        assertThat(serviceWith(app, prev).resolvePreviousBuildKey("app-1", device(Device.Platform.ANDROID))).isNull();
    }

    /** iOS 和 Android 是两个不同的蒲公英应用，取错 key 会装到另一端的包上去 */
    @Test
    void 按设备平台取对应的appKey() {
        UiAppService uiAppService = mock(UiAppService.class);
        when(uiAppService.get(anyString())).thenReturn(appWithKeys());

        PgyerAppService.PgyerBuildVo prev = new PgyerAppService.PgyerBuildVo();
        prev.setBuildKey("bk-ios-previous");
        PgyerAppService pgyer = mock(PgyerAppService.class);
        when(pgyer.previousBuild(eq("key-ios"), eq("IOS"), any())).thenReturn(prev);

        AppPackageInstallService svc = new AppPackageInstallService(
                null, null, null, uiAppService, null, null);
        ReflectionTestUtils.setField(svc, "pgyerAppService", pgyer);

        assertThat(svc.resolvePreviousBuildKey("app-1", device(Device.Platform.IOS))).isEqualTo("bk-ios-previous");
    }
}
