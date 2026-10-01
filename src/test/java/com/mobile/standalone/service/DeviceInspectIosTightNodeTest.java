package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * iOS 真机点无标签图标：别把红框罩到大容器上。
 * <p>金刚位图标在 XCUITest 里常是无 name/label 的 Button/Other，isInteresting 判为"无趣"
 * 被跳过，旧逻辑只能选到带标签的大祖先——红框罩住一大片。应像 Appium Inspector 一样
 * 选落点下最深(最小)的节点，红框贴合实际点中的控件。
 */
class DeviceInspectIosTightNodeTest {

    // app → 大 Other → 中 Other → 小 Button(点中的图标)；全部无 name/label
    private static final String ICON_PAGE = """
            <?xml version='1.0' encoding='UTF-8'?>
            <XCUIElementTypeApplication type="XCUIElementTypeApplication" x="0" y="0" width="400" height="800" visible="true">
              <XCUIElementTypeOther type="XCUIElementTypeOther" x="0" y="0" width="400" height="760" visible="true">
                <XCUIElementTypeOther type="XCUIElementTypeOther" x="10" y="100" width="380" height="300" visible="true">
                  <XCUIElementTypeButton type="XCUIElementTypeButton" x="20" y="120" width="60" height="60" visible="true"/>
                </XCUIElementTypeOther>
              </XCUIElementTypeOther>
            </XCUIElementTypeApplication>
            """;

    @SuppressWarnings("unchecked")
    private List<DeviceInspectService.HitCandidate> hit(String xml, int px, int py) throws Exception {
        DeviceInspectService svc = new DeviceInspectService(null, null, null, null, null);
        Method m = DeviceInspectService.class.getDeclaredMethod(
                "parseAndHit", String.class, int.class, int.class,
                Device.Platform.class, String.class, List.class);
        m.setAccessible(true);
        return (List<DeviceInspectService.HitCandidate>) m.invoke(
                svc, xml, px, py, Device.Platform.IOS, null, new ArrayList<String>());
    }

    // 金刚位图标常是 visible="false"：大 Cell 可见，里面的小图标不可见
    private static final String HIDDEN_ICON_PAGE = """
            <?xml version='1.0' encoding='UTF-8'?>
            <XCUIElementTypeApplication type="XCUIElementTypeApplication" x="0" y="0" width="400" height="800" visible="true">
              <XCUIElementTypeCell type="XCUIElementTypeCell" x="0" y="234" width="400" height="649" visible="true">
                <XCUIElementTypeButton type="XCUIElementTypeButton" x="20" y="250" width="60" height="60" visible="false"/>
              </XCUIElementTypeCell>
            </XCUIElementTypeApplication>
            """;

    @Test
    void visible为false的小图标也要能选中而非大Cell() throws Exception {
        // (40,270) 落在不可见的小 Button(20..80, 250..310) 内，也在大 Cell(0..400,234..883) 内
        List<DeviceInspectService.HitCandidate> hits = hit(HIDDEN_ICON_PAGE, 40, 270);

        assertThat(hits).isNotEmpty();
        var box = hits.get(0).getBounds();
        assertThat(box).isNotNull();
        // 贴合 60x60 的隐藏图标，而不是 400x649 的大 Cell
        assertThat(box.get("right") - box.get("left")).isEqualTo(60);
        assertThat(box.get("bottom") - box.get("top")).isEqualTo(60);
        assertThat(box.get("left")).isEqualTo(20);
        assertThat(box.get("top")).isEqualTo(250);
    }

    @Test
    void 点无标签图标红框贴合最小节点而非大容器() throws Exception {
        // (40,140) 落在小 Button(20..80, 120..180) 内，也落在两层大 Other 内
        List<DeviceInspectService.HitCandidate> hits = hit(ICON_PAGE, 40, 140);

        assertThat(hits).isNotEmpty();
        var box = hits.get(0).getBounds();
        assertThat(box).isNotNull();
        int w = box.get("right") - box.get("left");
        int h = box.get("bottom") - box.get("top");
        // 贴合 60x60 的图标，而不是 380x300 / 400x760 的容器
        assertThat(w).isLessThanOrEqualTo(80);
        assertThat(h).isLessThanOrEqualTo(80);
        assertThat(box.get("left")).isEqualTo(20);
        assertThat(box.get("top")).isEqualTo(120);
    }
}
