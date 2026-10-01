package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「什么算好定位」只该有一处定义。
 *
 * <p>这套分档原来长在 FlowRecorder 里，现在录制和元素体检共用。
 * 两边共用之后，这组测试就是那个共同约定——尤其是 {@code @bounds} 那一条，
 * 它被反着用过一次，代价是整条流程一步都点不中。
 */
class LocatorQualityTest {

    @Test
    void id最稳() {
        assertEquals(1, LocatorQuality.tier("id", "com.x:id/btn"));
        assertTrue(LocatorQuality.tier("id", "com.x:id/btn")
                < LocatorQuality.tier("text", "关注"));
    }

    /**
     * {@code @bounds} 的 xpath 和坐标是一回事。
     *
     * <p>它写起来像元素定位，锁的却是像素方框。而且因为精确到像素，它<b>必然唯一命中</b>——
     * 所以「优先选唯一命中的」这条听着完全正确的规则会稳定地把全场最差的挑出来。
     */
    @Test
    void bounds的xpath等同坐标() {
        assertEquals(LocatorQuality.TIER_POSITIONAL,
                LocatorQuality.tier("xpath", "//android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']"));
        assertEquals(LocatorQuality.TIER_POSITIONAL, LocatorQuality.tier("coordinate", "540,1663"));
        assertTrue(LocatorQuality.isPositional("xpath", "//*[@bounds='[0,0][10,10]']"));
    }

    /** 绝对下标路径说的是「第几个格子」，不是「哪个控件」 */
    @Test
    void 绝对下标路径也算靠位置() {
        assertEquals(LocatorQuality.TIER_POSITIONAL,
                LocatorQuality.tier("xpath", "/hierarchy/android.widget.FrameLayout[1]/android.widget.LinearLayout[3]"));
        assertEquals(LocatorQuality.TIER_POSITIONAL,
                LocatorQuality.tier("xpath", "//a[1]/b[2]/c[3]"));
    }

    /** 命中多个的 id 也好过唯一命中的像素方框——唯一性不跨档比 */
    @Test
    void 再唯一也翻不过一整档() {
        int idTier = LocatorQuality.tier("id", "com.x:id/icon");
        int boundsTier = LocatorQuality.tier("xpath", "//*[@bounds='[0,0][10,10]']");
        assertTrue(idTier * 100 + 40 < boundsTier * 100,
                "命中多个的 id 排到了像素方框后面");
    }

    @Test
    void 下标xpath和裸class要提醒人() {
        assertTrue(LocatorQuality.isShaky("xpath", "//android.widget.Button[2]"));
        assertTrue(LocatorQuality.isShaky("className", "android.widget.TextView"));
        assertTrue(!LocatorQuality.isShaky("id", "com.x:id/btn"));
    }

    @Test
    void 空值不可用() {
        assertEquals(LocatorQuality.TIER_UNUSABLE, LocatorQuality.tier("id", null));
        assertEquals(LocatorQuality.TIER_UNUSABLE, LocatorQuality.tier("id", "  "));
        assertEquals(LocatorQuality.TIER_UNUSABLE, LocatorQuality.tier(null, "x"));
    }

    @Test
    void 数下标段数() {
        assertEquals(0, LocatorQuality.countIndexSegments("//*[@text='a']"));
        assertEquals(2, LocatorQuality.countIndexSegments("//a[1]/b[2]"));
        assertEquals(0, LocatorQuality.countIndexSegments(null));
    }
}
