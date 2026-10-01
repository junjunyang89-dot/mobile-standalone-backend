package com.mobile.standalone.service.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceSettingVariantTest {

    @Test
    void 基础作业的jobKey不是变体() {
        assertNull(DeviceSettingVariant.ofJobKey("DOMESTIC_ESPORTS__emulator-5554"));
        assertNull(DeviceSettingVariant.ofJobKey("DOMESTIC_ESPORTS__192.168.1.8:5555"));
        assertNull(DeviceSettingVariant.ofJobKey(null));
    }

    /** 设备 id 恰好长得像设置名时也不能误判：基础作业只有一个分隔符 */
    @Test
    void 设备id和设置同名也不误判() {
        assertNull(DeviceSettingVariant.ofJobKey("DOMESTIC_ESPORTS__DARK_MODE"));
    }

    @Test
    void 变体作业能认出设置() {
        String key = DeviceSettingVariant.DARK_MODE.jobKeyOf("DOMESTIC_ESPORTS__emulator-5554");
        assertEquals("DOMESTIC_ESPORTS__emulator-5554__DARK_MODE", key);
        assertEquals(DeviceSettingVariant.DARK_MODE, DeviceSettingVariant.ofJobKey(key));
        assertEquals(DeviceSettingVariant.LARGE_FONT,
                DeviceSettingVariant.ofJobKey("A__d1__LARGE_FONT"));
    }

    @Test
    void 解析大小写不敏感_不认识的返回null() {
        assertEquals(DeviceSettingVariant.LARGE_FONT, DeviceSettingVariant.parse(" large_font "));
        assertNull(DeviceSettingVariant.parse("LANDSCAPE"));
        assertNull(DeviceSettingVariant.parse(""));
    }
}
