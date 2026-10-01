package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iOS 真机点选坐标缩放：截图是 retina 像素(3x)，控件树是点坐标。
 * detectHierarchySize 必须从 XCUITest 根元素读出点坐标尺寸，hitTest 才会把点击像素
 * 缩回点坐标——否则点小控件(周边商城, y=156pt)会被放大 3 倍命中下面的大容器。
 */
class DeviceInspectIosScaleTest {

    // 402x874 点的 iOS 页面：周边商城 StaticText 在 (27,156)-(72,172)，下面一个大 Cell
    private static final String PAGE = """
            <?xml version='1.0' encoding='UTF-8'?>
            <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="完美世界电竞" x="0" y="0" width="402" height="874" visible="true">
              <XCUIElementTypeWindow type="XCUIElementTypeWindow" x="0" y="0" width="402" height="874" visible="true">
                <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" value="周边商城" name="周边商城" label="周边商城" x="27" y="156" width="45" height="16" visible="true"/>
                <XCUIElementTypeCell type="XCUIElementTypeCell" x="0" y="234" width="402" height="640" visible="true"/>
              </XCUIElementTypeWindow>
            </XCUIElementTypeApplication>
            """;

    @Test
    void 从XCUITest根元素读出点坐标尺寸() throws Exception {
        Method m = DeviceInspectService.class.getDeclaredMethod(
                "detectHierarchySize", String.class, int[].class);
        m.setAccessible(true);
        // 截图是 3x retina：1206x2622 像素
        int[] size = (int[]) m.invoke(null, PAGE, new int[]{1206, 2622});
        assertThat(size).containsExactly(402, 874);
    }

    @SuppressWarnings("unchecked")
    @Test
    void 点击周边商城像素缩回点坐标命中小节点而非大Cell() throws Exception {
        // 直接验 parseAndHit：传入已按点坐标映射后的 (49,164)（周边商城中心）
        DeviceInspectService svc = new DeviceInspectService(null, null, null, null, null);
        Method m = DeviceInspectService.class.getDeclaredMethod(
                "parseAndHit", String.class, int.class, int.class,
                Device.Platform.class, String.class, List.class);
        m.setAccessible(true);
        List<DeviceInspectService.HitCandidate> hits = (List<DeviceInspectService.HitCandidate>) m.invoke(
                svc, PAGE, 49, 164, Device.Platform.IOS, null, new ArrayList<String>());

        assertThat(hits).isNotEmpty();
        // 首选应是周边商城这个小 StaticText，而不是 y=234 的大 Cell
        assertThat(hits.get(0).getText()).isEqualTo("周边商城");
        var box = hits.get(0).getBounds();
        assertThat(box.get("top")).isEqualTo(156);
        assertThat(box.get("bottom")).isEqualTo(172);
    }
}
