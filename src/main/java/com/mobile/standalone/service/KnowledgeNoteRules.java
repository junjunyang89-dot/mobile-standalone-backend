package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 测试知识点的字段规则。
 * <p>
 * 和 Bug / 手册分开：这不是要修的问题，也不是现象→原因→处理。
 * 模块必填、需求选填——找回靠模块，来源靠需求。
 */
public final class KnowledgeNoteRules {

    public static final int MAX_IMAGES = 8;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 拼模块路径只需要这三项，不绑项目树那套 Mapper */
    public record ModuleRef(String id, String parentId, String name) {
    }

    private KnowledgeNoteRules() {
    }

    static String requireTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("标题必填：一句话说清这条规则，过两周才找得到");
        }
        if (t.length() > 255) {
            throw new IllegalArgumentException("标题太长，压到 255 字以内");
        }
        return t;
    }

    static String requireModuleId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("模块必填：下次测同一块功能时才找得到。从需求登记那棵模块树里选");
        }
        return id;
    }

    static String optionalRequirementNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ACTIVE";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if ("STALE".equals(s) || "OUTDATED".equals(s) || "EXPIRED".equals(s)) {
            return "STALE";
        }
        return "ACTIVE";
    }

    static String imagesJson(Object raw) {
        List<String> urls = parseImages(raw);
        if (urls.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("截图最多 " + MAX_IMAGES + " 张");
        }
        try {
            return JSON.writeValueAsString(urls);
        } catch (Exception e) {
            return "[]";
        }
    }

    static List<String> parseImages(Object raw) {
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
        if (s.isEmpty() || "[]".equals(s)) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (Object o : JSON.readValue(s, List.class)) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 「功能域 / 子模块 / 叶子」——和需求登记下拉里看到的同一套名字。
     * 模块被删了也能靠调用方存下的快照显示。
     */
    static String moduleLabel(String moduleId, Function<String, ModuleRef> byId) {
        if (moduleId == null || moduleId.isBlank() || byId == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        String cursor = moduleId;
        for (int i = 0; i < 8 && cursor != null && !cursor.isBlank(); i++) {
            ModuleRef m = byId.apply(cursor);
            if (m == null || m.name() == null || m.name().isBlank()) {
                break;
            }
            parts.add(0, m.name().trim());
            cursor = m.parentId();
        }
        return String.join(" / ", parts);
    }
}
