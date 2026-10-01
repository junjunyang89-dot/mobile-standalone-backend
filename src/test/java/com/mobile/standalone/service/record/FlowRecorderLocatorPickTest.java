package com.mobile.standalone.service.record;

import com.mobile.standalone.service.DeviceInspectService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制挑定位：为什么「优先选唯一命中的那个」是错的。
 *
 * <p>背景是一次真实的回放失败：录出来的流程卡在第 8 步，
 * 定位是 {@code xpath://android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']}，
 * 现场一个节点都没命中。
 *
 * <p>原因不是候选算错了，而是<b>挑的时候用错了标准</b>：
 * bounds 精确到像素，必然只命中一个节点，于是「唯一优先」这条规则
 * 会稳定地把全场最差的定位挑出来。而录制时的点击本来就走坐标，
 * 所以录的当场完全看不出问题——要等到回放才炸。
 */
class FlowRecorderLocatorPickTest {

    private static DeviceInspectService.HitCandidate c(String type, String value, Integer matchCount) {
        DeviceInspectService.HitCandidate h = new DeviceInspectService.HitCandidate();
        h.setLocatorType(type);
        h.setLocatorValue(value);
        h.setMatchCount(matchCount);
        return h;
    }

    private static DeviceInspectService.HitTestResult hit(DeviceInspectService.HitCandidate... cs) {
        DeviceInspectService.HitTestResult r = new DeviceInspectService.HitTestResult();
        List<DeviceInspectService.HitCandidate> list = new ArrayList<>(List.of(cs));
        for (int i = 0; i < list.size(); i++) list.get(i).setRank(i);
        r.setCandidates(list);
        return r;
    }

    // ---------- 分档 ----------

    @Test
    void bounds_xpath_跟坐标同档() {
        assertEquals(FlowRecorder.TIER_POSITIONAL,
                FlowRecorder.locatorTier("xpath",
                        "//android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']"),
                "@bounds 的 xpath 锁的是像素方框，跟坐标是一回事");
        assertEquals(FlowRecorder.TIER_POSITIONAL,
                FlowRecorder.locatorTier("xpath", "//*[@bounds='[0,0][1080,100]']"));
        assertEquals(FlowRecorder.TIER_POSITIONAL, FlowRecorder.locatorTier("coordinate", "540,1600"));
    }

    @Test
    void 绝对下标路径也是位置定位() {
        assertEquals(FlowRecorder.TIER_POSITIONAL,
                FlowRecorder.locatorTier("xpath",
                        "/hierarchy/android.widget.FrameLayout[1]/android.widget.LinearLayout[2]"));
        assertEquals(FlowRecorder.TIER_POSITIONAL,
                FlowRecorder.locatorTier("xpath", "//a[1]/b[2]/c[3]"));
    }

    @Test
    void 好定位排在前面() {
        assertTrue(FlowRecorder.locatorTier("id", "com.pwrd.x:id/btn_login")
                < FlowRecorder.locatorTier("accessibilityId", "登录"));
        assertTrue(FlowRecorder.locatorTier("accessibilityId", "登录")
                < FlowRecorder.locatorTier("text", "登录"));
        assertTrue(FlowRecorder.locatorTier("text", "登录")
                < FlowRecorder.locatorTier("className", "android.widget.Button"));
        assertTrue(FlowRecorder.locatorTier("className", "android.widget.Button")
                < FlowRecorder.TIER_POSITIONAL);
    }

    @Test
    void 带条件的xpath比裸下标好() {
        assertTrue(FlowRecorder.locatorTier("xpath", "//*[@resource-id='a']//*[@text='b']")
                < FlowRecorder.locatorTier("xpath", "//android.widget.Button[2]"));
    }

    @Test
    void 空定位不可用() {
        assertEquals(99, FlowRecorder.locatorTier("id", null));
        assertEquals(99, FlowRecorder.locatorTier("id", "  "));
        assertEquals(99, FlowRecorder.locatorTier(null, "x"));
    }

    // ---------- 挑选 ----------

    /** 就是线上那一次：唯一的 bounds 把命中 3 个节点的 id 顶掉了 */
    @Test
    void 唯一的bounds不能顶掉命中多个的id() {
        DeviceInspectService.HitCandidate picked = FlowRecorder.pickCandidate(hit(
                c("id", "com.pwrd.x:id/item_title", 3),
                c("xpath", "//android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']", 1)));
        assertEquals("com.pwrd.x:id/item_title", picked.getLocatorValue(),
                "唯一性只能在同档里比。像素方框再唯一也不是元素定位");
    }

    @Test
    void 同档里唯一的排前面() {
        DeviceInspectService.HitCandidate picked = FlowRecorder.pickCandidate(hit(
                c("id", "com.pwrd.x:id/row", 5),
                c("id", "com.pwrd.x:id/btn_ok", 1)));
        assertEquals("com.pwrd.x:id/btn_ok", picked.getLocatorValue());
    }

    @Test
    void 同档同唯一性时按推荐序() {
        DeviceInspectService.HitCandidate picked = FlowRecorder.pickCandidate(hit(
                c("id", "first", 1),
                c("id", "second", 1)));
        assertEquals("first", picked.getLocatorValue());
    }

    @Test
    void 唯一性翻不过一整档() {
        // text 唯一 vs className 唯一：档位说了算
        DeviceInspectService.HitCandidate picked = FlowRecorder.pickCandidate(hit(
                c("className", "android.widget.TextView", 1),
                c("text", "登录", 1)));
        assertEquals("登录", picked.getLocatorValue());
    }

    /** 一个像样的都没有 → 返回 null，调用方退回明写的坐标点击 */
    @Test
    void 只剩位置定位时挑不出来() {
        assertNull(FlowRecorder.pickCandidate(hit(
                c("xpath", "//android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']", 1),
                c("coordinate", "540,1600", null))));
    }

    @Test
    void 空候选和空结果都不炸() {
        assertNull(FlowRecorder.pickCandidate(null));
        assertNull(FlowRecorder.pickCandidate(hit()));
        assertNull(FlowRecorder.pickCandidate(hit(c("id", "  ", 1), c(null, "x", 1))));
    }

    @Test
    void matchCount未知时不当成命中多个() {
        DeviceInspectService.HitCandidate picked = FlowRecorder.pickCandidate(hit(
                c("id", "unknown_count", null),
                c("id", "multi", 4)));
        assertEquals("unknown_count", picked.getLocatorValue(),
                "拿不到 matchCount 时按乐观算，不该被明确命中多个的那条挤掉");
    }

    @Test
    void 下标段计数() {
        assertEquals(0, FlowRecorder.countIndexSegments("//*[@text='a']"));
        assertEquals(1, FlowRecorder.countIndexSegments("//a[1]"));
        assertEquals(3, FlowRecorder.countIndexSegments("//a[1]/b[ 2 ]/c[33]"));
    }
}
