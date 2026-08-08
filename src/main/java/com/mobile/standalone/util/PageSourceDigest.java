package com.mobile.standalone.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 控件树摘要：把 Appium pageSource 压成人和模型都读得下去的缩进大纲。
 * <p>
 * 一份真机 pageSource 动辄几十万字符，绝大多数是没有文案、没有 id、纯做布局的容器节点。
 * 原样喂给模型不只是贵——真正有用的那几行会被淹掉，反而更容易得出错误结论。
 * 这里只保留「能被人认出来」的节点：有文案、有描述、有 id，或者可点击。
 * <p>
 * Android(UiAutomator2) 与 iOS(XCUITest) 的属性名不一样（text/content-desc/resource-id
 * 对 label/name/value），这里按两套名字都取一遍，谁有取谁。
 */
public final class PageSourceDigest {

    /** 默认摘要上限。留足证据又不至于一份控件树就吃掉整个上下文 */
    public static final int DEFAULT_MAX_CHARS = 20000;

    private PageSourceDigest() {
    }

    /**
     * @param outline    缩进大纲文本
     * @param totalNodes 原始节点总数
     * @param keptNodes  摘要里保留的节点数
     * @param truncated  是否因超出上限被截断（截断了就说明证据不完整，调用方要如实告知）
     * @param rootTag    根节点标签，可据此判断是 Android 还是 iOS 的控件树
     */
    public record Result(String outline, int totalNodes, int keptNodes, boolean truncated, String rootTag) {
        public static Result empty() {
            return new Result("", 0, 0, false, null);
        }
    }

    public static Result of(String xml) {
        return of(xml, DEFAULT_MAX_CHARS);
    }

    public static Result of(String xml, int maxChars) {
        if (xml == null || xml.isBlank()) {
            return Result.empty();
        }
        Document doc;
        try {
            doc = parse(xml);
        } catch (Exception e) {
            // 控件树抓取时可能被截断成非法 XML。这时退回纯文本裁剪，
            // 有残缺证据也好过一句「解析失败」什么都没有
            return new Result(clip(xml, maxChars), 0, 0, xml.length() > maxChars, null);
        }
        Element root = doc.getDocumentElement();
        if (root == null) {
            return Result.empty();
        }
        Counter counter = new Counter();
        List<Row> rows = new ArrayList<>();
        walk(root, 0, rows, counter);
        String outline = renderRows(rows, maxChars, counter);
        return new Result(outline, counter.total, counter.kept, counter.truncated, root.getTagName());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // 控件树来自设备，按不可信输入处理：关掉 DTD / 外部实体
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        // 默认实现会把解析错误直接打到 stderr。半截控件树是可预期的输入（抓取时进程被杀），
        // 这里已经有退回纯文本的处理，不需要再刷一屏看不懂的 XML 报错
        b.setErrorHandler(new SilentErrorHandler());
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void walk(Element el, int depth, List<Row> rows, Counter counter) {
        counter.total++;
        String body = lineOf(el);
        if (body != null) {
            rows.add(new Row(depth, body));
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element child) {
                walk(child, depth + 1, rows, counter);
            }
        }
    }

    /**
     * 按「相对层级」而不是原始深度缩进。
     * <p>
     * XCUITest 的控件树能嵌到 30 多层，中间大量是单子节点的包装容器。直接按原始深度缩进，
     * 每行前面几十个空格，既吃字符又看不出结构——真正有信息的节点其实只分布在少数几个层级上。
     * 这里把出现过的深度排序后取序号，等于把无意义的单链嵌套压平。
     */
    private static String renderRows(List<Row> rows, int maxChars, Counter counter) {
        List<Integer> depths = rows.stream().map(Row::depth).distinct().sorted().toList();
        StringBuilder sb = new StringBuilder();
        for (Row row : rows) {
            int level = Math.min(depths.indexOf(row.depth()), 10);
            String line = " ".repeat(level) + row.body();
            if (sb.length() + line.length() + 1 > maxChars) {
                counter.truncated = true;
                break;
            }
            sb.append(line).append('\n');
            counter.kept++;
        }
        return sb.toString();
    }

