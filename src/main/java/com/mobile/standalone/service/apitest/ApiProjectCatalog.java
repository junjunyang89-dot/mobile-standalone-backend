package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 项目管理页分类。内置只有「电竞APP」（key 仍是 APP，库里和各处查询都按这个 key）；
 * POGPOG 等是在分类管理里建的自定义分类。
 * <p>
 * 早先还有 PVP / WEB / 其他 三个内置分类，已下线。库里残留的这三个值一律当成 APP，
 * 否则项目上挂着旧值时，树会把已删掉的分类又原样渲染出来。
 */
public final class ApiProjectCatalog {

    private ApiProjectCatalog() {
    }

    public record Category(String key, String label) {
    }

    public static final Category APP = new Category("APP", "电竞APP");

    private static final List<Category> ORDER = List.of(APP);

    /** 已下线的内置分类 key */
    public static final Set<String> RETIRED_CATEGORY_KEYS = Set.of("PVP", "WEB", "OTHER");

    /** 没有手动分类的项目一律归到电竞APP */
    public static String categoryKeyOf(String projectCode) {
        return APP.key();
    }

    public static Category categoryOf(String projectCode) {
        return categoryOf(projectCode, null);
    }

    public static Category categoryOf(String projectCode, String storedCategory) {
        String key = resolveCategoryKey(projectCode, storedCategory);
        return ORDER.stream().filter(c -> c.key().equals(key)).findFirst().orElse(APP);
    }

    /** 手动分类优先；未配置或是已下线的旧值时归到电竞APP */
    public static String resolveCategoryKey(String projectCode, String storedCategory) {
        if (storedCategory != null && !storedCategory.isBlank()) {
            String k = storedCategory.trim();
            String upper = k.toUpperCase(Locale.ROOT);
            if (RETIRED_CATEGORY_KEYS.contains(upper)) {
                return APP.key();
            }
            for (Category c : ORDER) {
                if (c.key().equals(upper)) {
                    return c.key();
                }
            }
            return k;
        }
        return categoryKeyOf(projectCode);
    }

    public static boolean isBuiltinCategory(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return false;
        }
        String upper = categoryKey.trim().toUpperCase(Locale.ROOT);
        return ORDER.stream().anyMatch(c -> c.key().equals(upper));
    }

    /** 树节点展示名：内置分类用中文标签，自定义分类原样展示 */
    public static String categoryDisplayLabel(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return APP.label();
        }
        String upper = categoryKey.trim().toUpperCase(Locale.ROOT);
        for (Category c : ORDER) {
            if (c.key().equals(upper)) {
                return c.label();
            }
        }
        return categoryKey.trim();
    }

    public static List<Category> categoriesInOrder() {
        return ORDER;
    }

    public static List<Map<String, Object>> categoriesVo() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Category c : ORDER) {
            out.add(Map.of("key", c.key(), "label", c.label()));
        }
        return out;
    }

    /** APP 分类下推荐的项目编码顺序（含尚未入库的占位） */
    public static List<String> appProjectSlots() {
        List<String> slots = new ArrayList<>(ApiAppCatalog.groups().get(0).projectCodes());
        slots.add("app-global-data");
        return slots;
    }

    /**
     * 树节点展示名：{@code app-activity} → {@code APP-ACTIVITY}。
     */
    public static String projectDisplayLabel(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return "";
        }
        String raw = projectCode.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if ("android-domestic-esports".equals(lower)) {
            return "PVP · Android 国内";
        }
        if ("ios-domestic-esports".equals(lower)) {
            return "PVP · iOS 国内";
        }
        if ("harmonyos-domestic-esports".equals(lower)) {
            return "PVP · 鸿蒙 国内";
        }
        if ("android-overseas-pogpog".equals(lower)) {
            return "PVP · Android 海外";
        }
        if ("ios-overseas-pogpog".equals(lower)) {
            return "PVP · iOS 海外";
        }
        String canon = ApiAppCatalog.canonicalCode(raw);
        if (canon.startsWith("app-")) {
            return "APP-" + canon.substring(4).toUpperCase(Locale.ROOT);
        }
        return raw;
    }

    /** 某分类下固定展示的项目槽位 */
    public static List<String> slotsOfCategory(String categoryKey) {
        if (APP.key().equals(categoryKey)) {
            return appProjectSlots();
        }
        return List.of();
    }

    /**
     * 把 flat 项目列表按分类分桶；APP 内按 catalog 顺序 + 其余字母序。
     */
    public static Map<String, List<Map<String, Object>>> bucketProjects(
            List<Map<String, Object>> projectRows) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Category c : ORDER) {
            buckets.put(c.key(), new ArrayList<>());
        }
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : projectRows) {
            String code = String.valueOf(row.get("code"));
            byCode.put(code, row);
            String catKey = resolveCategoryKey(code, row.get("category") != null ? String.valueOf(row.get("category")) : null);
            buckets.computeIfAbsent(catKey, k -> new ArrayList<>()).add(row);
        }
        // APP：先按 catalog 槽位，再补未在槽位里的
        List<Map<String, Object>> appSorted = new ArrayList<>();
        for (String slot : appProjectSlots()) {
            Map<String, Object> hit = byCode.get(slot);
            if (hit != null && categoryKeyOf(slot).equals(APP.key())) {
                appSorted.add(hit);
            }
        }
        for (Map<String, Object> row : buckets.get(APP.key())) {
            if (!appSorted.contains(row)) {
                appSorted.add(row);
            }
        }
        buckets.put(APP.key(), appSorted);
        return buckets;
    }
}
