package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Confluence storage/view + child lists into the analysis snapshot.
 * Comments stay supplements, never merged into acceptance criteria.
 */
final class WikiRequirementParser {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Pattern H = Pattern.compile("(?is)<h([1-4])[^>]*>(.*?)</h\\1>");
    private static final Pattern IMG = Pattern.compile("(?is)<img([^>]+)>");
    private static final Pattern ATTR = Pattern.compile("(?is)([a-zA-Z0-9:-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FILENAME = Pattern.compile("(?is)<ri:attachment[^>]*ri:filename=\"([^\"]+)\"");
    private static final Pattern EXPAND = Pattern.compile("(?is)ac:name=\"expand\"");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");

    private WikiRequirementParser() {}

    static ObjectNode snapshot(String pageId, JsonNode page, String storage, String view,
                               List<JsonNode> comments, List<JsonNode> attachments, String source) {
        ObjectNode out = F.objectNode();
        out.put("schema", "wiki-snapshot-1");
        out.put("source", source == null ? "rest" : source);
        out.put("fetchedAt", Instant.now().toString());
        ObjectNode meta = out.putObject("page");
        meta.put("id", pageId);
        meta.put("title", page.path("title").asText(""));
        meta.put("version", page.path("version").path("number").asInt(0));
        meta.put("updatedAt", page.path("version").path("when").asText(""));
        meta.put("space", page.path("space").path("key").asText(""));
        meta.put("url", "https://wiki.shdev.net/pages/viewpage.action?pageId=" + pageId);

        String html = view == null ? "" : view;
        out.put("tableCount", count(html, "<table"));
        out.put("codeBlockCount", count(html, "<pre"));
        out.put("expandPresent", storage != null && EXPAND.matcher(storage).find());

        ArrayNode sections = out.putArray("sections");
        List<String> path = new ArrayList<>();
        Matcher hm = H.matcher(html);
        int last = 0;
        String pendingHeading = page.path("title").asText("");
        int pendingLevel = 1;
        while (hm.find()) {
            emitSection(sections, path, pendingHeading, pendingLevel, html.substring(last, hm.start()));
            pendingLevel = Integer.parseInt(hm.group(1));
            pendingHeading = strip(hm.group(2));
            last = hm.end();
        }
        emitSection(sections, path, pendingHeading, pendingLevel, html.substring(last));

        Map<String, JsonNode> attachByName = new LinkedHashMap<>();
        ArrayNode attachList = out.putArray("attachments");
        if (attachments != null) {
            for (JsonNode a : attachments) {
                String title = a.path("title").asText("");
                attachByName.put(title.toLowerCase(Locale.ROOT), a);
                ObjectNode row = attachList.addObject();
                row.put("id", a.path("id").asText());
                row.put("title", title);
                row.put("version", a.path("version").path("number").asInt(1));
                row.put("mediaType", a.path("extensions").path("mediaType").asText(""));
                row.put("downloadPath", a.path("_links").path("download").asText(""));
                row.put("referencedInBody", referenced(storage, html, title));
            }
        }

        ArrayNode images = out.putArray("images");
        int pos = 0;
        Matcher im = IMG.matcher(html);
        while (im.find()) {
            pos++;
            Map<String, String> attrs = attrs(im.group(1));
            String src = unescape(attrs.getOrDefault("src", ""));
            String alias = firstNonBlank(attrs.get("data-linked-resource-default-alias"),
                    attrs.get("alt"), filenameFromSrc(src));
            boolean placeholder = isPlaceholder(attrs, src);
            ObjectNode img = images.addObject();
            img.put("assetId", firstNonBlank(attrs.get("data-linked-resource-id"), alias, "img-" + pos));
            img.put("pageId", pageId);
            ArrayNode sp = img.putArray("sectionPath");
            for (String p : currentPathForOffset(html, im.start())) sp.add(p);
            img.put("position", pos);
            img.put("originalUrl", src);
            img.put("filename", alias);
            JsonNode att = attachByName.get(alias.toLowerCase(Locale.ROOT));
            img.put("attachmentVersion", att == null ? 0 : att.path("version").path("number").asInt(0));
            img.put("placeholder", placeholder);
            img.put("status", placeholder ? "excluded-placeholder" : "listed");
            img.put("duplicateOfEarlier", alreadyListed(images, alias, pos));
        }
        if (storage != null) {
            Matcher fm = FILENAME.matcher(storage);
            while (fm.find()) {
                String fn = fm.group(1);
                if (!fn.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|gif|webp)$")) continue;
                boolean seen = false;
                for (JsonNode n : images) if (fn.equalsIgnoreCase(n.path("filename").asText())) { seen = true; break; }
                if (!seen) {
                    ObjectNode img = images.addObject();
                    img.put("assetId", fn);
                    img.put("pageId", pageId);
                    img.putArray("sectionPath").add("storage");
                    img.put("position", images.size());
                    img.put("filename", fn);
                    img.put("placeholder", false);
                    img.put("status", "listed");
                }
            }
        }

        ArrayNode commentArr = out.putArray("comments");
        if (comments != null) {
            for (JsonNode c : comments) {
                ObjectNode row = commentArr.addObject();
                row.put("id", c.path("id").asText());
                row.put("author", c.path("history").path("createdBy").path("displayName").asText(
                        c.path("version").path("by").path("displayName").asText("")));
                row.put("time", c.path("version").path("when").asText(""));
                row.put("body", strip(c.path("body").path("view").path("value").asText("")));
                row.put("parentId", c.path("ancestors").isArray() && c.path("ancestors").size() > 0
                        ? c.path("ancestors").get(c.path("ancestors").size() - 1).path("id").asText("") : "");
                row.put("role", "comment-supplement");
                row.put("acceptance", "需确认，不作为正式验收条件");
            }
        }

        ArrayNode gaps = out.putArray("gaps");
        if (html.isBlank() && (storage == null || storage.isBlank())) gaps.add("正文为空");
        if (out.path("expandPresent").asBoolean() && !html.toLowerCase(Locale.ROOT).contains("人工智能demo")
                && (storage == null || !storage.contains("人工智能DEMO"))) {
            gaps.add("折叠区人工智能 DEMO 可能未展开");
        }
        return out;
    }

