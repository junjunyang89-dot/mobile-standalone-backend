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
            // 忘记密码的人本来就登录不了，这两个必须公开。
            // 它们自身的防护是恒定响应 + 三档限流 + 验证码限次，不是靠鉴权
            "/auth/forgot-password", "/auth/reset-password",
            // 静态图片资源：报告里的截图是直接写进 <img src> 的，浏览器发这种请求时
            // 加不上任何请求头，拦下来的结果是所有报告的图全变成裂图。
            // 这些路径本身是不可猜的（任务 id + 时间戳），而真正要守的是接口那一层
            "/tmp/screenshots", "tmp/screenshots", "/ui-element-previews",
            "/handbook-images", "/agent-uploads", "/bug-images", "/issue-images");

    /**
     * 任务步骤截图也是写进 &lt;img src&gt; 的，带不上 Authorization。
     * 只放行具体的 PNG 地址，不放行 execution-log / screenshot-list。
     */
    private static boolean isPublicTaskScreenshot(String path) {
        if (!path.startsWith("/functional-test/tasks/")) {
            return false;
        }
        String rest = path.substring("/functional-test/tasks/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String tail = rest.substring(slash + 1);
        if ("screenshot".equals(tail)) {
            return true;
        }
        if (tail.startsWith("screenshots/")) {
            String idx = tail.substring("screenshots/".length());
            return !idx.isEmpty() && idx.chars().allMatch(Character::isDigit);
        }
        if (tail.startsWith("performance-screenshots/")) {
            String idx = tail.substring("performance-screenshots/".length());
            return !idx.isEmpty() && idx.chars().allMatch(Character::isDigit);
        }
        return false;
    }

    /**
     * 长得像写、其实是查的 POST。
     * <p>
     * 不放行的话只读用户点不开「接口对比分析」——而那恰恰是只读这一档最该看的东西。
     */
    private static final List<String> READ_ONLY_POST_SUFFIXES = List.of(
            "/analyze", "/analyze-dirs", "/diff", "/preview", "/preview-order",
            "/import/parse-text", "/inspector-xml/parse", "/resolve-dependencies",
            "/table-schemas/preview", "/ask");

    /**
     * 登录后人人可用的自助操作，不看角色。
     * <p>
     * 改自己的密码是 POST，按方法判会被划进「测试」档——那样只读用户就永远改不了自己的密码，
     * 只能去找管理员重置。这不是权限，是障碍。
     */
    private static final List<String> SELF_SERVICE = List.of(
            "/auth/me", "/auth/change-password", "/auth/logout",
            // 绑邮箱是每个人自己的事，只读账号也得能绑——不然他们永远只能找管理员重置
            "/auth/bind-email/code", "/auth/bind-email/confirm");

    /** 只有管理员能碰的前缀：账号管理本身，以及节点/密钥这类改了会影响所有人的配置 */
    private static final List<String> ADMIN_ONLY_PREFIXES = List.of(
            "/platform-users", "/worker-nodes");

    /**
     * 路由 → 最低角色。前端菜单据此过滤。
     * <p>
     * 没列出来的路由默认 {@link PlatformRole#VIEWER}——菜单是给人找路的，
     * 默认藏起来只会让人以为功能没了然后来问你。真正的边界在接口那一层。
     */
    private static final Map<String, PlatformRole> ROUTE_MIN_ROLE = Map.ofEntries(
            Map.entry("/platform-users", PlatformRole.ADMIN),
            Map.entry("/devices", PlatformRole.TESTER),
            Map.entry("/ui-apps", PlatformRole.TESTER),
            Map.entry("/app-online-regression", PlatformRole.TESTER),
            Map.entry("/app-automation", PlatformRole.TESTER),
            Map.entry("/ui-flows", PlatformRole.TESTER),
            Map.entry("/ui-elements", PlatformRole.TESTER),
            Map.entry("/ui-inspector-import", PlatformRole.TESTER),
            Map.entry("/api-environments", PlatformRole.TESTER),
            Map.entry("/api-schedules", PlatformRole.TESTER),
            // 抓包会改手机的代理设置，只读账号不该能碰真机
            Map.entry("/packet-capture", PlatformRole.TESTER));

    /**
     * 路径里出现 {@code .} / {@code ..} 段、反斜杠、或解码后仍残留的 {@code %}——正常业务路径不会有，一律拒绝。
     * <p>
     * <b>为什么是「拒绝」而不是「归一化后再判断」</b>：filter 归一化一次、Spring 路由再归一化一次，
     * 只要两次结果有一点点不同，就又是一个绕过口子。而 filter 是这套东西唯一的权限边界——
     * controller 里没有任何角色校验。对齐两套归一化很难保证，拒绝则不需要保证什么。
     * <p>
     * 具体绕过长这样：{@code POST /api/health/../platform-users} 带 {@code role: ADMIN}。
     * filter 看到 {@code /health/../platform-users}，命中 {@code /health} 前缀直接放行；
     * DispatcherServlet 归一化之后打到建账号接口——不用登录就造出一个管理员。
     */
    public static boolean looksTraversal(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // 解码之后还剩 % 说明是双重编码（%252e），正常路径不会有
        if (path.indexOf('\\') >= 0 || path.indexOf('%') >= 0) {
            return true;
        }
        for (String seg : path.split("/")) {
            if (".".equals(seg) || "..".equals(seg)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPublic(String path) {
        String p = normalize(path);
        return PUBLIC_PREFIXES.stream().anyMatch(pre -> matchesPrefix(p, pre))
                || isPublicTaskScreenshot(p);
    }

    /**
     * 按<b>路径段</b>匹配，不是裸 startsWith。
     * <p>
     * 裸 startsWith 的话，以后任何新增的 {@code /health-check}、{@code /platform-users-export}
     * 会自动继承 {@code /health}、{@code /platform-users} 的规则——前者变成免鉴权，后者可能反过来。
     * 这种口子加路由的人根本不会意识到自己开了它。
     */
    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
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
        if (ADMIN_ONLY_PREFIXES.stream().anyMatch(pre -> matchesPrefix(p, pre))) {
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
