package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端 APP → 后端项目 的分组。
 * <p>
 * 编码规则：<b>app-{gitlab 项目名}</b>，如 GitLab {@code seckill} → {@code app-seckill}。
 * 表里若还有 {@code data-center}、{@code app_esports_api} 等历史写法，{@link #canonicalCode} 会归一化。
 */
public final class ApiAppCatalog {

    private ApiAppCatalog() {
    }

    public record AppGroup(String key, String label, List<String> projectCodes) {
    }

    /**
     * 完美世界电竞 APP 下的 GitLab 后端项目（顺序即树里展示顺序）。
     * 展示编码统一为 app-{name}。
     */
    private static final List<String> WMPVP_GITLAB = List.of(
            "seckill", "video", "robot", "xiaomai", "activity", "activity-center",
            "admin", "community", "decoration", "engine", "event", "push", "ban", "user",
            "data-center", "data-agent", "data-provider", "esports-api", "global-agent",
            "message-center", "news-publish", "passport", "steam-web", "task-center",
            "vip-center", "wmpay", "wmpayplus");

    private static final List<String> WMPVP_CODES = canonicalList(WMPVP_GITLAB);

    private static final List<AppGroup> GROUPS = List.of(
            new AppGroup("wmpvp", "完美世界电竞", WMPVP_CODES),
            new AppGroup("pogpog", "PogPog 海外", List.of()));

    /** 历史/手工录入的编码 → 规范 app-{gitlab} */
    private static final Map<String, String> LEGACY_ALIASES = legacyAliases();

    private static List<String> canonicalList(List<String> gitlabNames) {
        List<String> out = new ArrayList<>();
        for (String g : gitlabNames) {
            out.add("app-" + g);
        }
        return List.copyOf(out);
    }

    private static Map<String, String> legacyAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String g : WMPVP_GITLAB) {
            String canon = "app-" + g;
            m.put(canon.toLowerCase(Locale.ROOT), canon);
            m.put(g.toLowerCase(Locale.ROOT), canon);
            m.put(canon.replace('-', '_').toLowerCase(Locale.ROOT), canon);
        }
        // 常见旧写法（项目管理里 code 与 GitLab 名不一致时）
        m.put("data-center", "app-data-center");
        m.put("activity-center", "app-activity-center");
        m.put("app_esports_api", "app-esports-api");
        m.put("app_activity-center", "app-activity-center");
        m.put("app_seckill", "app-seckill");
        m.put("app-task-cneter", "app-task-center");
        m.put("task-center", "app-task-center");
        m.put("app_task-center", "app-task-center");
        m.put("comment-service", "app-message-center");
        m.put("event-center", "app-event");
        m.put("steam-admin", "app-admin");
        m.put("partneragent", "app-partneragent");
        m.put("coupon", "app-coupon");
        m.put("mall-center", "app-mall-center");
        return Map.copyOf(m);
    }

    public static List<AppGroup> groups() {
        return GROUPS;
    }

    /** 任意项目编码 → 规范的 app-{gitlab}；认不出则原样 trim */
    public static String canonicalCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return "";
        }
        String raw = projectCode.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String hit = LEGACY_ALIASES.get(lower);
        if (hit != null) {
            return hit;
        }
        if (lower.startsWith("app-")) {
            return raw;
        }
        if (lower.startsWith("app_")) {
            return "app-" + raw.substring(4).replace('_', '-');
        }
        return "app-" + raw;
    }

    /** 项目编码 → 所属 APP */
    public static String appKeyOfProject(String projectCode) {
        String canon = canonicalCode(projectCode);
        for (AppGroup g : GROUPS) {
            if (g.projectCodes().stream().anyMatch(c -> c.equalsIgnoreCase(canon))) {
                return g.key();
            }
        }
        return "other";
    }

    public static List<Map<String, Object>> groupsVo() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppGroup g : GROUPS) {
            out.add(Map.of("key", g.key(), "label", g.label(), "projectCodes", g.projectCodes()));
        }
        out.add(Map.of("key", "other", "label", "其他后端", "projectCodes", List.of()));
        return out;
    }

    public static Map<String, List<String>> projectsByApp() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (AppGroup g : GROUPS) {
            map.put(g.key(), new ArrayList<>(g.projectCodes()));
        }
        map.put("other", new ArrayList<>());
        return map;
    }

    /** 把库里出现的 project 编码映射到树节点用的规范编码 */
    public static String treeProjectKey(String projectCode) {
        String canon = canonicalCode(projectCode);
        if ("wmpvp".equals(appKeyOfProject(canon))) {
            return canon;
        }
        return projectCode == null ? "" : projectCode.trim();
    }

    /**
     * 接口清单按项目筛选时，把历史别名一并带上。
     * 例如 {@code app-data-center} 应能查到 {@code data-center} 下的接口。
     */
    public static List<String> matchProjectCodes(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return List.of();
        }
        String raw = projectCode.trim();
        String canon = canonicalCode(raw);
        Set<String> codes = new LinkedHashSet<>();
        codes.add(raw);
        codes.add(canon);
        for (Map.Entry<String, String> e : LEGACY_ALIASES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(canon)) {
                codes.add(e.getKey());
                codes.add(e.getValue());
            }
        }
        return new ArrayList<>(codes);
    }
}
