package com.mobile.standalone.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「这个请求要哪一档角色」——<b>唯一的一份</b>。
 * <p>
 * 前端菜单也从这里取（经 {@code /auth/me} 下发），不在前端另写一份。
 * 两份规则迟早对不上，而对不上的表现极其难查：后端拦了、前端没禁用，
 * 用户看到的是一个「操作失败」的红字，根本想不到是权限问题。
 * <p>
 * 规则本身按 HTTP 方法定，不逐个端点标：264 个端点逐个标注一定会漏，
 * 而漏掉的那个默认是放行还是拦死都不对。方法本身已经带了绝大部分信息——
 * 读、改、删本来就是三档，剩下的少数例外才单独列。
 */
public final class PermissionRules {

    private PermissionRules() {
    }

    /**
     * 不需要登录就能访问的。
     * <p>
     * 健康检查必须在里面：它是判断「服务活着没」的，如果它要登录，
     * 客户端连不上时就分不清是服务挂了还是令牌过期了。
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/auth/login", "/auth/bootstrap-status", "/health", "/actuator",
            // 静态图片资源：报告里的截图是直接写进 <img src> 的，浏览器发这种请求时
            // 加不上任何请求头，拦下来的结果是所有报告的图全变成裂图。
            // 这些路径本身是不可猜的（任务 id + 时间戳），而真正要守的是接口那一层
            "/tmp/screenshots", "tmp/screenshots", "/ui-element-previews",
            "/handbook-images", "/agent-uploads");

    /**
     * 长得像写、其实是查的 POST。
     * <p>
     * 不放行的话只读用户点不开「接口对比分析」——而那恰恰是只读这一档最该看的东西。
     */
    private static final List<String> READ_ONLY_POST_SUFFIXES = List.of(
            "/analyze", "/analyze-dirs", "/diff", "/preview", "/preview-order",
            "/import/parse-text", "/inspector-xml/parse", "/resolve-dependencies",
            "/table-schemas/preview");

    /**
     * 登录后人人可用的自助操作，不看角色。
     * <p>
     * 改自己的密码是 POST，按方法判会被划进「测试」档——那样只读用户就永远改不了自己的密码，
     * 只能去找管理员重置。这不是权限，是障碍。
     */
    private static final List<String> SELF_SERVICE = List.of(
            "/auth/me", "/auth/change-password", "/auth/logout");

    /** 只有管理员能碰的前缀：账号管理本身，以及节点/密钥这类改了会影响所有人的配置 */
    private static final List<String> ADMIN_ONLY_PREFIXES = List.of(
            "/platform-users", "/worker-nodes");

    /**
     * 路由 → 最低角色。前端菜单据此过滤。
     * <p>
     * 没列出来的路由默认 {@link PlatformRole#VIEWER}——菜单是给人找路的，
     * 默认藏起来只会让人以为功能没了然后来问你。真正的边界在接口那一层。
     */
    private static final Map<String, PlatformRole> ROUTE_MIN_ROLE = Map.of(
            "/platform-users", PlatformRole.ADMIN,
            "/devices", PlatformRole.TESTER,
            "/ui-apps", PlatformRole.TESTER,
            "/app-online-regression", PlatformRole.TESTER,
            "/ui-flows", PlatformRole.TESTER,
            "/ui-elements", PlatformRole.TESTER,
            "/ui-inspector-import", PlatformRole.TESTER,
            "/api-environments", PlatformRole.TESTER,
            "/api-schedules", PlatformRole.TESTER);

    public static boolean isPublic(String path) {
        String p = normalize(path);
        return PUBLIC_PREFIXES.stream().anyMatch(p::startsWith);
    }

    /** 这个路由至少要哪一档才看得见 */
    public static PlatformRole minRoleForRoute(String route) {
        return ROUTE_MIN_ROLE.getOrDefault(normalize(route), PlatformRole.VIEWER);
    }

    public static Map<String, PlatformRole> routeRules() {
        return ROUTE_MIN_ROLE;
    }

    /**
     * 这个请求要哪一档。
     *
     * @param method HTTP 方法
     * @param path   已去掉 context-path 的路径
     */
    public static PlatformRole required(String method, String path) {
        String p = normalize(path);
        String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);

        if (SELF_SERVICE.contains(p)) {
            return PlatformRole.VIEWER;
        }
        if (ADMIN_ONLY_PREFIXES.stream().anyMatch(p::startsWith)) {
            return PlatformRole.ADMIN;
        }
        return switch (m) {
            // OPTIONS 是浏览器的预检，带不上令牌；拦掉的话所有跨域请求都发不出去
            case "GET", "HEAD", "OPTIONS" -> PlatformRole.VIEWER;
            // 删除是唯一不可回退的操作：改错了能改回来，删掉的一批元素和用例没有回退
            case "DELETE" -> PlatformRole.ADMIN;
            case "POST" -> READ_ONLY_POST_SUFFIXES.stream().anyMatch(p::endsWith)
                    ? PlatformRole.VIEWER
                    : PlatformRole.TESTER;
            default -> PlatformRole.TESTER;
        };
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        // 尾斜杠不统一会让 /platform-users/ 绕过 /platform-users 的管理员限制
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
