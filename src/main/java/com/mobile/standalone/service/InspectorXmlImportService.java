package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.UiElement;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 解析 Appium Inspector 导出的 page source XML，提炼可入库控件候选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectorXmlImportService {

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9_]+");
    private static final Set<String> IOS_INTERACTIVE = Set.of(
            "XCUIElementTypeButton",
            "XCUIElementTypeTextField",
            "XCUIElementTypeSecureTextField",
            "XCUIElementTypeSwitch",
            "XCUIElementTypeLink",
            "XCUIElementTypeSearchField",
            "XCUIElementTypeTextView"
    );

    private final UiElementService uiElementService;
    private final UiPageService uiPageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ParsedCandidate> parse(String platform, String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML 不能为空");
        }
        String plat = normalizePlatform(platform);
        Document doc = parseXml(xml.trim());
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("无效 XML：无根节点");
        }
        List<ParsedCandidate> out = new ArrayList<>();
        Set<String> seenLocator = new HashSet<>();
        Set<String> usedNameEn = new HashSet<>();
        String pageHint = detectPageHint(root);

        if ("H5".equals(plat)) {
            walkH5(root, out, seenLocator, usedNameEn, pageHint);
        } else if ("IOS".equals(plat) || "POG_IOS".equals(plat)) {
            walkIos(root, out, seenLocator, usedNameEn, pageHint, plat);
        } else {
            walkAndroid(root, out, seenLocator, usedNameEn, pageHint, plat);
        }
        return out;
    }

    @Transactional
    public ImportResult importSelected(String appId, String platform, String status,
                                       String pageId, String pageNameZh, String moduleTag,
                                       List<ParsedCandidate> items) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个元素");
        }
        String plat = normalizePlatform(platform);
        String st = status != null && !status.isBlank() ? status.trim().toUpperCase(Locale.ROOT) : "CANDIDATE";
        String resolvedPageId = null;
        if (pageId != null && !pageId.isBlank()) {
            var page = uiPageService.ensurePage(appId.trim(), pageId.trim(),
                    pageNameZh != null && !pageNameZh.isBlank() ? pageNameZh : pageId,
                    moduleTag);
            resolvedPageId = page.getPageId();
        }
        ImportResult result = new ImportResult();
        Set<String> batchSeen = new HashSet<>();
        for (ParsedCandidate c : items) {
            if (c == null) continue;
            String label = blank(c.getNameZh(), blank(c.getNameEn(), "?"));
            try {
                String locatorType = blank(c.getLocatorType(), "accessibilityId");
                String locatorValue = blank(c.getLocatorValue(), "");
                if (locatorValue.isBlank()) {
                    result.getSkipped().add(label + "：定位值为空，跳过");
                    continue;
                }
                String batchKey = locatorType.toLowerCase(Locale.ROOT) + "|" + locatorValue;
                if (!batchSeen.add(batchKey)) {
                    result.getSkipped().add(label + "：本批重复定位，跳过");
                    continue;
                }
                // 跨页面去重：同应用+同平台+相同定位 = 同一元素
                UiElement existing = uiElementService.findByExactLocator(
                        appId.trim(), plat, locatorType, locatorValue);
                if (existing != null) {
                    result.getSkipped().add(label + "：已存在「"
                            + blank(existing.getNameZh(), existing.getNameEn())
                            + "」(page=" + blank(existing.getPageId(), "未归属")
                            + ", nameEn=" + existing.getNameEn() + ")，跳过");
                    continue;
                }
                // 英文名在同应用同平台任意来源已存在，也视为同一元素（跨页不插）
                String nameEnBase = slug(blank(c.getNameEn(), label));
                UiElement byKey = uiElementService.findByKey(appId.trim(), plat, nameEnBase, null);
                if (byKey != null) {
                    result.getSkipped().add(label + "：英文名 "
                            + nameEnBase + " 已存在于页面 "
                            + blank(byKey.getPageId(), "未归属") + "，跳过");
                    continue;
                }

                UiElement e = new UiElement();
                e.setAppId(appId.trim());
                e.setPlatform(plat);
                e.setCatalog("SHARED");
                e.setSource("INSPECTOR_XML");
                e.setStatus(st);
                e.setNameZh(blank(c.getNameZh(), c.getNameEn()));
                e.setNameEn(ensureUniqueNameEn(appId.trim(), plat, blank(c.getNameEn(), "el")));
                e.setLocatorType(locatorType);
                e.setLocatorValue(locatorValue);
                e.setLocatorFallbacks(serializeFallbacks(c));
                e.setVisualHint(c.getVisualHint());
                e.setModuleTag(blank(moduleTag, blank(c.getModuleTag(), null)));
                e.setPageId(resolvedPageId != null ? resolvedPageId : blank(c.getPageId(), null));
                e.setDescription(c.getDescription());
                e.setEnabled(true);
                e.setDefaultTimeoutSec(10);
                UiElement saved = uiElementService.create(e);
                result.getCreated().add(saved);
            } catch (Exception ex) {
                result.getErrors().add(label + ": " + ex.getMessage());
            }
        }
        return result;
    }

    private String ensureUniqueNameEn(String appId, String platform, String base) {
        String name = slug(base);
        if (name.isBlank()) name = "el";
        String candidate = name;
        int i = 2;
        while (uiElementService.findByKey(appId, platform, candidate, "INSPECTOR_XML") != null) {
            candidate = name + "_" + i++;
            if (i > 200) {
                return name + "_" + System.currentTimeMillis();
            }
        }
        return candidate;
    }

    private void walkIos(Element el, List<ParsedCandidate> out, Set<String> seenLocator,
                         Set<String> usedNameEn, String pageHint, String platform) {
        String type = el.getAttribute("type");
        if (type == null || type.isBlank()) {
            type = el.getTagName();
        }
        if (IOS_INTERACTIVE.contains(type)
                || ("XCUIElementTypeStaticText".equals(type) && isShortActionLabel(firstNonBlank(
                el.getAttribute("name"), el.getAttribute("label"), el.getAttribute("value"))))) {
            String name = firstNonBlank(el.getAttribute("name"), el.getAttribute("label"), el.getAttribute("value"));
            if (name != null && !name.isBlank() && !"null".equalsIgnoreCase(name)) {
                if (!("XCUIElementTypeStaticText".equals(type) && name.length() > 24)) {
                    List<LocatorOption> options = buildIosLocatorOptions(type, name);
                    if (!options.isEmpty()) {
                        LocatorOption primary = options.get(0);
                        String key = primary.getLocatorType() + "|" + primary.getLocatorValue();
                        if (seenLocator.add(key)) {
                            ParsedCandidate c = new ParsedCandidate();
                            c.setPlatform(platform);
                            c.setElementType(type);
                            c.setNameZh(trimLabel(name));
                            c.setNameEn(uniqueSlug(usedNameEn, trimLabel(name)));
                            applyPrimaryAndFallbacks(c, options);
                            c.setVisualHint(buildIosHint(el, type, name) + " · " + options.size() + " 种定位");
                            c.setPageId(pageHint);
                            c.setModuleTag(pageHint != null ? pageHint : "inspector");
                            c.setBounds(boundsMap(el));
                            c.setDescription("Appium Inspector XML · " + type);
                            c.setSelected(shouldAutoSelect(type, name));
                            out.add(c);
                        }
                    }
                }
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                walkIos((Element) n, out, seenLocator, usedNameEn, pageHint, platform);
            }
        }
    }

    private void walkAndroid(Element el, List<ParsedCandidate> out, Set<String> seenLocator,
                             Set<String> usedNameEn, String pageHint, String platform) {
        String className = firstNonBlank(el.getAttribute("class"), el.getTagName());
        String rid = el.getAttribute("resource-id");
        String text = el.getAttribute("text");
        String desc = el.getAttribute("content-desc");
        boolean clickable = "true".equalsIgnoreCase(el.getAttribute("clickable"));
        boolean checkable = "true".equalsIgnoreCase(el.getAttribute("checkable"));
        boolean interesting = clickable || checkable
                || (rid != null && !rid.isBlank() && !"null".equalsIgnoreCase(rid)
                && (text != null && !text.isBlank() || desc != null && !desc.isBlank()));
        if (interesting) {
            List<LocatorOption> options = buildAndroidLocatorOptions(className, rid, text, desc);
            if (!options.isEmpty()) {
                LocatorOption primary = options.get(0);
                String key = primary.getLocatorType() + "|" + primary.getLocatorValue();
                if (seenLocator.add(key)) {
                    String label = firstNonBlank(text, desc, shortResourceId(rid), className);
                    ParsedCandidate c = new ParsedCandidate();
                    c.setPlatform(platform);
                    c.setElementType(className);
                    c.setNameZh(trimLabel(label));
                    c.setNameEn(uniqueSlug(usedNameEn, trimLabel(label)));
                    applyPrimaryAndFallbacks(c, options);
                    c.setVisualHint("Android · " + className
                            + (clickable ? " · clickable" : "")
                            + " · " + options.size() + " 种定位");
                    c.setPageId(pageHint);
                    c.setModuleTag(pageHint != null ? pageHint : "inspector");
                    c.setBounds(parseAndroidBounds(el.getAttribute("bounds")));
                    c.setDescription("Appium Inspector XML · Android");
                    c.setSelected(clickable || isDialogAction(label));
                    out.add(c);
                }
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                walkAndroid((Element) n, out, seenLocator, usedNameEn, pageHint, platform);
            }
        }
    }

    private static List<LocatorOption> buildIosLocatorOptions(String type, String name) {
        List<LocatorOption> opts = new ArrayList<>();
        String q = escapeIosQuote(name);
        opts.add(opt("accessibilityId", "accessibility id", name));
        opts.add(opt("iosPredicate", "-ios predicate string", "name == \"" + q + "\""));
        opts.add(opt("iosClassChain", "-ios class chain",
                "**/" + type + "[`name == \"" + q + "\"`]"));
        opts.add(opt("xpath", "xpath", "//" + type + "[@name=\"" + q + "\"]"));
        return opts;
    }

    private static List<LocatorOption> buildAndroidLocatorOptions(String className, String rid, String text, String desc) {
        List<LocatorOption> opts = new ArrayList<>();
        if (rid != null && !rid.isBlank() && !"null".equalsIgnoreCase(rid)) {
            opts.add(opt("id", "id", rid));
            opts.add(opt("xpath", "xpath", "//*[@resource-id=\"" + escapeIosQuote(rid) + "\"]"));
        }
        if (text != null && !text.isBlank()) {
            opts.add(opt("text", "text", text));
            opts.add(opt("xpath", "xpath", "//*[@text=\"" + escapeIosQuote(text) + "\"]"));
        }
        if (desc != null && !desc.isBlank()) {
            opts.add(opt("accessibilityId", "accessibility id", desc));
            opts.add(opt("xpath", "xpath", "//*[@content-desc=\"" + escapeIosQuote(desc) + "\"]"));
        }
        // 去重
        List<LocatorOption> dedup = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LocatorOption o : opts) {
            String k = o.getLocatorType() + "|" + o.getLocatorValue();
            if (seen.add(k)) dedup.add(o);
        }
        return dedup;
    }

    private static void applyPrimaryAndFallbacks(ParsedCandidate c, List<LocatorOption> options) {
        LocatorOption primary = options.get(0);
        c.setLocatorType(primary.getLocatorType());
        c.setLocatorValue(primary.getLocatorValue());
        c.setLocatorOptions(options);
        if (options.size() > 1) {
            c.setLocatorFallbacks(options.subList(1, options.size()));
        } else {
            c.setLocatorFallbacks(List.of());
        }
    }

    private String serializeFallbacks(ParsedCandidate c) {
        try {
            List<LocatorOption> fb = c.getLocatorFallbacks();
            if (fb == null || fb.isEmpty()) {
                // 从 options 推：除主定位外
                if (c.getLocatorOptions() != null && c.getLocatorOptions().size() > 1) {
                    List<LocatorOption> rest = new ArrayList<>();
                    for (LocatorOption o : c.getLocatorOptions()) {
                        if (o.getLocatorType().equals(c.getLocatorType())
                                && o.getLocatorValue().equals(c.getLocatorValue())) {
                            continue;
                        }
                        rest.add(o);
                    }
                    fb = rest;
                }
            }
            if (fb == null || fb.isEmpty()) return null;
            return objectMapper.writeValueAsString(fb);
        } catch (Exception e) {
            log.warn("序列化 locatorFallbacks 失败: {}", e.getMessage());
            return null;
        }
    }

    private static LocatorOption opt(String type, String label, String value) {
        LocatorOption o = new LocatorOption();
        o.setLocatorType(type);
        o.setLabel(label);
        o.setLocatorValue(value);
        return o;
    }

    private static String escapeIosQuote(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void walkH5(Element el, List<ParsedCandidate> out, Set<String> seenLocator,
                        Set<String> usedNameEn, String pageHint) {
        String tag = el.getTagName() != null ? el.getTagName().toLowerCase(Locale.ROOT) : "";
        boolean interesting = "button".equals(tag) || "a".equals(tag) || "input".equals(tag)
                || "textarea".equals(tag) || "select".equals(tag)
                || el.hasAttribute("onclick") || "true".equalsIgnoreCase(el.getAttribute("contenteditable"));
        if (interesting) {
            String id = el.getAttribute("id");
            String text = firstNonBlank(el.getTextContent() != null ? el.getTextContent().trim() : null,
                    el.getAttribute("value"), el.getAttribute("placeholder"), el.getAttribute("aria-label"),
                    el.getAttribute("name"));
            String locatorType;
            String locatorValue;
            if (id != null && !id.isBlank()) {
                locatorType = "css";
                locatorValue = "#" + id;
            } else if (text != null && !text.isBlank() && text.length() <= 40) {
                locatorType = "xpath";
                locatorValue = "//*[normalize-space(text())='" + text.replace("'", "") + "']";
            } else {
                locatorType = null;
                locatorValue = null;
            }
            if (locatorType != null) {
                String key = locatorType + "|" + locatorValue;
                if (seenLocator.add(key)) {
                    ParsedCandidate c = new ParsedCandidate();
                    c.setPlatform("H5");
                    c.setElementType(tag);
                    c.setNameZh(trimLabel(firstNonBlank(text, id, tag)));
                    c.setNameEn(uniqueSlug(usedNameEn, trimLabel(firstNonBlank(text, id, tag))));
                    c.setLocatorType(locatorType);
                    c.setLocatorValue(locatorValue);
                    c.setVisualHint("H5 · <" + tag + "> · " + locatorType + "=" + locatorValue);
                    c.setPageId(pageHint);
                    c.setModuleTag(pageHint != null ? pageHint : "h5");
                    c.setDescription("Inspector / HTML 导入");
                    c.setSelected("button".equals(tag) || "a".equals(tag));
                    out.add(c);
                }
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                walkH5((Element) n, out, seenLocator, usedNameEn, pageHint);
            }
        }
    }

    private static boolean shouldAutoSelect(String type, String name) {
        if ("XCUIElementTypeButton".equals(type) || "XCUIElementTypeTextField".equals(type)
                || "XCUIElementTypeSecureTextField".equals(type) || "XCUIElementTypeSwitch".equals(type)) {
            return true;
        }
        return isDialogAction(name);
    }

    private static boolean isDialogAction(String label) {
        if (label == null) return false;
        String t = label.trim();
        return "同意".equals(t) || "不同意".equals(t) || "取消".equals(t) || "确定".equals(t)
                || "确认".equals(t) || "关闭".equals(t) || "允许".equals(t) || "不允许".equals(t);
    }

    private static boolean isShortActionLabel(String s) {
        return s != null && !s.isBlank() && s.length() <= 12 && !s.contains("\n");
    }

    private static String detectPageHint(Element root) {
        String xml = root.getTextContent();
        // 快速扫属性
        List<String> names = new ArrayList<>();
        collectNames(root, names, 80);
        for (String n : names) {
            if ("温馨提示".equals(n)) return "PRIVACY_WARM_TIP";
            if (n != null && n.contains("登录")) return "LOGIN";
        }
        if (xml != null && xml.contains("温馨提示")) return "PRIVACY_WARM_TIP";
        return null;
    }

    private static void collectNames(Element el, List<String> out, int limit) {
        if (out.size() >= limit) return;
        for (String a : List.of("name", "label", "value", "text", "content-desc")) {
            String v = el.getAttribute(a);
            if (v != null && !v.isBlank() && v.length() <= 40) out.add(v.trim());
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                collectNames((Element) n, out, limit);
            }
        }
    }

    private static String buildIosHint(Element el, String type, String name) {
        String x = el.getAttribute("x");
        String y = el.getAttribute("y");
        String w = el.getAttribute("width");
        String h = el.getAttribute("height");
        return type + " · name=" + name
                + (x.isBlank() ? "" : " · bounds=(" + x + "," + y + "," + w + "," + h + ")");
    }

    private static Map<String, Integer> boundsMap(Element el) {
        try {
            int x = Integer.parseInt(el.getAttribute("x"));
            int y = Integer.parseInt(el.getAttribute("y"));
            int w = Integer.parseInt(el.getAttribute("width"));
            int h = Integer.parseInt(el.getAttribute("height"));
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("left", x);
            m.put("top", y);
            m.put("right", x + w);
            m.put("bottom", y + h);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Integer> parseAndroidBounds(String bounds) {
        // [l,t][r,b]
        if (bounds == null || !bounds.startsWith("[")) return null;
        try {
            String[] parts = bounds.replace("][", ",").replace("[", "").replace("]", "").split(",");
            if (parts.length != 4) return null;
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("left", Integer.parseInt(parts[0].trim()));
            m.put("top", Integer.parseInt(parts[1].trim()));
            m.put("right", Integer.parseInt(parts[2].trim()));
            m.put("bottom", Integer.parseInt(parts[3].trim()));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            // Inspector 导出可能很大；允许略宽松
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML 解析失败: " + e.getMessage());
        }
    }

    private static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空（IOS / ANDROID / H5）");
        }
        String p = platform.trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "IOS", "POG_IOS", "IPHONE", "IPAD" -> p.startsWith("POG") ? "POG_IOS" : "IOS";
            case "ANDROID", "POG_ANDROID" -> p;
            case "H5", "WEB", "WEBVIEW" -> "H5";
            case "HARMONYOS", "HARMONY" -> "HARMONYOS";
            default -> p;
        };
    }

    private static String uniqueSlug(Set<String> used, String label) {
        String base = slug(label);
        if (base.isBlank()) base = "el";
        String c = base;
        int i = 2;
        while (!used.add(c)) {
            c = base + "_" + i++;
        }
        return c;
    }

    private static String slug(String s) {
        if (s == null) return "el";
        // 中文保留拼音太重：用简单策略——纯英文数字则 slug；否则用 hash 短码 + 可读前缀
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.matches("[a-z0-9_\\-\\s]+")) {
            return NON_SLUG.matcher(t.replace('-', '_').replace(' ', '_')).replaceAll("_")
                    .replaceAll("_+", "_").replaceAll("^_|_$", "");
        }
        Map<String, String> known = Map.of(
                "同意", "agree",
                "不同意", "disagree",
                "取消", "cancel",
                "确定", "confirm",
                "确认", "confirm",
                "关闭", "close",
                "温馨提示", "warm_tip",
                "登录", "login",
                "发布", "publish"
        );
        if (known.containsKey(s.trim())) return known.get(s.trim());
        if (s.contains("用户协议")) return "user_agreement_link";
        if (s.contains("个人信息保护") || s.contains("隐私")) return "privacy_policy_link";
        String ascii = "el_" + Integer.toHexString(s.trim().hashCode()).replace("-", "n");
        return ascii;
    }

    private static String trimLabel(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() > 40) t = t.substring(0, 40);
        return t;
    }

    private static String shortResourceId(String rid) {
        if (rid == null) return null;
        int i = rid.lastIndexOf('/');
        return i >= 0 ? rid.substring(i + 1) : rid;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v)) return v.trim();
        }
        return null;
    }

    private static String blank(String v, String dft) {
        return v == null || v.isBlank() ? dft : v.trim();
    }

    @Data
    public static class LocatorOption {
        /** 入库用类型：accessibilityId / iosPredicate / iosClassChain / xpath / id / text */
        private String locatorType;
        /** 展示用，对齐 Appium Inspector：accessibility id / -ios predicate string … */
        private String label;
        private String locatorValue;
    }

    @Data
    public static class ParsedCandidate {
        private String platform;
        private String elementType;
        private String nameZh;
        private String nameEn;
        private String locatorType;
        private String locatorValue;
        /** 全部定位方案（含主） */
        private List<LocatorOption> locatorOptions;
        /** 备用（不含主） */
        private List<LocatorOption> locatorFallbacks;
        private String visualHint;
        private String moduleTag;
        private String pageId;
        private String description;
        private Map<String, Integer> bounds;
        /** 解析时建议勾选 */
        private boolean selected;
    }

    @Data
    public static class ImportResult {
        private List<UiElement> created = new ArrayList<>();
        /** 因跨页重复等跳过 */
        private List<String> skipped = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
    }
}
