package com.mobile.standalone.service.inspect;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.util.Locale;

/**
 * 在当前控件树上校验 locator 命中几个元素。
 *
 * <p>「这个 xpath 匹配到 3 个元素」比「我猜这个 locator 最好」有用得多：
 * 与其继续加启发式规则去猜，不如把唯一性直接摆给人看。</p>
 */
@Slf4j
public final class LocatorMatcher {

    /** 无法校验（locator 类型不支持 / xpath 语法错误 / 树不可用） */
    public static final int UNKNOWN = -1;

    private LocatorMatcher() {
    }

    /**
     * @return 命中数量；{@link #UNKNOWN} 表示无法校验
     */
    public static int count(Document doc, String locatorType, String locatorValue) {
        if (doc == null || locatorValue == null || locatorValue.isBlank()) return UNKNOWN;
        String expr = toXPath(locatorType, locatorValue.trim());
        if (expr == null) return UNKNOWN;
        try {
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList list = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
            return list == null ? 0 : list.getLength();
        } catch (Exception e) {
            log.debug("locator 唯一性校验失败 type={} value={}: {}", locatorType, locatorValue, e.getMessage());
            return UNKNOWN;
        }
    }

    /** 把各类 locator 翻译成可在 page source 上求值的 xpath；不支持的返回 null */
    public static String toXPath(String locatorType, String value) {
        String t = locatorType == null ? "" : locatorType.trim().toLowerCase(Locale.ROOT);
        String lit = literal(value);
        switch (t) {
            case "xpath":
                return value;
            case "id":
            case "resourceid":
            case "resource-id":
                return "//*[@resource-id=" + lit + " or @resourceId=" + lit + " or @id=" + lit + "]";
            case "text":
                return "//*[@text=" + lit + " or @label=" + lit + " or @value=" + lit + "]";
            case "accessibilityid":
            case "accessibility-id":
            case "contentdesc":
            case "content-desc":
                return "//*[@content-desc=" + lit + " or @name=" + lit + "]";
            case "classname":
            case "class":
                return "//*[@class=" + lit + " or name()=" + lit + "]";
            case "name":
                return "//*[@name=" + lit + " or @text=" + lit + " or @label=" + lit + "]";
            default:
                // coordinate 等非元素定位方式：无从校验
                return null;
        }
    }

    /** XPath 1.0 没有转义，含单双引号时只能 concat 拼 */
    public static String literal(String v) {
        if (v == null) return "''";
        if (!v.contains("'")) return "'" + v + "'";
        if (!v.contains("\"")) return "\"" + v + "\"";
        StringBuilder sb = new StringBuilder("concat(");
        String[] parts = v.split("'", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append("'").append(parts[i]).append("'");
        }
        return sb.append(")").toString();
    }
}
