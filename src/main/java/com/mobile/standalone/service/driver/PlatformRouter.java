package com.mobile.standalone.service.driver;

import com.mobile.standalone.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台路由
 * 根据设备平台选择对应的驱动。当 appium.enabled=true 时，Android/iOS 使用 Appium 驱动，否则使用 ADB/idevice 驱动。
 */
@Component
@Slf4j
public class PlatformRouter {

    private final AndroidDriver androidDriver;
    private final IOSDriver iosDriver;
    private final HarmonyOSDriver harmonyOSDriver;
    private final boolean appiumEnabled;
    private final AppiumDriverAdapter appiumDriverAdapter;

    public PlatformRouter(
            AndroidDriver androidDriver,
            IOSDriver iosDriver,
            HarmonyOSDriver harmonyOSDriver,
            @Value("${appium.enabled:false}") boolean appiumEnabled,
            @Autowired(required = false) AppiumDriverAdapter appiumDriverAdapter
    ) {
        this.androidDriver = androidDriver;
        this.iosDriver = iosDriver;
        this.harmonyOSDriver = harmonyOSDriver;
        this.appiumEnabled = appiumEnabled;
        this.appiumDriverAdapter = appiumDriverAdapter;
        log.info("PlatformRouter 初始化: appium.enabled={}, AppiumDriverAdapter={}, 驱动模式={}",
                appiumEnabled, appiumDriverAdapter != null ? "已加载" : "未加载",
                (appiumEnabled && appiumDriverAdapter != null) ? "Appium" : "ADB/idevice");
    }

    /**
     * 根据平台获取对应的驱动
     */
    public PlatformDriver getDriver(Device.Platform platform) {
        if (appiumEnabled && appiumDriverAdapter != null
                && (Device.usesAndroidAutomation(platform) && platform != Device.Platform.HARMONYOS
                    || Device.usesIosAutomation(platform))) {
            return appiumDriverAdapter;
        }
        switch (platform) {
            case ANDROID:
            case POG_ANDROID:
                return androidDriver;
            case IOS:
            case POG_IOS:
                return iosDriver;
            case HARMONYOS:
                return harmonyOSDriver;
            default:
                throw new IllegalArgumentException("不支持的平台类型: " + platform);
        }
    }
    
    /**
     * 根据设备获取对应的驱动
     */
    public PlatformDriver getDriver(Device device) {
        if (device == null || device.getPlatform() == null) {
            throw new IllegalArgumentException("设备或平台类型不能为空");
        }
        return getDriver(device.getPlatform());
    }
}
