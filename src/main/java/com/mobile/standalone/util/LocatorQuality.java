package com.mobile.standalone.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条定位有多靠得住。
 *
 * <p><b>为什么要单独拿出来</b>：这套分档原来长在 {@code FlowRecorder} 里，只有录制在用。
 * 现在元素体检也要判断「这条定位值不值得推荐」——如果各写一份，两份迟早会漂移，
 * 而漂移的表现是：录制挑出来的定位，体检说它不好；或者反过来。
 * 那时候人不知道该信哪个，两边都会被当成不准。
 *
 * <p><b>「好定位」的定义只该有一处。</b>
 */
public final class LocatorQuality {

    private LocatorQuality() {
    }

    /** 靠位置而不是靠身份的定位：坐标、{@code @bounds} 的 xpath、绝对下标路径。一律不当元素用 */
    public static final int TIER_POSITIONAL = 90;
    /** 到这一档就该提醒人了：下标 xpath、裸 className——能跑，但界面一改就废 */
    public static final int TIER_SHAKY = 6;
    /** 压根算不上定位 */
    public static final int TIER_UNUSABLE = 99;

    private static final Pattern XPATH_INDEX = Pattern.compile("\\[\\s*\\d+\\s*]");

    /**
     * 给一个定位打质量档，数字越小越稳。
     *
     * <p>最要紧的一条：<b>{@code @bounds} 的 xpath 和坐标是一回事</b>。
     * {@code //android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']} 写起来像元素定位，
     * 锁的却是屏幕上那个像素方框——多一行内容、换台分辨率不同的机器、甚至只是列表滚了一下，
     * 就再也匹配不到任何节点。
     *
     * <p>这里有个很容易反着看的陷阱，录制那边栽过一次：bounds 精确到像素，
     * 所以它<b>必然只命中一个节点</b>。于是「优先选唯一命中的那个」这条听着完全正确的规则，
     * 会稳定地把全场最差的定位挑出来。
     *
     * <p>结论：<b>唯一性只能在同一档内部比较，不能跨档</b>。一个命中 3 个节点的 id，
     * 也好过一个唯一命中的像素方框。
     */
    public static int tier(String type, String value) {
        if (value == null || value.isBlank()) {
            return TIER_UNUSABLE;
        }
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return TIER_UNUSABLE;
        }
        String v = value.trim();
        if ("coordinate".equals(t) || "coord".equals(t) || "tap".equals(t) || "point".equals(t)) {
            return TIER_POSITIONAL;
        }
        if ("xpath".equals(t)) {
            if (v.contains("@bounds")) {
                return TIER_POSITIONAL;
            }
            // /hierarchy/FrameLayout[1]/LinearLayout[3]/... 说的也是「第几个格子」，不是「哪个控件」
            if (v.startsWith("/hierarchy") || countIndexSegments(v) >= 3) {
                return TIER_POSITIONAL;
            }
            if (v.contains("@resource-id") || v.contains("@content-desc")) {
                return 3;
            }
            if (v.contains("@text")) {
                return 4;
            }
            if (countIndexSegments(v) >= 1) {
                return TIER_SHAKY;
            }
            return 5;
        }
        return switch (t) {
            case "id", "resource-id", "resourceid" -> 1;
            case "accessibilityid", "accessibility-id", "accessibility id", "desc", "description" -> 2;
            case "text", "name" -> 4;
            case "classname", "class", "type" -> 7;
            default -> 5;
        };
    }

    public static int countIndexSegments(String xpath) {
        if (xpath == null) {
            return 0;
        }
        Matcher m = XPATH_INDEX.matcher(xpath);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** 靠位置不靠身份，不该当元素存进库 */
    public static boolean isPositional(String type, String value) {
        return tier(type, value) >= TIER_POSITIONAL;
    }

    /** 能跑但界面一改就废，值得提醒人 */
    public static boolean isShaky(String type, String value) {
        int t = tier(type, value);
        return t >= TIER_SHAKY && t < TIER_POSITIONAL;
    }
}
