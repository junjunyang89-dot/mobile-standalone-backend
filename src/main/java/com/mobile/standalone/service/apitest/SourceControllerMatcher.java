package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 抓到的路径 → 仓库里声明它的那个 Controller 方法。
 * <p>
 * 抓包路径前面常带网关前缀（{@code /steamcn/community/task/signIn}），代码里只写到
 * {@code /community/task/signIn}——精确匹配永远对不上。所以按「整段后缀」认：
 * 代码路径必须是抓包路径末尾的完整几段，{@code /task/signIn} 不会误配到 {@code /xtask/signIn}。
 * 前缀越短越可信；同样短的命中了两个不同方法就不猜，交给人看。
 */
public final class SourceControllerMatcher {

    private SourceControllerMatcher() {
    }

    public enum Kind { EXACT, SUFFIX, AMBIGUOUS, NONE }

    public record Result(Kind kind, SourceEndpointScanner.Scanned hit, List<SourceEndpointScanner.Scanned> candidates) {
        static Result none() {
            return new Result(Kind.NONE, null, List.of());
        }
    }

    private record Scored(SourceEndpointScanner.Scanned s, int prefixSegments, int vars) {
    }

    public static Result match(String method, String capturedPath, List<SourceEndpointScanner.Scanned> scanned) {
        String path = normalize(capturedPath);
        if (path.isEmpty() || scanned == null || scanned.isEmpty()) {
            return Result.none();
        }
        String m = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase();
        List<Scored> hits = new ArrayList<>();
        for (SourceEndpointScanner.Scanned s : scanned) {
            if (!methodFits(m, s.httpMethod())) {
                continue;
            }
            String code = normalize(s.path());
            if (code.isEmpty() || "/".equals(code)) {
                continue;
            }
            int vars = countVars(code);
            var matcher = Pattern.compile("^((?:/[^/]+)*?)" + toRegex(code) + "$").matcher(path);
            if (matcher.matches()) {
                String prefix = matcher.group(1);
                int prefixSegments = prefix.isEmpty() ? 0 : prefix.split("/").length - 1;
                hits.add(new Scored(s, prefixSegments, vars));
            }
        }
        if (hits.isEmpty()) {
            return Result.none();
        }
        hits.sort(Comparator.comparingInt(Scored::prefixSegments).thenComparingInt(Scored::vars));
        Scored best = hits.get(0);
        List<SourceEndpointScanner.Scanned> tied = hits.stream()
                .filter(h -> h.prefixSegments() == best.prefixSegments() && h.vars() == best.vars())
                .map(Scored::s)
                .toList();
        if (tied.stream().map(t -> t.file() + "#" + t.handler()).distinct().count() > 1) {
            return new Result(Kind.AMBIGUOUS, null, tied);
        }
        return new Result(best.prefixSegments() == 0 ? Kind.EXACT : Kind.SUFFIX, best.s(), List.of(best.s()));
    }

    /** 网关前缀最多认几段：再长就不像前缀了，多半是两个不同的接口碰巧尾巴一样 */
    static final int MAX_GATEWAY_SEGMENTS = 2;

    /**
     * 抓包路径 = 网关前缀 + 代码路径 时，返回那段前缀（完全相同时是空串）；对不上返回 null。
     * <p>
     * 只有一段的代码路径（{@code /list}）不认前缀：项目里尾巴是 /list 的接口太多，认上了也是错认。
     */
    public static String gatewayPrefix(String capturedPath, String codePath) {
        String path = normalize(capturedPath);
        String code = normalize(codePath);
        if (path.isEmpty() || code.isEmpty() || "/".equals(code)) {
            return null;
        }
        var m = Pattern.compile("^((?:/[^/]+)*?)" + toRegex(code) + "$").matcher(path);
        if (!m.matches()) {
            return null;
        }
        String prefix = m.group(1);
        int segs = prefix.isEmpty() ? 0 : prefix.split("/").length - 1;
        if (segs > MAX_GATEWAY_SEGMENTS || segs > 0 && code.split("/").length - 1 < 2) {
            return null;
        }
        return prefix;
    }

    /**
     * 项目的网关前缀：已经认上的接口里占三分之二以上的那个；说不准返回 null。
     * 源码扫出来、还没抓过包的接口靠它补前缀，否则生成的用例直接打网关会 404。
     */
    public static String dominantPrefix(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return null;
        }
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        prefixes.forEach(p -> count.merge(p == null ? "" : p, 1, Integer::sum));
        var top = count.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow();
        return top.getValue() * 3 >= prefixes.size() * 2 ? top.getKey() : null;
    }

    /** 没写 method 的 @RequestMapping 是全部动词 */
    private static boolean methodFits(String captured, String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        String d = declared.trim().toUpperCase();
        return "ANY".equals(d) || "REQUEST".equals(d) || d.equals(captured);
    }

    static String normalize(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        s = s.replaceAll("/{2,}", "/");
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static int countVars(String code) {
        int n = 0;
        for (String seg : code.split("/")) {
            if (seg.startsWith("{") && seg.endsWith("}")) {
                n++;
            }
        }
        return n;
    }

    /** {@code /user/{id}} → 一段任意值；其余按字面 */
    private static String toRegex(String code) {
        StringBuilder sb = new StringBuilder();
        for (String seg : code.substring(1).split("/")) {
            sb.append('/');
            if (seg.startsWith("{") && seg.endsWith("}")) {
                sb.append("[^/]+");
            } else {
                sb.append(Pattern.quote(seg));
            }
        }
        return sb.toString();
    }
}
