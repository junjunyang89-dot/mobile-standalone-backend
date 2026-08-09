package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * iOS 设备体检里的纯解析与结论。
 * <p>
 * 这组的立场：<b>结论必须能驱动动作</b>。
 * 「设备未就绪」这种话等于没说；要说清楚是哪一步、去手机上点哪里。
 */
class IosDeviceReadinessTest {

    private static final String INFO = """
            DeviceName: yangjun 的 iPhone
            ProductType: iPhone10,4
            ProductVersion: 16.7.2
            UniqueDeviceID: 00008030-001A2B3C
            """;

    @Test
    void 从ideviceinfo里取值() {
        assertEquals("yangjun 的 iPhone", IosDeviceReadiness.valueOf(INFO, "DeviceName"));
        assertEquals("16.7.2", IosDeviceReadiness.valueOf(INFO, "ProductVersion"));
    }

    /** 前缀相同的 key 不能误命中：ProductType 不是 ProductVersion */
    @Test
    void 前缀相同的key不误命中() {
        assertEquals("iPhone10,4", IosDeviceReadiness.valueOf(INFO, "ProductType"));
    }

    @Test
    void 取不到返回null不抛() {
        assertEquals(null, IosDeviceReadiness.valueOf(INFO, "NotThere"));
        assertEquals(null, IosDeviceReadiness.valueOf(null, "DeviceName"));
    }

    @Test
    void 主版本号解析() {
        assertEquals(16, IosDeviceReadiness.majorOf("16.7.2"));
        assertEquals(17, IosDeviceReadiness.majorOf("17.0"));
    }

    /** 解析不出来返回 0，让调用方跳过版本相关判断，而不是当成 iOS 0 走进错误分支 */
    @Test
    void 版本号异常返回零() {
        assertEquals(0, IosDeviceReadiness.majorOf(null));
        assertEquals(0, IosDeviceReadiness.majorOf(""));
        assertEquals(0, IosDeviceReadiness.majorOf("未知"));
    }

    // ==================== 结论 ====================

    private static IosDeviceReadiness.Check fail(String name, String fix) {
        IosDeviceReadiness.Check c = new IosDeviceReadiness.Check();
        c.setStatus("FAIL");
        c.setName(name);
        c.setHowToFix(fix);
        return c;
    }

    @Test
    void 全过时说可以直接跑() {
        assertTrue(IosDeviceReadiness.summarize(List.of()).contains("可以直接跑"));
    }

    /**
     * 本组核心。这几步是有先后的——没配对就查不了开发者模式，
     * 一次列四条会让人不知道从哪下手，所以只报第一条。
     */
    @Test
    void 多项没过时只报第一条() {
        String s = IosDeviceReadiness.summarize(List.of(
                fail("设备已配对信任", "连数据线点信任"),
                fail("开发者模式已开启", "设置 → 隐私与安全性")));

        assertTrue(s.contains("还差 2 项"), s);
        assertTrue(s.contains("设备已配对信任"), "先报最前面那一步：" + s);
        assertTrue(!s.contains("隐私与安全性"), "第二条不该一起塞出来：" + s);
    }

    /** 结论里必须带上「怎么做」，只说名字等于没说 */
    @Test
    void 结论里带上怎么做() {
        assertTrue(IosDeviceReadiness.summarize(List.of(fail("开发者模式已开启", "设置 → 隐私与安全性")))
                .contains("设置 → 隐私与安全性"));
    }
}