    /** 返回 null 表示这个节点没有辨识信息，不值得占用篇幅 */
    private static String lineOf(Element el) {
        String text = firstNonBlank(attr(el, "text"), attr(el, "value"));
        String desc = firstNonBlank(attr(el, "content-desc"), attr(el, "label"));
        String id = firstNonBlank(attr(el, "resource-id"), attr(el, "name"));
        boolean clickable = "true".equals(attr(el, "clickable"));

        if (isBlank(text) && isBlank(desc) && isBlank(id) && !clickable) {
            return null;
        }

        StringBuilder line = new StringBuilder();
        line.append(shortType(el));
        // iOS 的 name 默认就等于 label，两个都打等于把同一句话说两遍。
        // 这类节点在实测的树里占了三分之一，去重省下的篇幅相当可观。
        String shortId = shortId(id);
        if (!isBlank(shortId) && !shortId.equals(text)) {
            appendAttr(line, "id", shortId);
        }
        appendAttr(line, "text", text);
        if (!isBlank(desc) && !desc.equals(text) && !desc.equals(shortId)) {
            appendAttr(line, "desc", desc);
        }
        if (clickable) {
            line.append(" clickable");
        }
        if ("false".equals(attr(el, "enabled"))) {
            line.append(" disabled");
        }
        // 「元素在树里但人看不见」是遮挡/滚出屏幕的典型信号，和「压根没渲染」修法完全不同
        if ("false".equals(attr(el, "displayed")) || "false".equals(attr(el, "visible"))) {
            line.append(" not-displayed");
        }
        String bounds = firstNonBlank(attr(el, "bounds"), iosBounds(el));
        appendAttr(line, "bounds", bounds);
        return line.toString();
    }

    private static void appendAttr(StringBuilder line, String name, String value) {
        if (isBlank(value)) {
            return;
        }
        line.append(' ').append(name).append("=\"").append(clip(value.replace('\n', ' '), 120)).append('"');
    }

    /** com.foo.bar:id/login_btn → login_btn；XCUITest 的 name 常常已经是短名 */
    private static String shortId(String id) {
        if (isBlank(id)) {
            return null;
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
    }

    /** android.widget.TextView → TextView；XCUIElementTypeButton → Button */
    private static String shortType(Element el) {
        String raw = firstNonBlank(attr(el, "class"), attr(el, "type"), el.getTagName());
        if (raw == null) {
            return "?";
        }
        int dot = raw.lastIndexOf('.');
        if (dot >= 0 && dot < raw.length() - 1) {
            raw = raw.substring(dot + 1);
        }
        if (raw.startsWith("XCUIElementType")) {
            raw = raw.substring("XCUIElementType".length());
        }
        return raw.isBlank() ? "?" : raw;
    }

    private static String iosBounds(Element el) {
        String x = attr(el, "x");
        String y = attr(el, "y");
        String w = attr(el, "width");
        String h = attr(el, "height");
        if (isBlank(x) || isBlank(y) || isBlank(w) || isBlank(h)) {
            return null;
        }
        return "[" + x + "," + y + "][" + w + "x" + h + "]";
    }

    private static String attr(Element el, String name) {
        NamedNodeMap attrs = el.getAttributes();
        if (attrs == null) {
            return null;
        }
        Node n = attrs.getNamedItem(name);
        if (n == null) {
            return null;
        }
        String v = n.getNodeValue();
        return isBlank(v) ? null : v.trim();
    }

    /**
     * 大纲里出现过的所有可见文案，供快速判断「页面到底停在哪」。
     */
    public static List<String> visibleTexts(String xml, int limit) {
        Result r = of(xml, DEFAULT_MAX_CHARS);
        List<String> out = new ArrayList<>();
        for (String line : r.outline().split("\n")) {
            int i = line.indexOf(" text=\"");
            if (i < 0) {
                continue;
            }
            int start = i + " text=\"".length();
            int end = line.indexOf('"', start);
            if (end > start) {
                String t = line.substring(start, end).trim();
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    /** 先收集再渲染：缩进层级要等整棵树走完、知道用到了哪些深度才能算 */
    private record Row(int depth, String body) {
    }

    private static final class Counter {
        int total;
        int kept;
        boolean truncated;
    }

    /** 致命错误照常抛出（由 of() 接住退回纯文本），warning/error 不再打屏 */
    private static final class SilentErrorHandler implements org.xml.sax.ErrorHandler {
        @Override
        public void warning(org.xml.sax.SAXParseException e) {
        }

        @Override
        public void error(org.xml.sax.SAXParseException e) {
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXParseException {
            throw e;
        }
    }
}
