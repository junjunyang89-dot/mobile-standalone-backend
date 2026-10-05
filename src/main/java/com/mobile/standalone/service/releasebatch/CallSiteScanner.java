package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apitest.CallerRefScanner;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 找代码里「调了哪个接口」：前端 / 客户端里写着接口路径的字符串，后端里的 Feign 和 http 调用。
 * <p>
 * 只认字符串字面量。{@code baseUrl + '/x/' + id} 这种拼出来的只能认出带斜杠的那一段，
 * 拿去和接口比时允许前面多一两段网关前缀（见 {@link CrossProjectLinker}）。
 */
public final class CallSiteScanner {

    /** 一个项目要看哪些代码 */
    public enum Scope {
        /** 后端仓库：Java 里的远程调用 + 仓库里自带的前端（如 app-admin/frontend） */
        BACKEND,
        FRONTEND,
        CLIENT
    }

    public static final String FRONT = "FRONT";
    public static final String BACK = "BACK";
    public static final String CLIENT = "CLIENT";

    static final Set<String> FRONT_EXTS = Set.of("js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "ets");
    static final Set<String> CLIENT_EXTS = Set.of("java", "kt", "swift", "m", "mm", "h", "dart", "ets", "js", "ts");
    private static final Set<String> BACK_EXTS = Set.of("java", "kt");

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".svn", ".vscode", "node_modules", "dist", "unpackage", "build", "target", "out",
            "Pods", "DerivedData", ".nuxt", ".next", "coverage", "test", "tests", "__tests__", "androidTest",
            "mock", "mocks", "oh_modules", "vendor", "libs");

    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_FILES = 100_000;

