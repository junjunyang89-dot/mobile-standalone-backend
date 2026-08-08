package com.mobile.standalone.handbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识手册分类白名单（八大组 + 叶子 code；前后端约定同一套）。
 */
public final class HandbookCategories {

    private HandbookCategories() {
    }

    /** 旧版 code → 新版 code（启动时迁移 / 入参规范化） */
    public static final Map<String, String> LEGACY_ALIASES = Map.of(
            "shell", "linux",
            "automation", "auto_framework",
            "pitfall", "troubleshoot"
    );

    public static final List<Group> GROUPS = new ArrayList<>();
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        addGroup("business", "业务",
                entry("business", "业务经验"),
                entry("checklist", "测试规范 / 发布 checklist"));
        addGroup("client", "端侧",
                entry("app", "APP 经验"),
                entry("web", "Web / H5 经验"),
                entry("miniprogram", "小程序经验"),
                entry("harmony", "鸿蒙经验"),
                entry("mobile_os", "iOS / Android 系统经验"));
        addGroup("data", "接口与数据",
                entry("api", "接口经验"),
                entry("db", "数据库经验（MySQL / SQL）"),
                entry("redis", "Redis 经验"));
        addGroup("ops", "环境与运维",
                entry("linux", "Linux / Shell 经验"),
                entry("docker", "Docker 经验"),
                entry("k8s", "K8S 经验"),
                entry("jenkins", "Jenkins / CI/CD 经验"),
                entry("tomcat", "Tomcat / 部署经验"),
                entry("nginx", "Nginx 经验"),
                entry("env_setup", "环境搭建经验"));
        addGroup("quality", "质量与排障",
                entry("case_design", "用例设计经验"),
                entry("perf", "性能测试经验"),
                entry("flaky", "稳定性 / Flaky 经验"),
                entry("troubleshoot", "失败归因 / 排障经验"),
                entry("jvm_log", "JVM / 日志排障经验"),
                entry("packet", "抓包经验"),
                entry("security", "账号权限 / 安全基础"));
        addGroup("collab", "工程协作",
                entry("git", "Git 经验"),
                entry("defect_collab", "提测 / 缺陷协作经验"));
        addGroup("tools", "工具与速查",
                entry("tools", "常用工具汇总"),
                entry("cheatsheet", "常用命令 / 配置速查"));
        addGroup("automation", "自动化",
                entry("appium", "Appium 经验"),
                entry("playwright", "Playwright 经验"),
                entry("auto_framework", "自动化框架经验"));
    }

    public static final Set<String> CODES = LABELS.keySet();

    public static boolean isValid(String code) {
        return normalize(code) != null;
    }

    /**
     * 规范化为白名单 code；支持旧别名。无效则返回 null。
     */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toLowerCase();
        if (c.isEmpty()) {
            return null;
        }
        if (LEGACY_ALIASES.containsKey(c)) {
            c = LEGACY_ALIASES.get(c);
        }
        return CODES.contains(c) ? c : null;
    }

    public static String labelOf(String code) {
        String n = normalize(code);
        return n == null ? code : LABELS.get(n);
    }

    /** 供 /meta 返回的结构化分组 */
    public static List<Map<String, Object>> groupsForMeta() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Group g : GROUPS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("label", g.label());
            Map<String, String> cats = new LinkedHashMap<>();
            for (Entry e : g.entries()) {
                cats.put(e.code(), e.label());
            }
            m.put("categories", cats);
            out.add(m);
        }
        return out;
    }

    @SafeVarargs
    private static void addGroup(String id, String label, Entry... entries) {
        List<Entry> list = List.of(entries);
        GROUPS.add(new Group(id, label, list));
        for (Entry e : list) {
            LABELS.put(e.code(), e.label());
        }
    }

    private static Entry entry(String code, String label) {
        return new Entry(code, label);
    }

    public record Entry(String code, String label) {
    }

    public record Group(String id, String label, List<Entry> entries) {
    }
}
