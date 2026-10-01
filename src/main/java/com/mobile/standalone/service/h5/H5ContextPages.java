package com.mobile.standalone.service.h5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 Appium {@code mobile: getContexts} 里挑当前 H5 页（含 CDP websocket）。
 */
public final class H5ContextPages {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H5ContextPages() {
    }

    public record Target(
            String url,
            String wsUrl,
            int screenX,
            int screenY,
            int width,
            int height,
            boolean visible
    ) {
    }

    @SuppressWarnings("unchecked")
    public static List<Target> parse(Object raw) {
        List<Target> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object pagesObj = m.get("pages");
            if (!(pagesObj instanceof List<?> pages)) {
                continue;
            }
            for (Object p : pages) {
                if (!(p instanceof Map<?, ?> page)) {
                    continue;
                }
                String url = str(page.get("url"));
                String ws = str(page.get("webSocketDebuggerUrl"));
                if (ws.isBlank()) {
                    continue;
                }
                if (url.isBlank() || "about:blank".equalsIgnoreCase(url)) {
                    continue;
                }
                int sx = 0, sy = 0, w = 0, h = 0;
                boolean visible = false;
                String desc = str(page.get("description"));
                if (!desc.isBlank() && desc.trim().startsWith("{")) {
                    try {
                        JsonNode n = JSON.readTree(desc);
                        sx = n.path("screenX").asInt(0);
                        sy = n.path("screenY").asInt(0);
                        w = n.path("width").asInt(0);
                        h = n.path("height").asInt(0);
                        visible = n.path("visible").asBoolean(false);
                    } catch (Exception ignored) {
                        // description 不是 JSON 就当不可见页
                    }
                }
                out.add(new Target(url, ws, sx, sy, w, h, visible));
            }
        }
        return out;
    }

    public static Target pick(List<Target> all) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        Target visible = null;
        Target hash = null;
        for (Target t : all) {
            if (t.visible && visible == null) {
                visible = t;
            }
            if (t.url.contains("#") && hash == null) {
                hash = t;
            }
        }
        return visible != null ? visible : (hash != null ? hash : all.get(0));
    }

    public static int tapX(Target page, double cssX, double viewW) {
        if (page == null) {
            return (int) Math.round(cssX);
        }
        double scale = (page.width > 0 && viewW > 1) ? (page.width / viewW) : 1.0;
        return page.screenX + (int) Math.round(cssX * scale);
    }

    public static int tapY(Target page, double cssY, double viewH) {
        if (page == null) {
            return (int) Math.round(cssY);
        }
        double scale = (page.height > 0 && viewH > 1) ? (page.height / viewH) : 1.0;
        return page.screenY + (int) Math.round(cssY * scale);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
