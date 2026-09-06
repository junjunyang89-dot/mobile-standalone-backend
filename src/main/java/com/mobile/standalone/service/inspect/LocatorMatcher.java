package com.mobile.standalone.service.inspect;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在当前控件树上校验 locator 命中几个元素、分别是谁。
 *
 * <p>「这个 xpath 匹配到 3 个元素」比「我猜这个 locator 最好」有用得多：
 * 与其继续加启发式规则去猜，不如把唯一性直接摆给人看。</p>
 *
 * <p>更进一步，光知道「命中 3 个」还不够，必须知道<b>第 1 个是谁</b>——
 * Appium 的 findElement 拿的就是文档序第一个。定位命中 3 个、而你要的那个排在第 2，
 * 现象是「元素找得到、点击也成功、界面毫无反应」，这是最难查的一类问题，
 * 因为每一步都报绿。</p>
 */
@Slf4j
public final class LocatorMatcher {

    /** 无法校验（locator 类型不支持 / xpath 语法错误 / 树不可用） */
    public static final int UNKNOWN = -1;

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    private LocatorMatcher() {
    }

    /**
     * 命中的是哪些节点，按文档序（也就是 Appium findElement 的取用顺序）。
     *
     * @return 命中列表；空列表＝一个都没命中；{@code null}＝无法校验，调用方不该把它当成 0
     */
    public static List<Hit> matches(Document doc, String locatorType, String locatorValue) {
        if (doc == null || locatorValue == null || locatorValue.isBlank()) return null;
        String expr = toXPath(locatorType, locatorValue.trim());
        if (expr == null) return null;
        try {
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList list = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
            List<Hit> out = new ArrayList<>();
            if (list == null) return out;
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                if (node instanceof Element el) {
                    out.add(hitOf(el));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("locator 校验失败 type={} value={}: {}", locatorType, locatorValue, e.getMessage());
            return null;
        }
    }

    /**
     * @return 命中数量；{@link #UNKNOWN} 表示无法校验
     */
    public static int count(Document doc, String locatorType, String locatorValue) {
        List<Hit> hits = matches(doc, locatorType, locatorValue);
        return hits == null ? UNKNOWN : hits.size();
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

    private static Hit hitOf(Element el) {
        int[] b = boundsOf(el);
        return new Hit(
                b != null ? b[0] : Integer.MIN_VALUE,
                b != null ? b[1] : Integer.MIN_VALUE,
                b != null ? b[2] : Integer.MIN_VALUE,
                b != null ? b[3] : Integer.MIN_VALUE,
                firstAttr(el, "resource-id", "resourceId", "id", "name"),
                firstAttr(el, "class", "type"),
                b != null);
    }

    /** Android 是 bounds="[l,t][r,b]"，iOS/鸿蒙是 x/y/width/height，两种都收 */
    private static int[] boundsOf(Element el) {
        String raw = el.getAttribute("bounds");
        if (raw != null && !raw.isBlank()) {
            Matcher m = BOUNDS.matcher(raw);
            if (m.find()) {
                return new int[]{
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
            }
        }
        Integer x = intAttr(el, "x");
        Integer y = intAttr(el, "y");
        Integer w = intAttr(el, "width");
        Integer h = intAttr(el, "height");
        if (x != null && y != null && w != null && h != null) {
            return new int[]{x, y, x + w, y + h};
        }
        return null;
    }

    private static Integer intAttr(Element el, String name) {
        String v = el.getAttribute(name);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstAttr(Element el, String... names) {
        for (String n : names) {
            String v = el.getAttribute(n);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /**
     * 一次命中：够用来回答「这是不是我点的那个节点」。
     *
     * @param hasBounds 树上没有坐标时为 false——此时不能拿坐标去判定同一性，
     *                  只能说「无法确认」，绝不能反过来断言「不是同一个」
     */
    public record Hit(int left, int top, int right, int bottom,
                      String resourceId, String className, boolean hasBounds) {

        /**
         * 与某个已知节点是不是同一个。
         * <p>
         * 用「坐标 + resource-id」判同：同一屏上坐标完全相同、id 也相同的两个节点，
         * 只可能是父子同框且同 id，实际不存在。比拿 className 判可靠得多——
         * 复用 id 的那批节点 class 往往也一样。
         */
        public boolean isSameNodeAs(int l, int t, int r, int b, String rid) {
            if (!hasBounds) return false;
            if (left != l || top != t || right != r || bottom != b) return false;
            if (resourceId == null || rid == null) return true;
            return resourceId.equals(rid.trim());
        }

        public String describe() {
            String id = resourceId == null ? "(无 id)" : shortId(resourceId);
            return hasBounds
                    ? id + " [" + left + "," + top + "][" + right + "," + bottom + "]"
                    : id;
        }

        private static String shortId(String rid) {
            int i = rid.lastIndexOf('/');
            return i >= 0 && i < rid.length() - 1 ? rid.substring(i + 1) : rid;
        }
    }
}
