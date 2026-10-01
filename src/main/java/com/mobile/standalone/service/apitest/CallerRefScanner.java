package com.mobile.standalone.service.apitest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在客户端 / H5 / 其他服务的源码里找「谁在调这个接口」。
 * <p>
 * 抓包要先知道去哪个页面点；找不到调用方的接口多半已经下线，
 * 与其写用例不如先确认要不要删。
 * <p>
 * 只认字符串字面量里的路径，拼出来的（baseUrl + 变量）找不到；
 * JS 里路径只写在一个导出函数里、而这个函数没人 import 的，也算没人调。
 */
public final class CallerRefScanner {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".svn", "node_modules", "dist", "unpackage", "build", "target", "out",
            "Pods", "DerivedData", ".nuxt", ".next", "coverage", "test", "tests", "__tests__", "androidTest",
            "mock", "mocks", "oh_modules");

    private static final Set<String> CODE_EXTS = Set.of(
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "java", "kt", "swift", "m", "mm", "h", "ets", "dart", "go", "py");

    private static final Set<String> JS_EXTS = Set.of("js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "ets");

    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_FILES = 300_000;
    private static final int MAX_REFS_PER_PATH = 20;
    private static final int MAX_USED_BY = 5;

    private static final Pattern LITERAL = Pattern.compile("([\"'`])([^\"'`\\s]{3,300})\\1");
    /** request({ url: '/x' }) 里的键名不是封装名 */
    private static final Set<String> GENERIC_KEYS = Set.of(
            "url", "path", "uri", "api", "href", "src", "link", "endpoint", "action", "baseurl", "route",
            "name", "method", "type", "value", "key");
    private static final Pattern HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+");
    /** Spring 的 ${ai.translate.url:/appdatacenter/ai/aiTranslate}：默认值就是路径 */
    private static final Pattern SPRING_DEFAULT = Pattern.compile("^\\$\\{[^}:]*:([^}]*)}$");
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\$\\{[^}]*}|\\{[^}/]*}|\\\\\\([^)]*\\)|%[sd@]");
    /** Retrofit 的 {baseUrl}/appdatacenter/...、Swift 的 \(host)/... */
    private static final Pattern LEADING_VAR = Pattern.compile("^(?:/?\\{})+");
    /** 路径所在的 JS 封装函数 / 常量名 */
    private static final Pattern JS_OWNER = Pattern.compile(
            "(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)"
                    + "|(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=");
    private static final Pattern JS_KEY = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*:\\s*[\"'`]");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    private CallerRefScanner() {
    }

    /**
     * @param repo   仓库名（搜索目录下的第一层目录）
     * @param via    路径写在哪个 JS 函数 / 常量里，找不到为 null
     * @param usedBy {@code via} 在同仓库其他文件里出现的位置
     * @param unused 写了路径但封装它的函数没人用——等于没人调
     */
    public record Ref(String repo, String file, int line, String code, String via, List<String> usedBy, boolean unused) {
    }

    /**
     * @param roots   搜哪些目录，一般是后端仓库的上一级（所有仓库并排放着）
     * @param exclude 接口自己所在的仓库，它自己的 Controller 不算调用方
     * @return 接口路径 → 调用位置；没找到的路径不在 map 里
     */
    public static Map<String, List<Ref>> scan(Collection<String> endpointPaths, List<Path> roots, Path exclude)
            throws IOException {
        Index index = new Index(endpointPaths);
        Path ex = exclude == null ? null : exclude.toAbsolutePath().normalize();
        Map<String, List<Draft>> found = new LinkedHashMap<>();
        Map<String, List<Path>> jsFilesByRepo = new HashMap<>();
        int[] files = {0};

        for (Path r : roots) {
            if (r == null || !Files.isDirectory(r)) {
                continue;
            }
            Path base = r.toAbsolutePath().normalize();
            boolean baseIsRepo = Files.isDirectory(base.resolve(".git"));
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (ex != null && dir.equals(ex)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(base) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    String ext = extOf(name);
                    if (!CODE_EXTS.contains(ext) || name.contains(".min.") || attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++files[0] > MAX_FILES) {
                        throw new IllegalArgumentException("调用方搜索范围里的代码文件超过 " + MAX_FILES + " 个，是不是选错了目录");
                    }
                    Path rel = base.relativize(file);
                    String repo = baseIsRepo || rel.getNameCount() < 2
                            ? base.getFileName().toString() : rel.getName(0).toString();
                    String relFile = (baseIsRepo || rel.getNameCount() < 2 ? rel : rel.subpath(1, rel.getNameCount()))
                            .toString().replace('\\', '/');
                    boolean js = JS_EXTS.contains(ext);
                    if (js) {
                        jsFilesByRepo.computeIfAbsent(repo, k -> new ArrayList<>()).add(file);
                    }
                    String src;
                    try {
                        src = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    collect(src, ext, js, repo, relFile, file, index, found);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        resolveUsages(found, jsFilesByRepo);

        Map<String, List<Ref>> out = new LinkedHashMap<>();
        found.forEach((path, drafts) -> out.put(path, drafts.stream().map(Draft::toRef).toList()));
        return out;
    }

    private static void collect(String src, String ext, boolean js, String repo, String relFile, Path abs,
                                Index index, Map<String, List<Draft>> found) {
        // 其他服务自己也可能声明同名路径的接口，那是它的接口不是调用；Feign 客户端例外
        boolean skipMappingLines = "java".equals(ext) || "kt".equals(ext);
        boolean feign = skipMappingLines && src.contains("@FeignClient");
        int[] lineStarts = null;
        Matcher m = LITERAL.matcher(src);
        while (m.find()) {
            String raw = m.group(2);
            if (raw.indexOf('/') < 0) {
                continue;
            }
            List<String> paths = index.match(raw);
            if (paths.isEmpty()) {
                continue;
            }
            if (lineStarts == null) {
                lineStarts = lineStarts(src);
            }
            int lineNo = lineOf(lineStarts, m.start());
            String lineText = lineText(src, lineStarts, lineNo);
            if (skipMappingLines && !feign && lineText.contains("Mapping(")) {
                continue;
            }
            String via = js ? jsOwner(src, lineStarts, lineNo, lineText) : null;
            for (String p : paths) {
                List<Draft> list = found.computeIfAbsent(p, k -> new ArrayList<>());
                if (list.size() >= MAX_REFS_PER_PATH) {
                    continue;
                }
                list.add(new Draft(repo, relFile, abs, lineNo, abbreviate(lineText.trim()), via));
            }
        }
    }

    /** JS 封装函数有没有被同仓库别的文件用到：一次扫描，只看要查的那些名字 */
    private static void resolveUsages(Map<String, List<Draft>> found, Map<String, List<Path>> jsFilesByRepo) {
        Map<String, Set<String>> wantedByRepo = new HashMap<>();
        for (List<Draft> drafts : found.values()) {
            for (Draft d : drafts) {
                if (d.via != null) {
                    wantedByRepo.computeIfAbsent(d.repo, k -> new HashSet<>()).add(d.via);
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : wantedByRepo.entrySet()) {
            Set<String> wanted = e.getValue();
            // 名字 → 文件 → 出现次数
            Map<String, Map<Path, Integer>> hits = new HashMap<>();
            for (Path f : jsFilesByRepo.getOrDefault(e.getKey(), List.of())) {
                String src;
                try {
                    src = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    continue;
                }
                Matcher m = IDENT.matcher(src);
                while (m.find()) {
                    String id = m.group();
                    // '/ai/getAiComment' 里的 getAiComment 是路径片段，不是引用
                    boolean pathSegment = m.start() > 0 && src.charAt(m.start() - 1) == '/';
                    if (!pathSegment && wanted.contains(id)) {
                        hits.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(f, 1, Integer::sum);
                    }
                }
            }
            for (List<Draft> drafts : found.values()) {
                for (Draft d : drafts) {
                    if (d.via == null || !d.repo.equals(e.getKey())) {
                        continue;
                    }
                    Map<Path, Integer> byFile = hits.getOrDefault(d.via, Map.of());
                    // 定义处算一次；本文件里再出现就是本地在用（组件里 const url = ... 再 request(url)）
                    List<Path> users = byFile.entrySet().stream()
                            .filter(x -> !x.getKey().equals(d.abs) || x.getValue() > 1)
                            .map(Map.Entry::getKey).toList();
                    d.unused = users.isEmpty();
                    d.usedBy = users.stream().limit(MAX_USED_BY).map(p -> relToRepo(p, d)).toList();
                }
            }
        }
    }

    private static String relToRepo(Path p, Draft d) {
        String abs = p.toString().replace('\\', '/');
        String marker = "/" + d.repo + "/";
        int i = abs.indexOf(marker);
        return i >= 0 ? abs.substring(i + marker.length()) : p.getFileName().toString();
    }

    private static String jsOwner(String src, int[] lineStarts, int lineNo, String lineText) {
        Matcher k = JS_KEY.matcher(lineText);
        if (k.find() && !GENERIC_KEYS.contains(k.group(1).toLowerCase(Locale.ROOT))) {
            return k.group(1);
        }
        int from = lineStarts[Math.max(0, lineNo - 1 - 8)];
        int to = lineNo < lineStarts.length ? lineStarts[lineNo] : src.length();
        Matcher m = JS_OWNER.matcher(src.substring(from, to));
        String fn = null;
        String var = null;
        while (m.find()) {
            if (m.group(1) != null) {
                fn = m.group(1);
                var = null;
            } else {
                var = m.group(2);
            }
        }
        // 函数体里的 const data = {...} 不是封装名；路径就写在 const 上时才认它
        if (var != null && lineText.contains(var)) {
            return var;
        }
        return fn != null ? fn : var;
    }

    static String normalize(String raw) {
        Matcher d = SPRING_DEFAULT.matcher(raw);
        String s = HOST.matcher(d.matches() ? d.group(1) : raw).replaceFirst("");
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        s = TEMPLATE_VAR.matcher(s).replaceAll("{}");
        s = LEADING_VAR.matcher(s).replaceFirst("");
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static int segments(String p) {
        int n = 0;
        for (String part : p.split("/")) {
            if (!part.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 接口路径的几种写法：带上下文前缀的全路径、去掉前缀（baseUrl 已带）的短路径 */
    static final class Index {
        private final Map<String, List<String>> byKey = new HashMap<>();

        Index(Collection<String> paths) {
            for (String p : paths) {
                if (p == null || p.isBlank()) {
                    continue;
                }
                String full = trimSlash(normalize(p));
                put(full, p);
                int second = full.indexOf('/', 1);
                if (second > 0) {
                    String shortPath = full.substring(second);
                    // 只剩一段的短路径（/list、/detail）到处都是，认了全是误报
                    if (segments(shortPath) >= 2) {
                        put(shortPath, p);
                    }
                }
            }
        }

        private void put(String key, String path) {
            List<String> list = byKey.computeIfAbsent(key, k -> new ArrayList<>());
            if (!list.contains(path)) {
                list.add(path);
            }
        }

        List<String> match(String raw) {
            String n = normalize(raw);
            List<String> hit = byKey.get(trimSlash(n));
            if (hit == null && n.endsWith("/")) {
                // '/match/' + id 这种拼接，对应 /match/{id}
                hit = byKey.get(n + "{}");
            }
            return hit == null ? List.of() : hit;
        }

        private static String trimSlash(String s) {
            return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }

    private static final class Draft {
        final String repo;
        final String file;
        final Path abs;
        final int line;
        final String code;
        final String via;
        List<String> usedBy = List.of();
        boolean unused;

        Draft(String repo, String file, Path abs, int line, String code, String via) {
            this.repo = repo;
            this.file = file;
            this.abs = abs;
            this.line = line;
            this.code = code;
            this.via = via;
        }

        Ref toRef() {
            return new Ref(repo, file, line, code, via, usedBy, unused);
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static int[] lineStarts(String src) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 1 起的行号 */
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

    private static String lineText(String src, int[] starts, int lineNo) {
        int from = starts[lineNo - 1];
        int to = lineNo < starts.length ? starts[lineNo] : src.length();
        return src.substring(from, to).replace("\r", "").replace("\n", "");
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
