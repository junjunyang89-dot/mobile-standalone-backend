package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台名归一。
 * <p>
 * POG_ANDROID / POG_IOS 把「操作系统」和「产品线」混在一个维度里，而 appId 已经能区分
 * 国内/海外——平台再分一次导致同一个元素要按平台各存一份。归一之后回到操作系统维度，
 * 存量数据靠别名回退继续工作。
 */
class PlatformNamesTest {

    @Test
    void 历史产品线平台归一到操作系统() {
        assertEquals("ANDROID", PlatformNames.normalize("POG_ANDROID"));
        assertEquals("ANDROID", PlatformNames.normalize("POGPOG"));
        assertEquals("IOS", PlatformNames.normalize("POG_IOS"));
    }

    @Test
    void 标准平台原样返回() {
        assertEquals("ANDROID", PlatformNames.normalize("ANDROID"));
        assertEquals("IOS", PlatformNames.normalize("IOS"));
        assertEquals("HARMONYOS", PlatformNames.normalize("HARMONYOS"));
    }

    @Test
    void 大小写与空格不敏感() {
        assertEquals("ANDROID", PlatformNames.normalize("  pog_android  "));
        assertEquals("IOS", PlatformNames.normalize("Pog_Ios"));
    }

    @Test
    void 空值返回null() {
        assertNull(PlatformNames.normalize(null));
        assertNull(PlatformNames.normalize("   "));
    }

    /** 没见过的平台不能被吞掉，否则将来新增平台会静默走错分支 */
    @Test
    void 未知平台原样返回不吞掉() {
        assertEquals("WEAROS", PlatformNames.normalize("wearos"));
    }

    @Test
    void 能识别历史取值() {
        assertTrue(PlatformNames.isLegacy("POG_ANDROID"));
        assertTrue(PlatformNames.isLegacy("POG_IOS"));
        assertTrue(PlatformNames.isLegacy("POGPOG"));
        assertFalse(PlatformNames.isLegacy("ANDROID"));
        assertFalse(PlatformNames.isLegacy(null));
    }

    /**
     * 回退别名是迁移期间不出事的关键：
     * 存量元素还存在 POG_ANDROID 下，代码改成按 ANDROID 查之后要能再找一次。
     */
    @Test
    void 归一后能取到对应的历史别名() {
        assertEquals(List.of("POG_ANDROID", "POGPOG"), PlatformNames.legacyAliasesOf("ANDROID"));
        assertEquals(List.of("POG_IOS"), PlatformNames.legacyAliasesOf("IOS"));
    }

    @Test
    void 无历史别名的平台返回空列表() {
        assertTrue(PlatformNames.legacyAliasesOf("HARMONYOS").isEmpty());
        assertTrue(PlatformNames.legacyAliasesOf(null).isEmpty());
        assertTrue(PlatformNames.legacyAliasesOf("WEAROS").isEmpty());
    }

    @Test
    void 平台判断覆盖历史取值() {
        assertTrue(PlatformNames.isIos("POG_IOS"));
        assertTrue(PlatformNames.isIos("IOS"));
        assertFalse(PlatformNames.isIos("ANDROID"));

        assertTrue(PlatformNames.isAndroid("POG_ANDROID"));
        assertTrue(PlatformNames.isAndroid("POGPOG"));
        assertFalse(PlatformNames.isAndroid("HARMONYOS"));
    }
}