    private static final Pattern LITERAL = Pattern.compile("([\"'`])([^\"'`\\s]{3,300})\\1");
    private static final Pattern STATIC_EXT = Pattern.compile(
            "\\.(png|jpe?g|gif|svg|webp|ico|bmp|css|scss|less|sass|styl|js|mjs|ts|tsx|jsx|vue|html?|json|map|"
                    + "woff2?|ttf|eot|otf|mp3|mp4|wav|webm|pdf|zip|txt|md|xml|yml|yaml|swift|kt|java)$");
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9_\\-.{}~$]+");
    private static final Pattern DATE_SEGMENT = Pattern.compile("^(y+|m+|d+|h+|s+)$");
    private static final Pattern MIME = Pattern.compile(
            "(?i)(application|text|image|audio|video|multipart|font)/[\\w.+\\-*]+(;.*)?");
    private static final Pattern SKIP_LINE = Pattern.compile(
            "(?i)(^\\s*(import\\s|//|\\*|/\\*|<!--)|\\brequire\\s*\\(|\\bfrom\\s+[\"']|\\$router|router\\.(push|replace)|"
                    + "<router-link|\\bredirect\\s*:|\\bto=\"|:to=|\\burl\\s*\\(|\\bimport\\s*\\()");
    private static final Pattern ROUTER_FILE = Pattern.compile("(?i)(^|/)routers?(/|\\.)");
    private static final Pattern API_FILE = Pattern.compile(
            "(?i)(^|/)(api|apis|services?|requests?|http|network|net|remote)(/|\\.[a-z]+$)");
    private static final Pattern API_LINE = Pattern.compile(
            "(?i)(axios|request|fetch|http|\\$(get|post)|\\.(get|post|put|delete|patch)\\s*[(<]|\\burl\\s*[:=]|"
                    + "@(GET|POST|PUT|DELETE|PATCH)\\s*\\(|\\bapi)");
    private static final Pattern FEIGN_PATH = Pattern.compile("@FeignClient\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern ATTR_PATH = Pattern.compile("\\bpath\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"[^)]*\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:public\\s+)?interface\\b");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(([^)]*)\\)");
    private static final Pattern FIRST_STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern HTTP_IN_JAVA = Pattern.compile("(?i)(https?|lb)://");

    private CallSiteScanner() {
    }

    /** 这个文件在这个项目里算哪一侧；不看的文件返回 null */
    static String sideOf(String rel, Scope scope) {
        String ext = extOf(rel);
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.contains(".min.") || lower.contains("src/main/resources/")) {
            return null;
        }
        return switch (scope) {
            case BACKEND -> FRONT_EXTS.contains(ext) ? FRONT : BACK_EXTS.contains(ext) ? BACK : null;
            case FRONTEND -> FRONT_EXTS.contains(ext) ? FRONT : null;
            case CLIENT -> CLIENT_EXTS.contains(ext) ? CLIENT : null;
        };
    }

    /** 相对路径 → 哪一侧；跳过依赖、产物、测试目录 */
    static List<String[]> listFiles(Path root) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        Path base = root.toAbsolutePath().normalize();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                if (out.size() >= MAX_FILES) {
                    throw new IllegalArgumentException("代码文件超过 " + MAX_FILES + " 个，是不是选错了目录：" + base);
                }
                out.add(new String[]{base.relativize(file).toString().replace('\\', '/'), file.toString()});
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** 整个仓库里的调用点 */
    public static List<CallSite> scanRepo(Path root, Scope scope) throws IOException {
        List<CallSite> out = new ArrayList<>();
        for (String[] f : listFiles(root)) {
            String side = sideOf(f[0], scope);
            if (side == null) {
                continue;
            }
            String src = read(Path.of(f[1]));
            if (src != null) {
                out.addAll(extract(src, f[0], side));
            }
        }
        return out;
    }

    /** 一个文件里的调用点 */
    public static List<CallSite> extract(String src, String relFile, String side) {
        if (src == null || src.isEmpty()) {
            return List.of();
        }
        if (BACK.equals(side)) {
            return extractJava(src, relFile);
        }
        boolean routerFile = ROUTER_FILE.matcher(relFile).find();
        boolean apiFile = API_FILE.matcher(relFile).find();
        List<CallSite> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf('/') < 0 || SKIP_LINE.matcher(line).find()
                    || routerFile && line.contains("path")) {
                continue;
            }
            Matcher m = LITERAL.matcher(line);
            while (m.find()) {
                String raw = m.group(2);
                String path = acceptPath(raw);
                if (path == null || !seen.add(path + "#" + (i + 1))) {
                    continue;
                }
                out.add(site(relFile, i + 1, path, raw, line, side, apiFile || API_LINE.matcher(line).find()));
            }
        }
        return out;
    }

    /**
     * Java 里只认真正的远程调用：Feign 接口上的映射（拼上类上的 path），
     * 以及写着 http:// / lb:// 的地址。自己的 Controller 映射不算调用。
     */
    static List<CallSite> extractJava(String src, String relFile) {
        List<CallSite> out = new ArrayList<>();
        int[] starts = lineStarts(src);
        if (src.contains("@FeignClient")) {
            String prefix = "";
            Matcher fm = FEIGN_PATH.matcher(src);
            if (fm.find()) {
                Matcher pm = ATTR_PATH.matcher(fm.group(1));
                if (pm.find()) {
                    prefix = pm.group(1);
                }
            }
            Matcher cm = CLASS_MAPPING.matcher(src);
            if (cm.find()) {
                prefix = join(prefix, cm.group(1));
            }
            Matcher mm = METHOD_MAPPING.matcher(src);
            while (mm.find()) {
                if (cm.find(0) && mm.start() == cm.start()) {
                    continue;
                }
                Matcher s = FIRST_STRING.matcher(mm.group(2));
                String p = s.find() ? s.group(1) : "";
                String full = join(prefix, p);
                String path = acceptPath(full);
                if (path != null) {
                    int line = lineOf(starts, mm.start());
                    out.add(site(relFile, line, path, full, lineText(src, starts, line), BACK, true));
                }
            }
            return out;
        }
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!HTTP_IN_JAVA.matcher(line).find() || line.contains("Mapping(")) {
                continue;
            }
            Matcher m = LITERAL.matcher(line);
            while (m.find()) {
                String path = acceptPath(m.group(2));
                if (path != null) {
                    out.add(site(relFile, i + 1, path, m.group(2), line, BACK, true));
                }
            }
        }
        return out;
    }

    /** 像接口路径就返回归一后的路径，否则 null */
    static String acceptPath(String raw) {
        if (raw == null || raw.indexOf('/') < 0) {
            return null;
        }
        String r = raw.trim();
        if (r.startsWith("./") || r.startsWith("../") || r.startsWith("@/") || r.startsWith("~")
                || r.startsWith("data:") || r.startsWith("mailto:") || r.contains("*") || r.contains("<")
                || r.contains(">") || r.contains("\\") || r.contains(" ")) {
            return null;
        }
        if (r.startsWith("//") && !r.startsWith("///") || MIME.matcher(r).matches()) {
            return null;
        }
        String n = CallerRefScanner.normalize(r);
        if (STATIC_EXT.matcher(n).find()) {
            return null;
        }
        String[] segs = n.substring(1).split("/");
        int real = 0;
        int dateLike = 0;
        for (String s : segs) {
            if (s.isEmpty()) {
                continue;
            }
            if (!SEGMENT.matcher(s).matches()) {
                return null;
            }
            if (DATE_SEGMENT.matcher(s).matches()) {
                dateLike++;
            }
            if (!"{}".equals(s)) {
                real++;
            }
        }
        if (real < 2 || dateLike >= 2) {
            return null;
        }
        return n.length() > 1 && n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
    }

    private static CallSite site(String file, int line, String path, String raw, String code, String side, boolean apiLike) {
        CallSite c = new CallSite();
        c.setFile(file);
        c.setLine(line);
        c.setPath(path);
        c.setRaw(raw);
        String t = code == null ? "" : code.trim();
        c.setCode(t.length() > 160 ? t.substring(0, 160) + "…" : t);
        c.setSide(side);
        c.setApiLike(apiLike);
        return c;
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty()) {
            return y;
        }
        if (y.isEmpty()) {
            return x;
        }
        return (x.endsWith("/") ? x.substring(0, x.length() - 1) : x) + (y.startsWith("/") ? y : "/" + y);
    }

    static String read(Path p) {
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            String v = s.replace("\r\n", "\n").replace("\r", "\n");
            return v.isEmpty() || v.charAt(0) != '\uFEFF' ? v : v.substring(1);
        } catch (Exception e) {
            return null;
        }
    }

    static String extOf(String name) {
        int slash = name.lastIndexOf('/');
        int i = name.lastIndexOf('.');
        return i < 0 || i < slash ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static int[] lineStarts(String src) {
        List<Integer> s = new ArrayList<>();
        s.add(0);
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                s.add(i + 1);
            }
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    private static String lineText(String src, int[] starts, int line) {
        int from = starts[line - 1];
        int to = line < starts.length ? starts[line] : src.length();
        return src.substring(from, to).replace("\n", "");
    }
}
