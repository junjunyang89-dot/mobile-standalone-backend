package com.mobile.standalone.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能模块目录：管理员给账号勾「能看哪些入口」时的那张清单。
 * <p>
 * 一个模块就是侧栏里的一个入口，用它的路由当 key；分组和侧栏一致，管理员照着菜单勾就行。
 * 前端勾选框和 {@code /auth/me} 的隐藏路由都从这里取，不在前端另写一份。
 * <p>
 * 账号上存的是<b>允许清单</b>而不是禁止清单：以后新加的模块，受限账号默认看不到，
 * 要管理员再勾一次——反过来的话，每加一个页面所有受限账号都会悄悄多出一个入口。
 */
public final class PlatformModules {

    private PlatformModules() {
    }

    public record Module(String route, String label) {
    }

    public record Group(String label, List<Module> modules) {
    }

    /** 工作台是登录后的落地页，也是没权限时被送回去的地方，不能被勾掉 */
    public static final String HOME = "/home";

    private static final List<Group> GROUPS = List.of(
            new Group("主菜单", List.of(
                    new Module("/codewords-agent", "AI助手"),
                    new Module("/env-setup", "环境搭建"))),
            new Group("测试用例", List.of(
                    new Module("/bug-management", "Bug 管理"),
                    new Module("/issue-tracking", "线上问题跟踪"),
                    new Module("/test-cases", "测试用例库"))),
            new Group("资产管理", List.of(
                    new Module("/api-projects", "项目管理"),
                    new Module("/devices", "设备池"),
                    new Module("/ui-apps", "应用管理"),
                    new Module("/app-packs", "APP 打包"))),
            new Group("接口测试", List.of(
                    new Module("/api-collections", "用例集合"),
                    new Module("/api-endpoints", "接口管理"),
                    new Module("/api-environments", "环境变量"),
                    new Module("/api-reports", "执行报告"),
                    new Module("/api-changes", "接口对比分析"),
                    new Module("/api-changes-v2", "接口对比分析2"))),
            new Group("定时任务", List.of(
                    new Module("/api-schedules", "定时执行"))),
            new Group("APP 自动化", List.of(
                    new Module("/tasks", "APP测试任务"),
                    new Module("/ui-flows", "流程编排"),
                    new Module("/ui-elements", "元素管理"),
                    new Module("/ui-inspector-import", "Inspector XML 导入"),
                    new Module("/ui-keywords", "关键字库"),
                    new Module("/app-online-regression", "APP回归"),
                    new Module("/reports", "APP UI测试报告"))),
            new Group("测试工具", List.of(
                    new Module("/packet-capture", "抓包"),
                    new Module("/status-monitor", "状态监控"),
                    new Module("/redis-tool", "Redis 查询"),
                    new Module("/kafka-tool", "Kafka 查询"),
                    new Module("/incident-playbooks", "事故沉淀"),
                    new Module("/tracking-check", "埋点校验"))),
            new Group("性能测试", List.of(
                    new Module("/performance-baseline", "安卓性能测试"),
                    new Module("/reports/esports/performance", "性能报告"),
                    new Module("/special-report", "版本质量卡"),
                    new Module("/performance", "性能监控"))),
            new Group("知识手册", List.of(
                    new Module("/knowledge-handbook", "知识手册"))),
            new Group("APP发版", List.of(
                    new Module("/release-doc", "发版文档"),
                    new Module("/release-go-live", "上线看板"),
                    new Module("/version-archives", "版本需求归档"))),
            new Group("权限与设置", List.of(
                    new Module("/settings", "设置"))));

    private static final List<String> ROUTES = GROUPS.stream()
            .flatMap(g -> g.modules().stream())
            .map(Module::route)
            .toList();

    public static List<Group> groups() {
        return GROUPS;
    }

    public static List<String> routes() {
        return ROUTES;
    }

    /** 去掉目录里没有的、去重、按目录顺序排——前端传什么都不能原样落库 */
    public static List<String> sanitize(Collection<String> picked) {
        if (picked == null || picked.isEmpty()) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String p : picked) {
            if (p != null) {
                set.add(p.trim());
            }
        }
        List<String> out = new ArrayList<>();
        for (String r : ROUTES) {
            if (set.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 库里存逗号分隔；NULL = 不限制 */
    public static String serialize(Collection<String> picked) {
        return String.join(",", sanitize(picked));
    }

    /** @return null 表示不限制（存量账号都是这个状态） */
    public static List<String> parse(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.isBlank()) {
            return List.of();
        }
        return sanitize(List.of(stored.split(",")));
    }
}
