package com.mobile.standalone.service.capture;

import java.util.Locale;
import java.util.Set;

/**
 * 抓包流水按资源类型归类。
 * <p>
 * 一次 H5 操作会带出页面、JS、CSS、图片和真正的接口，平铺在一张表里
 * 分不清「测什么」。先按路径后缀和 Content-Type 分开；哪些路径算接口，
 * 之后可以再收紧。
 */
public final class CaptureResourceKind {

    public static final String API = "api";
    public static final String JS = "js";
    public static final String CSS = "css";
    public static final String IMAGE = "image";
    public static final String PAGE = "page";
    public static final String OTHER = "other";

    private static final Set<String> JS_EXT = Set.of("js", "mjs", "cjs");
    private static final Set<String> CSS_EXT = Set.of("css");
    private static final Set<String> IMAGE_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif");
    private static final Set<String> PAGE_EXT = Set.of("html", "htm", "xhtml");
    private static final Set<String> OTHER_EXT = Set.of(
            "woff", "woff2", "ttf", "otf", "eot",
            "mp4", "webm", "mp3", "m4a", "m3u8", "aac", "map");

    private CaptureResourceKind() {
    }

    public static boolean isApi(String kind) {
        return API.equals(kind);
    }

    public static String of(String path, String contentType) {
        String ext = extension(path);
        if (ext != null) {
            if (JS_EXT.contains(ext)) {
                return JS;
            }
            if (CSS_EXT.contains(ext)) {
                return CSS;
            }
            if (IMAGE_EXT.contains(ext)) {
                return IMAGE;
            }
            if (PAGE_EXT.contains(ext)) {
                return PAGE;
            }
            if (OTHER_EXT.contains(ext)) {
                return OTHER;
            }
            if ("json".equals(ext)) {
                return API;
            }
        }
        String ct = mime(contentType);
        if (ct.startsWith("image/")) {
            return IMAGE;
        }
        if (ct.contains("javascript") || ct.contains("ecmascript")) {
            return JS;
        }
        if (ct.equals("text/css")) {
            return CSS;
        }
        if (ct.contains("html")) {
            // 接口 404 也常回 text/html（如 /api/v1/...）。没有 .html 后缀别当成页面，
            // 不然「页面」页签会被这些假页面占满，真 H5 反而难找。
            return API;
        }
        if (ct.startsWith("font/") || ct.startsWith("audio/") || ct.startsWith("video/")) {
            return OTHER;
        }
        return API;
    }

    static String extension(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int slash = p.lastIndexOf('/');
        String file = slash >= 0 ? p.substring(slash + 1) : p;
        int dot = file.lastIndexOf('.');
        if (dot <= 0 || dot == file.length() - 1) {
            return null;
        }
        return file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String ct = contentType.toLowerCase(Locale.ROOT).trim();
        int semi = ct.indexOf(';');
        return semi >= 0 ? ct.substring(0, semi).trim() : ct;
    }
}