    static List<String> wikiUrlsFromMaterials(JsonNode materials) {
        List<String> urls = new ArrayList<>();
        if (materials == null || !materials.isArray()) return urls;
        for (JsonNode m : materials) {
            String url = m.path("url").asText("");
            if (WikiPageIds.extract(url).isPresent()) urls.add(url);
        }
        return urls;
    }

    private static void emitSection(ArrayNode sections, List<String> path, String heading, int level, String html) {
        while (path.size() >= level) path.remove(path.size() - 1);
        if (heading != null && !heading.isBlank()) {
            while (path.size() < level - 1) path.add("");
            if (path.size() == level - 1) path.add(heading);
            else path.set(level - 1, heading);
        }
        ObjectNode s = sections.addObject();
        ArrayNode p = s.putArray("path");
        for (String x : path) p.add(x);
        s.put("heading", heading == null ? "" : heading);
        s.put("text", strip(html));
        s.put("unconfirmedDemo", path.stream().anyMatch(x -> x.toUpperCase(Locale.ROOT).contains("DEMO")));
        org.jsoup.nodes.Document doc=org.jsoup.Jsoup.parse(html);doc.select("style,script").remove();
        ArrayNode tables=s.putArray("tables");
        for(var table:doc.select("table")){ArrayNode rows=tables.addArray();for(var row:table.select("tr")){ArrayNode cells=rows.addArray();for(var cell:row.children())if(cell.tagName().matches("td|th"))cells.add(cell.text());}}
        ArrayNode code=s.putArray("codeBlocks");for(var pre:doc.select("pre"))code.add(pre.wholeText());
        s.put("toc", heading != null && (heading.contains("目录") || html.contains("toc-macro")));
    }

    private static List<String> currentPathForOffset(String html, int offset) {
        List<String> path = new ArrayList<>();
        Matcher hm = H.matcher(html.substring(0, Math.min(offset, html.length())));
        while (hm.find()) {
            int level = Integer.parseInt(hm.group(1));
            String heading = strip(hm.group(2));
            while (path.size() >= level) path.remove(path.size() - 1);
            while (path.size() < level - 1) path.add("");
            if (path.size() == level - 1) path.add(heading);
            else path.set(level - 1, heading);
        }
        return path;
    }

    private static boolean referenced(String storage, String view, String title) {
        if (title == null || title.isBlank()) return false;
        if (view != null && view.contains(title)) return true;
        return storage != null && storage.contains(title);
    }

    private static boolean isPlaceholder(Map<String, String> attrs, String src) {
        String cls = attrs.getOrDefault("class", "");
        if (cls.contains("placeholder") || cls.contains("thumb-placeholder") || src.contains("placeholder-medium-file") || src.contains("placeholder-small-file")) return true;
        String w = attrs.getOrDefault("width", ""), h = attrs.getOrDefault("height", "");
        return "16".equals(w) || "16".equals(h);
    }

    private static boolean alreadyListed(ArrayNode images, String filename, int currentPos) {
        if (filename == null || filename.isBlank()) return false;
        for (JsonNode n : images) {
            if (n.path("position").asInt() == currentPos) continue;
            if (filename.equalsIgnoreCase(n.path("filename").asText())) return true;
        }
        return false;
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher a = ATTR.matcher(raw);
        while (a.find()) m.put(a.group(1).toLowerCase(Locale.ROOT), unescape(a.group(2)));
        return m;
    }

    private static String filenameFromSrc(String src) {
        if (src == null) return "";
        int q = src.indexOf('?');
        String p = q < 0 ? src : src.substring(0, q);
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String strip(String html) {
        if (html == null) return "";
        org.jsoup.nodes.Document doc=org.jsoup.Jsoup.parse(html);doc.select("script,style,.toc-macro").remove();
        String t = doc.body().wholeText();
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static int count(String html, String token) {
        if (html == null) return 0;
        int n = 0, i = 0;
        String lower = html.toLowerCase(Locale.ROOT);
        String needle = token.toLowerCase(Locale.ROOT);
        while ((i = lower.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return "";
        for (String x : xs) if (x != null && !x.isBlank()) return x;
        return "";
    }
}
