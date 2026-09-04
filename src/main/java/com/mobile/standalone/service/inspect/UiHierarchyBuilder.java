package com.mobile.standalone.service.inspect;

import lombok.Data;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 page source 解析成完整控件树（Appium Inspector 式），整棵交给前端渲染 + 画框。
 *
 * <p>这里刻意<b>不做任何「猜哪个节点更合适」的判断</b>：挑哪个节点是人的事，
 * DeviceInspectService 的启发式只负责给一个默认推荐。</p>
 */
public final class UiHierarchyBuilder {

    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private UiHierarchyBuilder() {
    }

    /** 控件树节点：属性全给，不裁剪，前端右侧属性面板直接铺开。 */
    @Data
    public static class HierarchyNode {
        /** 相对当前 page source 的绝对路径，树内唯一，用于点选后回选 / 取 locator */
        private String nodePath;
        /** Appium 可用的绝对 xpath（按 class 索引），等价于 Inspector 里的 xpath 一栏 */
        private String xpath;
        private String tag;
        private String className;
        private String resourceId;
        private String text;
        private String contentDesc;
        private String packageName;
        /** 同级同 tag 中的序号，1 起 */
        private Integer index;
        private Integer depth;
        private Boolean clickable;
        private Boolean enabled;
        private Boolean displayed;
        private Boolean focused;
        private Boolean scrollable;
        private Boolean checkable;
        private Boolean checked;
        private Boolean selected;
        private Boolean password;
        private Boolean longClickable;
        /** 控件树原始坐标 */
        private Map<String, Integer> bounds;
        /** 映射到截图像素坐标，前端画框直接用这个 */
        private Map<String, Integer> screenBounds;
        /** 该节点上的全部原始属性 */
        private Map<String, String> attrs;
        private List<HierarchyNode> children = new ArrayList<>();
    }

    public static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * @param scaleX 控件树 X → 截图像素 X 的比例（同坐标系传 1.0）
     * @param scaleY 同上
     */
    public static HierarchyNode build(Document doc, double scaleX, double scaleY) {
        if (doc == null || doc.getDocumentElement() == null) return null;
        return toNode(doc.getDocumentElement(), null, null, 0, scaleX, scaleY);
    }

