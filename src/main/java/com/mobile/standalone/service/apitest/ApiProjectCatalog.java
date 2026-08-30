package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 项目管理页顶部分类：APP / PVP / WEB / 其他。
 * <p>
 * APP＝GitLab {@code app-*} 后端微服务；PVP＝电竞客户端（Android/iOS/鸿蒙）；WEB＝Web/H5 相关后端。
 */
public final class ApiProjectCatalog {

    private ApiProjectCatalog() {
    }

    public record Category(String key, String label) {
    }

    public static final Category APP = new Category("APP", "APP 后端");
    public static final Category PVP = new Category("PVP", "PVP · 客户端");
    public static final Category WEB = new Category("WEB", "WEB");
    public static final Category OTHER = new Category("OTHER", "其他");

    private static final List<Category> ORDER = List.of(APP, PVP, WEB, OTHER);

    /** 客户端 / 电竞端项目编码 */
    private static final Set<String> PVP_CODES = Set.of(
            "android-domestic-esports",
            "ios-domestic-esports",
            "harmonyos-domestic-esports",
            "android-overseas-pogpog",
            "ios-overseas-pogpog");

    /** Web / H5 相关 */
    private static final Set<String> WEB_CODES = Set.of(
            "app-steam-web",
            "steam-web",
            "steam-admin",
            "pogpog-h5",
            "overseas_service_host_and_tool_url");

    /**
     * 项目编码 → APP / PVP / WEB / OTHER。
     * APP 后端优先走 {@link ApiAppCatalog} 的 GitLab 清单与别名归一。
     */
    public static String categoryKeyOf(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return OTHER.key();
        }
        String raw = projectCode.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        if (PVP_CODES.contains(lower)) {
            return PVP.key();
        }
        if (WEB_CODES.contains(lower)) {
            return WEB.key();
        }

        // debug-* 是对比分析的演练项目，auth-* 是平台自己的初始化服务。
        // 这一段必须排在 APP 判断<b>前面</b>：canonicalCode 会把它们归一成
        // app-xxx，于是下面那个 APP 分支先命中，这一段永远执行不到——
        // 结果是平台自己的东西混进了「APP 后端」业务树，而代码里明明写着不该
        if (lower.startsWith("debug-") || lower.startsWith("auth-")) {
            return OTHER.key();
        }

        String canon = ApiAppCatalog.canonicalCode(raw);
        if ("wmpvp".equals(ApiAppCatalog.appKeyOfProject(canon))
                || lower.startsWith("app-")
                || lower.startsWith("app_")
                || canon.startsWith("app-")) {
            return APP.key();
        }
        if (lower.contains("esports") && (lower.contains("android") || lower.contains("ios"))) {
            return PVP.key();
        }
        return OTHER.key();
    }

    public static Category categoryOf(String projectCode) {
        return categoryOf(projectCode, null);
    }

    public static Category categoryOf(String projectCode, String storedCategory) {
        String key = resolveCategoryKey(projectCode, storedCategory);
        return ORDER.stream().filter(c -> c.key().equals(key)).findFirst().orElse(OTHER);
    }

    /** 手动分类优先；未配置时按编码规则推断 */
    public static String resolveCategoryKey(String projectCode, String storedCategory) {
        if (storedCategory != null && !storedCategory.isBlank()) {
            String k = storedCategory.trim();
            String upper = k.toUpperCase(Locale.ROOT);
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
            return OTHER.label();
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

    /** PVP 客户端占位（树里固定展示） */
    public static List<String> pvpProjectSlots() {
        return List.copyOf(PVP_CODES);
    }

    /** WEB 相关占位（树里固定展示） */
    public static List<String> webProjectSlots() {
        return List.copyOf(WEB_CODES);
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
        if (PVP.key().equals(categoryKey)) {
            return pvpProjectSlots();
        }
        if (WEB.key().equals(categoryKey)) {
            return webProjectSlots();
        }
        return List.of();
    }

    /**
     * 把 flat 项目列表分成四类；APP 内按 catalog 顺序 + 其余字母序。
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
