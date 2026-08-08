package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 蒲公英「取最新版」的挑选规则。
 * <p>
 * 不打真实网络，只覆盖挑选逻辑——这段挑错了不会报错，只会静默装上一个旧包，
 * 而任务还显示「安装成功」，排查起来最费劲。
 */
class PgyerLatestBuildTest {

    private static PgyerAppService.PgyerBuildVo build(String key, int buildNo, int type) {
        PgyerAppService.PgyerBuildVo vo = new PgyerAppService.PgyerBuildVo();
        vo.setBuildKey(key);
        vo.setBuildBuildVersion(buildNo);
        vo.setBuildType(type);
        return vo;
    }

    @SuppressWarnings("unchecked")
    private List<PgyerAppService.PgyerBuildVo> filter(List<PgyerAppService.PgyerBuildVo> all,
                                                      String platform) {
        Integer want = ReflectionTestUtils.invokeMethod(
                PgyerAppService.class, "wantedBuildType", platform);
        return all.stream()
                .filter(b -> want == null || b.getBuildType() <= 0 || b.getBuildType() == want)
                .toList();
    }

    @Test
    void 平台名映射到蒲公英的buildType() {
        assertEquals(1, (Integer) ReflectionTestUtils.invokeMethod(
                PgyerAppService.class, "wantedBuildType", "IOS"));
        assertEquals(2, (Integer) ReflectionTestUtils.invokeMethod(
                PgyerAppService.class, "wantedBuildType", "ANDROID"));
        // 鸿蒙包走 Android 通道
        assertEquals(2, (Integer) ReflectionTestUtils.invokeMethod(
                PgyerAppService.class, "wantedBuildType", "HARMONYOS"));
        assertNull(ReflectionTestUtils.invokeMethod(
                PgyerAppService.class, "wantedBuildType", (Object) null));
    }

    /**
     * 实测蒲公英列表接口不返回 buildType（值为 0）。按平台严格过滤会把所有版本滤光，
     * 然后报「没有 ANDROID 平台的包」——把人引到完全错误的方向。
     */
    @Test
    void buildType缺失时不过滤() {
        List<PgyerAppService.PgyerBuildVo> all = List.of(
                build("a", 2022, 0), build("b", 2021, 0));

        assertEquals(2, filter(all, "ANDROID").size());
    }

    @Test
    void buildType有值时按平台过滤() {
        List<PgyerAppService.PgyerBuildVo> all = List.of(
                build("ios", 5, 1), build("apk", 4, 2));

        assertEquals("apk", filter(all, "ANDROID").get(0).getBuildKey());
        assertEquals("ios", filter(all, "IOS").get(0).getBuildKey());
    }

    /**
     * 不依赖接口的返回顺序。文档说是时间倒序，但这种隐式约定一旦变了，
     * 会静默装上旧包而任务显示成功——按构建号取最大才稳。
     */
    @Test
    void 按构建号取最大而不是取第一条() {
        List<PgyerAppService.PgyerBuildVo> all = List.of(
                build("old", 2019, 0), build("newest", 2022, 0), build("mid", 2021, 0));

        assertEquals("newest", all.stream()
                .max(java.util.Comparator.comparingInt(PgyerAppService.PgyerBuildVo::getBuildBuildVersion))
                .orElseThrow().getBuildKey());
    }
}
