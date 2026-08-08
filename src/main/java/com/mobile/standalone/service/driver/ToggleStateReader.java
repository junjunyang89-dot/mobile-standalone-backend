package com.mobile.standalone.service.driver;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读勾选/开关状态。自定义 {@code ImageView} 上 WebDriver {@code getAttribute("checked")}
 * 常恒为 false 或空串，需再从 page source 的 {@code checked=} 补读一遍。
 */
public final class ToggleStateReader {

    private static final List<String> TOGGLE_ATTRS = List.of("checked", "selected", "value");

    private ToggleStateReader() {
    }

    /**
     * 解析 checked/selected/value 等属性值。
     * 空串视为「读不到」，不能当成 false——否则 ImageView 会永远被判成未勾选。
     */
    public static Boolean parseToggleValue(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)
                || "checked".equals(v) || "on".equals(v)) {
            return true;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) {
            return false;
        }
        return null;
    }

    /** 依次读 checked / selected / value，任一能解析即返回 */
    public static Boolean readFromAttributes(PlatformDriver driver, String deviceId, String selector) {
        if (driver == null || deviceId == null || selector == null) {
            return null;
        }
        for (String attr : TOGGLE_ATTRS) {
            String raw = driver.getElementAttribute(deviceId, selector, attr);
            Boolean parsed = parseToggleValue(raw);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * 合并 WebDriver 与 page source 的勾选态（供 Appium 层使用，不影响点击步骤）。
     */
    public static Boolean mergeElementAndPageSource(Boolean fromElement, Boolean fromPageSource) {
        if (Boolean.TRUE.equals(fromElement) || Boolean.TRUE.equals(fromPageSource)) {
            return true;
        }
        if (fromPageSource != null) {
            return fromPageSource;
        }
        return fromElement;
    }

    /**
     * 从 page source 节点推断勾选态（勾号 / 空圈在无障碍树里通常落在 checked 或 content-desc）。
     */
    public static Boolean inferCheckedFromNode(String node) {
        if (node == null || node.isBlank()) {
            return null;
        }
        for (String attr : List.of("checked", "selected")) {
            Boolean parsed = parseToggleValue(extractXmlAttribute(node, attr));
            if (parsed != null) {
                return parsed;
            }
        }
        String desc = extractXmlAttribute(node, "content-desc");
        return inferCheckedFromDescription(desc);
    }

    /**
     * 从 content-desc / 文案推断（部分 App 勾选后会在描述里带「已选」等字样）。
     * <p>
     * 注意：不能用「已同意」——协议勾选旁固定文案是「我已阅读并同意…」，未勾选时也会出现。
     */
    public static Boolean inferCheckedFromDescription(String desc) {
        if (desc == null || desc.isBlank()) {
            return null;
        }
        String d = desc.trim().toLowerCase(Locale.ROOT);
        // 协议勾选旁固定文案，不能当勾选态
        if (d.contains("我已阅读并同意") || d.contains("用户协议") || d.contains("个人信息保护")) {
            return null;
        }
        if (d.contains("已勾选") || d.contains("已选中") || d.contains("勾选状态:已选")
                || d.equals("checked") || d.equals("selected")) {
            return true;
        }
        if (d.contains("未勾选") || d.contains("未选中") || d.contains("unchecked")
                || d.equals("unchecked") || d.equals("unselected")) {
            return false;
        }
        return null;
    }

    /**
     * 只读 XML 的 checked/selected，不读 content-desc（避免协议条文案误判）。
     * <p>
     * <b>false 只在有证据时才采信。</b>Android 控件树里每个节点都带 {@code checked="false"}
     * {@code selected="false"}，这是默认值、不是「未勾选」。只有 {@code checkable="true"} 才说明
     * 这个控件真的实现了勾选语义，它的 false 才有意义。
     * <p>
     * 现实里的坑：某 APP 的协议勾选是 {@code ImageView checkable="false" checked="false"}，
     * 蓝色勾只是换了张图。把这个 false 当「未勾选」，断言就会去点一下「纠正」，
     * 结果把上一步刚勾好的取消掉——而日志显示每一步都成功。
     */
    public static Boolean readCheckedAttrFromPageSource(String pageSource, String selector) {
        String node = findNode(pageSource, selector);
        if (node == null || node.isBlank()) {
            return null;
        }
        boolean checkable = Boolean.TRUE.equals(parseToggleValue(extractXmlAttribute(node, "checkable")));
        for (String attr : List.of("checked", "selected")) {
            Boolean parsed = parseToggleValue(extractXmlAttribute(node, attr));
            if (Boolean.TRUE.equals(parsed)) {
                return true;
            }
            if (Boolean.FALSE.equals(parsed) && checkable) {
                return false;
            }
        }
        // 读不到状态，交给上层决定是看图还是判失败——绝不能当成「未勾选」
        return null;
    }

    /**
     * 从 Android page source 里按 resource-id 找节点，再读 checked/selected/content-desc。
     */
    public static Boolean readCheckedFromPageSource(String pageSource, String selector) {
        String node = findNode(pageSource, selector);
        return inferCheckedFromNode(node);
    }

    /** 读元素 enabled 属性（用于间接断言，如发送验证码是否点亮） */
    public static Boolean readEnabledFromPageSource(String pageSource, String selector) {
        String node = findNode(pageSource, selector);
        if (node == null) {
            return null;
        }
        return parseToggleValue(extractXmlAttribute(node, "enabled"));
    }

    private static String findNode(String pageSource, String selector) {
        if (pageSource == null || pageSource.isBlank() || selector == null) {
            return null;
        }
        String resourceId = extractResourceIdFromSelector(selector);
        if (resourceId == null) {
            return null;
        }
        return findNodeByResourceId(pageSource, resourceId);
    }

    static String extractResourceIdFromSelector(String selector) {
        if (selector == null || selector.isBlank()) {
            return null;
        }
        String s = selector.trim();
        int colon = s.indexOf(':');
        if (colon > 0) {
            String type = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = s.substring(colon + 1).trim();
            if ("id".equals(type) || "resource-id".equals(type)) {
                return value;
            }
            if ("xpath".equals(type)) {
                Matcher m = Pattern.compile("@resource-id=['\"]([^'\"]+)['\"]")
                        .matcher(value);
                if (m.find()) {
                    return m.group(1);
                }
                // 测试桩或简写：xpath:login_policy_checkbox
                return value;
            }
            return null;
        }
        return s;
    }

    static String findNodeByResourceId(String pageSource, String resourceId) {
        String esc = Pattern.quote(resourceId);
        Pattern full = Pattern.compile("<[^>]+resource-id=\"" + esc + "\"[^>]*/?>", Pattern.CASE_INSENSITIVE);
        Matcher m = full.matcher(pageSource);
        if (m.find()) {
            return m.group();
        }
        String shortId = resourceId.contains("/")
                ? resourceId.substring(resourceId.lastIndexOf('/') + 1)
                : resourceId;
        if (shortId.equals(resourceId)) {
            return null;
        }
        Pattern shortPat = Pattern.compile(
                "<[^>]+resource-id=\"[^\"]*/?" + Pattern.quote(shortId) + "\"[^>]*/?>",
                Pattern.CASE_INSENSITIVE);
        m = shortPat.matcher(pageSource);
        return m.find() ? m.group() : null;
    }

    static String extractXmlAttribute(String node, String attribute) {
        if (node == null || attribute == null) {
            return null;
        }
        Matcher m = Pattern.compile(attribute + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(node);
        return m.find() ? m.group(1) : null;
    }
}
