package com.mobile.standalone.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
            "/handbook-images", "/agent-uploads", "/bug-images", "/issue-images",
            "/knowledge-note-images",
            // iOS 抓包装 CA：手机浏览器要直连下载，带不上登录态；证书本来就是给人装的
            "/packet-capture/mitm-ca",
            // 本机调试用的免登录查询。只放行 /open，列表/增删改仍要登录
            "/debug-tests/open");

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
            "/table-schemas/preview", "/ask", "/insight");

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
     * 用 POST 实现的<b>不可恢复</b>操作，提到 ADMIN。
     * <p>
     * 按 HTTP 方法分档有个漏洞：「DELETE 要 ADMIN」的前提是销毁都走 DELETE。
     * 可实际上批量销毁常常写成 POST——{@code /reimport} 会先删光一批用例再重导，
     * {@code /cleanup} 直接删文件。它们比很多 DELETE 更危险，却只要 TESTER。
     * <p>
     * 所以这份清单和 {@link #READ_ONLY_POST_SUFFIXES} 是对称的：那份把「其实只是读」的 POST 降到
     * VIEWER，这份把「其实是销毁」的 POST 升到 ADMIN。新增批量销毁类接口时要记得往这里加一条。
     */
    private static final List<String> ADMIN_POST_SUFFIXES = List.of(
            "/reimport", "/cleanup", "/clear", "/reset", "/truncate", "/purge");

    /**
     * 生产环境的写操作，一律提到 ADMIN。
     * <p>
     * 这些路径下改的是<b>所有人共用</b>的东西：生产 baseUrl、生产鉴权头、生产账号变量。
     * 一个人为了调自己那条用例把 PROD 的 baseUrl 指到预发，全组的线上巡检结果就静默变成假阳性，
     * 而且报表上看不出任何异常。
     * <p>
     * 更要命的是 secret：{@code EnvVariableService} 刻意不提供查看真值的接口，
     * 所以<b>覆盖即永久丢失</b>——没人说得出旧值是什么。
     */
    private static final List<String> PROD_WRITE_MARKERS = List.of(
            "/envs/prod", "/environments/prod");

    /**
     * 路由 → 最低角色。前端菜单据此过滤。
     * <p>
     * 没列出来的路由默认 {@link PlatformRole#VIEWER}——菜单是给人找路的，
     * 默认藏起来只会让人以为功能没了然后来问你。真正的边界在接口那一层。
     */
    private static final Map<String, PlatformRole> ROUTE_MIN_ROLE = Map.ofEntries(
            Map.entry("/platform-users", PlatformRole.ADMIN),
            Map.entry("/devices", PlatformRole.TESTER),
            Map.entry("/ui-recordings", PlatformRole.TESTER),
            Map.entry("/ui-apps", PlatformRole.TESTER),
            Map.entry("/app-packs", PlatformRole.TESTER),
            Map.entry("/app-online-regression", PlatformRole.TESTER),
            Map.entry("/app-automation", PlatformRole.TESTER),
            Map.entry("/ui-flows", PlatformRole.TESTER),
            Map.entry("/asr", PlatformRole.TESTER),
            Map.entry("/ui-elements", PlatformRole.TESTER),
            Map.entry("/ui-inspector-import", PlatformRole.TESTER),
            Map.entry("/api-environments", PlatformRole.TESTER),
            Map.entry("/api-schedules", PlatformRole.TESTER),
            // 抓包会改手机的代理设置，只读账号不该能碰真机
            Map.entry("/packet-capture", PlatformRole.TESTER),
            Map.entry("/status-monitor", PlatformRole.TESTER),
            Map.entry("/incident-playbooks", PlatformRole.TESTER),
            // 缓存和消息里常有 token、手机号；查询接口本身也是 POST，只读账号调不动
            Map.entry("/redis-tool", PlatformRole.TESTER),
            Map.entry("/kafka-tool", PlatformRole.TESTER),
            // 会用自己的 GitLab 令牌往 master 合代码
            Map.entry("/release-merge", PlatformRole.TESTER));

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
    /**
     * 这条路径是不是在动生产环境的配置。
     * <p>
     * 用<b>包含</b>而不是前缀：真实路径是 {@code /api-tests/envs/PROD/variables}，
     * 生产段夹在中间。大小写不敏感，因为 {@code TestEnvironment.normalize} 会把 prod/PROD 都归一。
     */
    static boolean isProdWritePath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(Locale.ROOT);
        for (String marker : PROD_WRITE_MARKERS) {
            if (lower.equals(marker) || lower.contains(marker + "/") || lower.endsWith(marker)) {
                return true;
            }
        }
        return false;
    }

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
     * 这个账号看不见的路由：角色不够的 + 管理员没给勾的模块。
     *
     * @param visibleModules {@link PlatformUser#visibleModules()}；null = 模块不限
     */
    public static List<String> hiddenRoutes(PlatformRole role, List<String> visibleModules) {
        Set<String> hidden = new LinkedHashSet<>();
        ROUTE_MIN_ROLE.forEach((route, min) -> {
            if (!role.satisfies(min)) {
                hidden.add(route);
            }
        });
        if (visibleModules != null) {
            Set<String> allowed = new HashSet<>(visibleModules);
            for (String r : PlatformModules.routes()) {
                if (!allowed.contains(r)) {
                    hidden.add(r);
                }
            }
        }
        return new ArrayList<>(hidden);
    }

    /**
     * 目录里这个账号<b>看得见</b>的模块路由。
     * <p>
     * 前端按「最长匹配」判断时要用到：{@code /reports} 被勾掉、{@code /reports/esports/performance} 勾上了，
     * 只给隐藏清单的话前缀一匹配，性能报告也跟着没了。
     */
    public static List<String> visibleRoutes(List<String> hiddenRoutes) {
        Set<String> hidden = new HashSet<>(hiddenRoutes);
        return PlatformModules.routes().stream().filter(r -> !hidden.contains(r)).toList();
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
        // 写生产环境配置要 ADMIN。只拦写操作：读（含掩码后的变量列表）照旧
        if (!"GET".equals(m) && !"HEAD".equals(m) && !"OPTIONS".equals(m)
                && isProdWritePath(p)) {
            return PlatformRole.ADMIN;
        }
        return switch (m) {
            // OPTIONS 是浏览器的预检，带不上令牌；拦掉的话所有跨域请求都发不出去
            case "GET", "HEAD", "OPTIONS" -> PlatformRole.VIEWER;
            // 删除是唯一不可回退的操作：改错了能改回来，删掉的一批元素和用例没有回退
            case "DELETE" -> PlatformRole.ADMIN;
            case "POST" -> {
                // 先判销毁再判只读：两份清单万一将来撞上同一个后缀，
                // 宁可误判成「要 ADMIN」也不能误判成「谁都能调」
                if (ADMIN_POST_SUFFIXES.stream().anyMatch(p::endsWith)) {
                    yield PlatformRole.ADMIN;
                }
                yield READ_ONLY_POST_SUFFIXES.stream().anyMatch(p::endsWith)
                        ? PlatformRole.VIEWER
                        : PlatformRole.TESTER;
            }
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
