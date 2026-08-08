package com.mobile.standalone.service.harmony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把 hmdriver2 / uitest dumpLayout 的 JSON 转成点选引擎可解析的 XML
 *（bounds / text / resource-id 与 Android dump 同形）。
 */
public final class HarmonyHierarchyXml {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HarmonyHierarchyXml() {
    }

    /** @return XML，无法转换时返回 null */
    public static String toInspectXml(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("<")) {
            return s;
        }
        try {
            JsonNode root = JSON.readTree(s);
            if (root.has("hierarchy")) {
                JsonNode h = root.get("hierarchy");
                if (h != null && h.isTextual()) {
                    root = JSON.readTree(h.asText());
                } else if (h != null && !h.isNull()) {
                    root = h;
                }
            }
            if (root == null || root.isNull()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(Math.max(256, s.length()));
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            sb.append("<hierarchy rotation=\"0\">\n");
            appendNode(sb, root, 1);
            sb.append("</hierarchy>\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean looksLikeHierarchy(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim();
        return s.startsWith("<") || s.startsWith("{");
    }

    private static void appendNode(StringBuilder sb, JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        JsonNode attrs = node.has("attributes") ? node.get("attributes") : node;
        String indent = "  ".repeat(Math.max(0, depth));
        sb.append(indent).append("<node");
        writeAttr(sb, "bounds", text(attrs, "bounds"));
        writeAttr(sb, "text", first(attrs, "text", "originalText"));
        String id = first(attrs, "id", "key");
        writeAttr(sb, "resource-id", id);
        writeAttr(sb, "name", text(attrs, "key"));
        writeAttr(sb, "content-desc", text(attrs, "description"));
        // accessibilityId 多为数字，不当作 content-desc，避免点选推荐出 text:76340 这类废定位
        writeAttr(sb, "class", text(attrs, "type"));
        writeAttr(sb, "package", text(attrs, "bundleName"));
        writeAttr(sb, "clickable", boolish(attrs, "clickable"));
        writeAttr(sb, "enabled", boolishDefaultTrue(attrs, "enabled"));
        writeAttr(sb, "focused", boolish(attrs, "focused"));
        writeAttr(sb, "checked", boolish(attrs, "checked"));
        writeAttr(sb, "selected", boolish(attrs, "selected"));
        writeAttr(sb, "scrollable", boolish(attrs, "scrollable"));
        writeAttr(sb, "displayed", visible(attrs));
        JsonNode children = node.get("children");
        boolean hasChildren = children != null && children.isArray() && children.size() > 0;
        if (!hasChildren) {
            sb.append(" />\n");
            return;
        }
        sb.append(">\n");
        for (JsonNode child : children) {
            appendNode(sb, child, depth + 1);
        }
        sb.append(indent).append("</node>\n");
    }

    private static void writeAttr(StringBuilder sb, String name, String value) {
        if (value == null) {
            return;
        }
        sb.append(' ').append(name).append("=\"").append(xmlEscape(value)).append('"');
    }

    private static String text(JsonNode attrs, String field) {
        if (attrs == null || !attrs.has(field) || attrs.get(field).isNull()) {
            return null;
        }
        String v = attrs.get(field).asText("");
        return v.isBlank() ? null : v;
    }

    private static String first(JsonNode attrs, String... fields) {
        for (String f : fields) {
            String v = text(attrs, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String boolish(JsonNode attrs, String field) {
        String v = text(attrs, field);
        if (v == null) {
            return "false";
        }
        return truthy(v) ? "true" : "false";
    }

    private static String boolishDefaultTrue(JsonNode attrs, String field) {
        String v = text(attrs, field);
        if (v == null) {
            return "true";
        }
        return truthy(v) ? "true" : "false";
    }

    private static String visible(JsonNode attrs) {
        String v = text(attrs, "visible");
        if (v == null) {
            return "true";
        }
        return truthy(v) ? "true" : "false";
    }

    private static boolean truthy(String v) {
        String s = v.trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
