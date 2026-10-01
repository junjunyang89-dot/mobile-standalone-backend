package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Android 的 id+text 组合 xpath 要带元素类型（和 Appium Inspector 一致）：
 * //android.widget.TextView[@resource-id="..." and @text="周边商城"]，而不是 //*[...]。
 * 复用 id（tv_text 多个金刚位共用）靠 id+text 消歧时尤其需要带类型。
 */
class DeviceInspectAndroidXpathTest {

    private static final String PAGE = """
            <?xml version='1.0' encoding='UTF-8'?>
            <hierarchy rotation="0" width="1080" height="2000">
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2000]" displayed="true">
                <node class="android.widget.TextView" resource-id="com.pwrd.steam.esports:id/tv_text"
                      text="周边商城" clickable="true" displayed="true" bounds="[40,200][200,280]"/>
              </node>
            </hierarchy>
            """;

    @SuppressWarnings("unchecked")
    @Test
    void 组合xpath带元素类型() throws Exception {
        DeviceInspectService svc = new DeviceInspectService(null, null, null, null, null);
        Method m = DeviceInspectService.class.getDeclaredMethod(
                "parseAndHit", String.class, int.class, int.class,
                Device.Platform.class, String.class, List.class);
        m.setAccessible(true);
        List<DeviceInspectService.HitCandidate> hits = (List<DeviceInspectService.HitCandidate>) m.invoke(
                svc, PAGE, 120, 240, Device.Platform.ANDROID, null, new ArrayList<String>());

        List<String> xpaths = hits.stream()
                .filter(c -> "xpath".equals(c.getLocatorType()))
                .map(DeviceInspectService.HitCandidate::getLocatorValue)
                .toList();
        // 带类型的组合 xpath，而不是 //*[...]
        assertThat(xpaths).anyMatch(v -> v.contains(
                "//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_text' and @text='周边商城']"));
    }
}
