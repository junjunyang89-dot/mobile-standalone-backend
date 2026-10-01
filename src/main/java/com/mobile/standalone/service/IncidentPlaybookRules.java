package com.mobile.standalone.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 事故沉淀的字段规则。
 * <p>
 * 这不是线上 Bug，也不是需求上的业务知识点。
 * 记的是「这次怎么炸的、下次凭哪句报错认出来、有没有做成可跑的检查」。
 */
public final class IncidentPlaybookRules {

    static final Set<String> CATEGORIES = Set.of(
            "DEPENDENCY", "CACHE", "SIGNING", "CONFIG", "ENV", "BUILD", "OTHER");
    static final Set<String> TOOL_STATUSES = Set.of("NONE", "SCRIPT", "TOOL");

    private IncidentPlaybookRules() {
    }

    static String requireTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("标题必填：一句话说清炸在哪");
        }
        if (t.length() > 255) {
            throw new IllegalArgumentException("标题压到 255 字以内");
        }
        return t;
    }

    static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "OTHER";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(s) ? s : "OTHER";
    }

    static String normalizeToolStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NONE";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return TOOL_STATUSES.contains(s) ? s : "NONE";
    }

    static List<String> parseFingerprints(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.startsWith("[")) {
            try {
                List<String> out = new ArrayList<>();
                for (Object o : new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, List.class)) {
                    if (o != null && !String.valueOf(o).isBlank()) {
                        out.add(String.valueOf(o).trim());
                    }
                }
                return out;
            } catch (Exception ignored) {
                // 当成分号/换行文本继续拆
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split("[\\n;；]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    static String fingerprintsText(Object raw) {
        return String.join("\n", parseFingerprints(raw));
    }

    static String categoryLabel(String category) {
        return switch (normalizeCategory(category)) {
            case "DEPENDENCY" -> "依赖包";
            case "CACHE" -> "构建缓存";
            case "SIGNING" -> "签名证书";
            case "CONFIG" -> "配置";
            case "ENV" -> "环境/机器";
            case "BUILD" -> "构建脚本";
            default -> "其他";
        };
    }

    static String toolStatusLabel(String status) {
        return switch (normalizeToolStatus(status)) {
            case "SCRIPT" -> "已有脚本";
            case "TOOL" -> "已做成工具";
            default -> "仅记录";
        };
    }
}