    private static HierarchyNode toNode(Element el, String parentPath, String parentXpath,
                                        int depth, double sx, double sy) {
        String path = pathOf(parentPath, el);
        // nodePath 按 XML 里的 tag 索引（能在这份 source 上求值），xpath 按 class 索引（Appium 运行时用）
        String xpath = xpathOf(parentXpath, el);
        HierarchyNode n = new HierarchyNode();
        n.setNodePath(path);
        n.setXpath(xpath);
        n.setTag(el.getTagName());
        n.setDepth(depth);
        n.setIndex(siblingIndex(el, el.getTagName(), false));

        Map<String, String> attrs = new LinkedHashMap<>();
        NamedNodeMap map = el.getAttributes();
        if (map != null) {
            for (int i = 0; i < map.getLength(); i++) {
                Node a = map.item(i);
                attrs.put(a.getNodeName(), a.getNodeValue());
            }
        }
        n.setAttrs(attrs);

        n.setClassName(firstNonBlank(attrs.get("class"), attrs.get("type"), el.getTagName()));
        n.setResourceId(firstNonBlank(attrs.get("resource-id"), attrs.get("resourceId"), attrs.get("id")));
        n.setText(firstNonBlank(attrs.get("text"), attrs.get("label"), attrs.get("value")));
        n.setContentDesc(firstNonBlank(attrs.get("content-desc"), attrs.get("contentDescription"), attrs.get("name")));
        n.setPackageName(firstNonBlank(attrs.get("package"), attrs.get("bundleId")));
        n.setClickable(bool(attrs.get("clickable")));
        n.setEnabled(attrs.get("enabled") == null ? Boolean.TRUE : bool(attrs.get("enabled")));
        n.setFocused(bool(attrs.get("focused")));
        n.setScrollable(bool(attrs.get("scrollable")));
        n.setCheckable(bool(attrs.get("checkable")));
        n.setChecked(bool(attrs.get("checked")));
        n.setSelected(bool(attrs.get("selected")));
        n.setPassword(bool(attrs.get("password")));
        n.setLongClickable(bool(attrs.get("long-clickable")));
        String displayed = attrs.get("displayed");
        String visible = attrs.get("visible");
        n.setDisplayed(displayed != null ? bool(displayed) : (visible != null ? bool(visible) : Boolean.TRUE));

        int[] b = readBounds(attrs);
        if (b != null) {
            n.setBounds(boundsMap(b[0], b[1], b[2], b[3]));
            n.setScreenBounds(boundsMap(
                    (int) Math.round(b[0] * sx), (int) Math.round(b[1] * sy),
                    (int) Math.round(b[2] * sx), (int) Math.round(b[3] * sy)));
        }

        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                n.getChildren().add(toNode((Element) k, path, xpath, depth + 1, sx, sy));
            }
        }
        return n;
    }

    /** 树内唯一路径：/hierarchy/node[1]/node[3]，按 XML 里的 tag 索引，保证能在同一份 source 上回选 */
    public static String pathOf(String parentPath, Element el) {
        String tag = el.getTagName();
        if (parentPath == null || parentPath.isBlank()) return "/" + tag;
        return parentPath + "/" + tag + "[" + siblingIndex(el, tag, false) + "]";
    }

    /** Appium 风格绝对 xpath：优先用 class 属性做标签名，与 Inspector 输出对齐 */
    public static String xpathOf(String parentXpath, Element el) {
        String name = firstNonBlank(el.getAttribute("class"), el.getTagName());
        if (parentXpath == null || parentXpath.isBlank()) return "/" + name;
        return parentXpath + "/" + name + "[" + siblingIndex(el, name, true) + "]";
    }

    private static int siblingIndex(Element el, String name, boolean byClass) {
        int idx = 1;
        Node prev = el.getPreviousSibling();
        while (prev != null) {
            if (prev.getNodeType() == Node.ELEMENT_NODE) {
                Element pe = (Element) prev;
                String pn = byClass ? firstNonBlank(pe.getAttribute("class"), pe.getTagName()) : pe.getTagName();
                if (name != null && name.equals(pn)) idx++;
            }
            prev = prev.getPreviousSibling();
        }
        return idx;
    }

    public static HierarchyNode findByPath(HierarchyNode root, String nodePath) {
        if (root == null || nodePath == null) return null;
        if (nodePath.equals(root.getNodePath())) return root;
        for (HierarchyNode c : root.getChildren()) {
            HierarchyNode hit = findByPath(c, nodePath);
            if (hit != null) return hit;
        }
        return null;
    }

    public static int countNodes(HierarchyNode root) {
        if (root == null) return 0;
        int n = 1;
        for (HierarchyNode c : root.getChildren()) n += countNodes(c);
        return n;
    }

    private static int[] readBounds(Map<String, String> attrs) {
        String bounds = attrs.get("bounds");
        if (bounds != null && !bounds.isBlank()) {
            Matcher m = BOUNDS.matcher(bounds.trim());
            if (m.matches()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
            }
        }
        // iOS XCUITest: x/y/width/height
        try {
            String xs = attrs.get("x");
            String ys = attrs.get("y");
            String ws = attrs.get("width");
            String hs = attrs.get("height");
            if (xs != null && ys != null && ws != null && hs != null) {
                int left = (int) Double.parseDouble(xs);
                int top = (int) Double.parseDouble(ys);
                return new int[]{left, top, left + (int) Double.parseDouble(ws), top + (int) Double.parseDouble(hs)};
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static Map<String, Integer> boundsMap(int l, int t, int r, int b) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("left", l);
        m.put("top", t);
        m.put("right", r);
        m.put("bottom", b);
        m.put("width", Math.max(0, r - l));
        m.put("height", Math.max(0, b - t));
        return m;
    }

    private static Boolean bool(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim())
                || "YES".equalsIgnoreCase(v.trim()));
    }

    private static String firstNonBlank(String... vs) {
        if (vs == null) return null;
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
