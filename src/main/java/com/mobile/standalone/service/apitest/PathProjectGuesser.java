package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 猜抓到的接口归哪个项目。
 * <p>
 * 依次看：
 * <ol>
 *   <li>域名：appengine-testing.wmpvp.com 这种直连域名本身就点了名，同一路径在好几个仓库里都有时只能靠它分；</li>
 *   <li>网关路由：网关域名（如 gwapi-staging）后面挂着好几个服务，按 Apollo 里的 Path 路由找服务；</li>
 *   <li>路径第一段和项目编码对得上；</li>
 *   <li>清单里同前缀的接口多数在哪。</li>
 * </ol>
 */
public final class PathProjectGuesser {

    public record Guess(String project, String reason) {
    }

    /** 这些前缀各服务都在用，看不出归属 */
    private static final Set<String> GENERIC = Set.of(
            "api", "apis", "v1", "v2", "v3", "rest", "open", "openapi", "app", "gateway", "gw",
            "web", "h5", "m", "mobile", "inner", "internal", "public", "admin", "service", "services");

    /** 域名第一段的环境后缀：appengine-testing、api-staging、gwapi-qa */
    private static final Pattern ENV_SUFFIX = Pattern.compile("-(testing|test|staging|stage|qa|dev|pre|uat|fat|prod)$");

    private final Map<String, String> byName = new HashMap<>();
    /** 去掉 app 前缀后的名字：网关里叫 app-data-center，项目可能叫 data-center */
    private final Map<String, String> byBareName = new HashMap<>();
    private final Map<String, Map<String, Integer>> counts = new HashMap<>();
    /** 路径第一段（小写）→ 网关转发到的服务名 */
    private final Map<String, String> routes = new LinkedHashMap<>();

    public PathProjectGuesser(List<ApiProject> projects, List<ApiEndpoint> endpoints) {
        this(projects, endpoints, Map.of());
    }

    /**
     * @param projects  后端项目
     * @param endpoints 清单里的接口；待归类、已下线的不算
     * @param routes    网关路由：路径第一段 → 服务名（Nacos 里注册的 spring.application.name）
     */
    public PathProjectGuesser(List<ApiProject> projects, List<ApiEndpoint> endpoints, Map<String, String> routes) {
        for (ApiProject p : projects) {
            if (p.getCode() == null || p.getCode().isBlank()) {
                continue;
            }
            String code = ApiAppCatalog.treeProjectKey(p.getCode());
            byName.putIfAbsent(norm(p.getCode()), code);
            byBareName.putIfAbsent(bare(norm(p.getCode())), code);
            if (p.getName() != null && !p.getName().isBlank()) {
                byName.putIfAbsent(norm(p.getName()), code);
                byBareName.putIfAbsent(bare(norm(p.getName())), code);
            }
        }
        for (ApiEndpoint e : endpoints) {
            String seg = segment(e.getPath());
            if (seg == null || e.getProject() == null || CaptureCatalogService.INBOX.equals(e.getProject())
                    || e.isRetired()) {
                continue;
            }
            counts.computeIfAbsent(seg, k -> new HashMap<>()).merge(e.getProject(), 1, Integer::sum);
        }
        routes.forEach((prefix, service) -> {
            if (prefix != null && !prefix.isBlank() && service != null && !service.isBlank()) {
                this.routes.put(prefix.trim().replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT), service.trim());
            }
        });
    }

    /** 猜不出返回 null */
    public Guess guess(String path) {
        return guess(null, path);
    }

    /**
     * @param url 抓到的完整地址，没有就只按路径猜
     */
    public Guess guess(String url, String path) {
        String hostLabel = hostLabel(url);
        if (hostLabel != null) {
            String byHost = lookup(hostLabel);
            if (byHost != null) {
                return new Guess(byHost, "域名 " + hostOf(url) + " 和项目编码对得上");
            }
        }
        String first = firstSegment(path);
        String service = first == null ? null : routes.get(first);
        if (service != null) {
            String byRoute = lookup(norm(service));
            if (byRoute != null) {
                return new Guess(byRoute, "网关路由 /" + first + "/** 转到服务 " + service);
            }
        }
        String seg = segment(path);
        if (seg == null) {
            return null;
        }
        String named = byName.get(norm(seg));
        if (named != null) {
            return new Guess(named, "路径 /" + seg + "/ 和项目编码对得上");
        }
        Map<String, Integer> c = counts.get(seg);
        if (c == null || c.isEmpty()) {
            return null;
        }
        int total = c.values().stream().mapToInt(Integer::intValue).sum();
        Map.Entry<String, Integer> top = c.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        // 被放错的那几个也算在里面，所以要求明显多数
        if (top.getValue() < 2 || top.getValue() * 10 < total * 6) {
            return null;
        }
        return new Guess(top.getKey(),
                "清单里 /" + seg + "/ 开头的接口 " + top.getValue() + "/" + total + " 个在这个项目");
    }

    private String lookup(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String hit = byName.get(normalized);
        if (hit != null) {
            return hit;
        }
        String b = bare(normalized);
        return b.length() < 4 ? null : byBareName.get(b);
    }

    /** 域名第一段去掉环境后缀、归一化；通用名（api、gateway…）不算 */
    static String hostLabel(String url) {
        String host = hostOf(url);
        if (host == null || host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')) {
            return null;
        }
        String label = ENV_SUFFIX.matcher(host.split("\\.")[0].toLowerCase(Locale.ROOT)).replaceFirst("");
        if (GENERIC.contains(label) || label.length() < 4) {
            return null;
        }
        return norm(label);
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String s = url.contains("://") ? url : "https://" + url;
            return URI.create(s.replace(" ", "%20")).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstSegment(String path) {
        if (path == null) {
            return null;
        }
        for (String s : path.trim().split("/")) {
            if (!s.isBlank()) {
                return s.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    static String segment(String path) {
        String seg = firstSegment(path);
        return seg == null || GENERIC.contains(seg) || seg.contains("{") || seg.contains(".") ? null : seg;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String bare(String normalized) {
        return normalized.startsWith("app") ? normalized.substring(3) : normalized;
    }
}
