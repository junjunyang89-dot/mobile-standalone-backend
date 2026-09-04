package com.mobile.standalone.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用例「问 AI」要能对着源码说话，而不是让模型猜「异步聚合」。
 * <p>
 * 人贴的是文案、matchId、仓库路径；真正的判断条件在 swift-sphere 的 {@code v-if} 里。
 * 先在本机仓库里把这段文案和接口定义搜出来，再直接作答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseAskCodeSearch {

    private static final int MAX_HITS = 12;
    private static final int CONTEXT = 14;
    private static final int MAX_EXCERPT = 1800;
    private static final Duration RG_TIMEOUT = Duration.ofSeconds(8);

    private static final Pattern QUOTE = Pattern.compile("[「『“\"']([^」』”\"']{2,80})[」』”\"']");
    private static final Pattern API_PATH = Pattern.compile("(/[A-Za-z0-9._-]{2,}){2,8}");
    private static final Pattern ABS_PATH = Pattern.compile("(/Users/[^\\s，。；;）)]+)|(/home/[^\\s，。；;）)]+)");
    private static final Pattern REPO_NAME = Pattern.compile(
            "\\b(swift-sphere|data-center|global-data|app-swift-v3|app_esports_android|esportapp-ios)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> SKIP_DIR = List.of(
            "node_modules", "dist", "target", "build", ".git", ".idea", "coverage");

    private final ApiProjectService apiProjectService;

    @Value("${case-ask.workspace:}")
    private String workspace;

    public record Hit(String repo, String file, int line, String excerpt) {
    }

    public List<Hit> search(String question, List<String> extraHints) {
        List<String> queries = extractQueries(question, extraHints);
        List<Path> roots = roots(question);
        if (queries.isEmpty() || roots.isEmpty()) {
            return List.of();
        }
        List<Hit> raw = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path root : roots) {
            for (String q : queries) {
                collect(raw, seen, searchOne(root, q));
            }
            for (String q : followUpQueries(raw)) {
                collect(raw, seen, searchOne(root, q));
            }
        }
        raw.sort((a, b) -> Integer.compare(score(b), score(a)));
        return raw.size() > MAX_HITS ? new ArrayList<>(raw.subList(0, MAX_HITS)) : raw;
    }

    /**
     * 上一轮已经定位到的文件，这一轮把那段再读出来，避免追问「哪个接口」时把上文丢掉。
     */
    public List<Hit> reopen(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<Path> roots = roots("");
        List<Hit> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String ref : refs) {
            Hit h = reopenOne(roots, ref);
            if (h != null && seen.add(h.file() + ":" + h.line())) {
                out.add(h);
            }
        }
        return out;
    }

    private Hit reopenOne(List<Path> roots, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        int colon = ref.lastIndexOf(':');
        if (colon <= 0 || colon >= ref.length() - 1) {
            return null;
        }
        String path = ref.substring(0, colon).trim();
        int line;
        try {
            line = Integer.parseInt(ref.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int slash = path.indexOf('/');
        String repo = slash > 0 ? path.substring(0, slash) : "";
        String rel = slash > 0 ? path.substring(slash + 1) : path;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            String leaf = root.getFileName() == null ? "" : root.getFileName().toString();
            Path file = null;
            Path hitRoot = root;
            if (!repo.isEmpty() && leaf.equals(repo)) {
                file = root.resolve(rel);
            } else if (!repo.isEmpty()) {
                Path nested = root.resolve(repo).resolve(rel);
                if (Files.isRegularFile(nested)) {
                    file = nested;
                    hitRoot = root.resolve(repo);
                } else {
                    Path asGiven = root.resolve(path);
                    if (Files.isRegularFile(asGiven)) {
                        file = asGiven;
                        hitRoot = Files.isDirectory(root.resolve(repo)) ? root.resolve(repo) : root;
                    }
                }
            } else {
                file = root.resolve(rel);
            }
            if (file != null && Files.isRegularFile(file)) {
                return toHit(hitRoot, file, Math.max(1, line));
            }
        }
        return null;
    }

    private static void collect(List<Hit> raw, Set<String> seen, List<Hit> more) {
        for (Hit h : more) {
            if (seen.add(h.file() + ":" + h.line())) {
                raw.add(h);
            }
        }
    }

    static List<String> followUpQueries(List<Hit> hits) {
        LinkedHashSet<String> q = new LinkedHashSet<>();
        for (Hit h : hits) {
            String f = h.file() == null ? "" : h.file();
            String ex = h.excerpt() == null ? "" : h.excerpt();
            if (f.contains("/lang/") && (ex.contains("loading") || f.contains("matchDetail"))) {
                q.add("matchOverview.loading");
                q.add("isParsing");
                q.add("demoStatus");
            }
            if (f.contains("matchDetail") && (f.endsWith(".vue") || f.endsWith(".js"))) {
                q.add("getMatchDetail");
                q.add("/gld/deadlock/match/detail");
            }
            if (ex.contains("getDeadlockMatchHighLightInfo")) {
                q.add("getDeadlockMatchHighLightInfo");
            }
        }
        return new ArrayList<>(q);
    }

    static int score(Hit h) {
        String f = h.file() == null ? "" : h.file().toLowerCase(Locale.ROOT);
        String ex = h.excerpt() == null ? "" : h.excerpt();
        int s = 0;
        if (f.contains("matchdetail") || f.contains("match-detail")) {
            s += 50;
        }
        if (f.endsWith(".vue")) {
            s += 35;
        }
        if (f.contains("/lang/") && f.endsWith(".json")) {
            s += 20;
        }
        if (f.contains("/api/deadlock") || f.contains("/api/highlights")) {
            s += 40;
        }
        if (ex.contains("getMatchDetail") || (ex.contains("url:") && ex.contains("/gld/"))) {
            s += 40;
        }
        if (ex.contains("v-if") || ex.contains("isParsing") || ex.contains("demoStatus")) {
            s += 45;
        }
        if (f.contains("applog") || f.contains("dailytask") || f.endsWith("mine.js") || f.contains("/utils/")) {
            s -= 50;
        }
        return s;
    }

    static List<String> extractQueries(String question, List<String> extraHints) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String text = question == null ? "" : question;
        Matcher qm = QUOTE.matcher(text);
        while (qm.find()) {
            String q = qm.group(1).trim();
            if (q.length() >= 2) {
                out.add(q);
                // 「数据陆续获取中，清耐心等待」和文案差一个错字时，仍能命中前半段
                int cut = indexOfFirstPause(q);
                if (cut >= 4 && cut < q.length()) {
                    out.add(q.substring(0, cut));
                }
            }
        }
        Matcher pm = API_PATH.matcher(text);
        while (pm.find()) {
            String p = pm.group();
            if (looksLikeApiPath(p)) {
                out.add(p);
            }
        }
        if (extraHints != null) {
            for (String h : extraHints) {
                if (h != null && h.length() >= 4 && h.length() <= 80) {
                    out.add(h.trim());
                }
            }
        }
        if (out.isEmpty()) {
            String run = longestChineseRun(text);
            if (run != null) {
                out.add(run);
            }
        }
        // 问「何时露出 / 判断条件」时，把前端开关一起搜上，避免只命中 i18n
        if (looksLikeUiConditionQuestion(text)) {
            out.add("isParsing");
            out.add("demoStatus");
        }
        if (looksLikeApiQuestion(text)) {
            out.add("getMatchDetail");
            out.add("matchData.value =");
            out.add("/gld/deadlock/match/detail");
            // 问接口时也带上露出开关，避免只答「res.result」却不提 demoStatus==0
            out.add("isParsing");
            out.add("demoStatus");
        }
        return new ArrayList<>(out);
    }

    static String formatHits(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "本机仓库没有搜到相关片段。";
        }
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append("—— ").append(h.repo()).append(" / ").append(h.file());
            if (h.line() > 0) {
                sb.append(" :").append(h.line());
            }
            sb.append(" ——\n").append(h.excerpt()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private List<Path> roots(String question) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        addExisting(out, pathsInQuestion(question));
        addExisting(out, configuredWorkspace());
        addExisting(out, siblingAppCoding());
        addExisting(out, projectRepoPaths());
        List<Path> narrowed = narrowToNamedRepo(out, question);
        List<Path> list = new ArrayList<>(narrowed.isEmpty() ? out : narrowed);
        return list.size() > 6 ? list.subList(0, 6) : list;
    }

    private List<Path> projectRepoPaths() {
        try {
            List<Path> paths = new ArrayList<>();
            for (var p : apiProjectService.list(true)) {
                if (p.getRepoPath() == null || p.getRepoPath().isBlank()) {
                    continue;
                }
                paths.add(Path.of(p.getRepoPath().trim()));
            }
            return paths;
        } catch (Exception e) {
            log.debug("[case-ask] 读 api_projects.repo_path 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Path configuredWorkspace() {
        if (workspace != null && !workspace.isBlank()) {
            return Path.of(workspace.trim());
        }
        return null;
    }

    private static Path siblingAppCoding() {
        Path here = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path sibling = here.getParent() == null ? null : here.getParent().resolve("app_coding");
        Path home = Path.of(System.getProperty("user.home", ""), "projects/qa_coding/app_coding");
        if (sibling != null && Files.isDirectory(sibling)) {
            return sibling;
        }
        if (Files.isDirectory(home)) {
            return home;
        }
        return sibling;
    }

    private static List<Path> pathsInQuestion(String question) {
        if (question == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        Matcher m = ABS_PATH.matcher(question);
        while (m.find()) {
            out.add(Path.of(m.group().replaceAll("[，。；;）)]+$", "")));
        }
        return out;
    }

    private static List<Path> narrowToNamedRepo(Set<Path> roots, String question) {
        if (question == null) {
            return List.of();
        }
        Matcher m = REPO_NAME.matcher(question);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (m.find()) {
            names.add(m.group().toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty() && (question.contains("前端") || question.contains("H5"))) {
            names.add("swift-sphere");
        }
        if (names.isEmpty()) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            String leaf = root.getFileName() == null ? "" : root.getFileName().toString().toLowerCase(Locale.ROOT);
            if (names.contains(leaf)) {
                out.add(root);
                continue;
            }
            for (String name : names) {
                Path child = root.resolve(name);
                if (Files.isDirectory(child)) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    private static void addExisting(Set<Path> out, Path p) {
        if (p != null && Files.isDirectory(p)) {
            out.add(p.toAbsolutePath().normalize());
        }
    }

    private static void addExisting(Set<Path> out, List<Path> paths) {
        for (Path p : paths) {
            addExisting(out, p);
        }
    }

    private List<Hit> searchOne(Path root, String query) {
        List<Hit> fromRg = rg(root, query);
        if (!fromRg.isEmpty()) {
            return fromRg;
        }
        return walkFallback(root, query);
    }

    private List<Hit> rg(Path root, String query) {
        Path rg = rgBinary();
        if (rg == null) {
            return List.of();
        }
        List<String> cmd = List.of(
                rg.toString(), "-n", "-F", "--no-heading",
                "--max-count", "12",
                "--max-filesize", "400K",
                "--glob", "!**/node_modules/**",
                "--glob", "!**/dist/**",
                "--glob", "!**/target/**",
                "--glob", "!**/.git/**",
                "--glob", "!**/build/**",
                // 这个 -- 是安全边界，不是格式：query 是用户可控的，没有它 rg 会把
                // `--pre=/bin/sh` 之类当成选项，对每个被搜文件执行任意命令（以后端进程身份）。
                // -F 只影响 pattern 怎么解释，不影响参数解析，挡不住这个。
                // 而 /ask 在 READ_ONLY_POST_SUFFIXES 白名单里，只读账号就能调。
                "--",
                query,
                root.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            boolean done = p.waitFor(RG_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return List.of();
            }
            List<Hit> hits = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && hits.size() < MAX_HITS) {
                    Hit h = parseRgLine(root, line);
                    if (h != null) {
                        hits.add(h);
                    }
                }
            }
            return hits;
        } catch (Exception e) {
            log.debug("[case-ask] rg 失败 {}: {}", query, e.getMessage());
            return List.of();
        }
    }

    private Hit parseRgLine(Path root, String line) {
        int a = line.indexOf(':');
        if (a <= 0) {
            return null;
        }
        int b = line.indexOf(':', a + 1);
        if (b <= a) {
            return null;
        }
        Path file = Path.of(line.substring(0, a));
        int ln;
        try {
            ln = Integer.parseInt(line.substring(a + 1, b));
        } catch (NumberFormatException e) {
            return null;
        }
        return toHit(root, file, ln);
    }

    private List<Hit> walkFallback(Path root, String query) {
        List<Hit> hits = new ArrayList<>();
        try (var walk = Files.walk(root, 8)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !skipped(root.relativize(p)))
                    .filter(CaseAskCodeSearch::codeFile)
                    .limit(400)
                    .forEach(p -> {
                        if (hits.size() >= MAX_HITS) {
                            return;
                        }
                        Hit h = scanFile(root, p, query);
                        if (h != null) {
                            hits.add(h);
                        }
                    });
        } catch (IOException e) {
            log.debug("[case-ask] walk {} 失败: {}", root, e.getMessage());
        }
        return hits;
    }

    private Hit scanFile(Path root, Path file, String query) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(query)) {
                    return toHit(root, file, i + 1, lines);
                }
            }
        } catch (Exception ignored) {
            // 二进制或超大文件跳过
        }
        return null;
    }

    private Hit toHit(Path root, Path file, int line) {
        try {
            return toHit(root, file, line, Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private Hit toHit(Path root, Path file, int line, List<String> lines) {
        int from = Math.max(0, line - 1 - CONTEXT);
        int to = Math.min(lines.size(), line + CONTEXT);
        StringBuilder ex = new StringBuilder();
        for (int i = from; i < to; i++) {
            ex.append(String.format("%4d| %s%n", i + 1, lines.get(i)));
        }
        String excerpt = ex.toString();
        if (excerpt.length() > MAX_EXCERPT) {
            excerpt = excerpt.substring(0, MAX_EXCERPT) + "\n…";
        }
        Path abs = file.toAbsolutePath().normalize();
        Path rel;
        try {
            rel = root.toAbsolutePath().normalize().relativize(abs);
        } catch (Exception e) {
            rel = abs.getFileName();
        }
        String repo = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        return new Hit(repo, rel.toString().replace('\\', '/'), line, excerpt);
    }

    private static Path rgBinary() {
        for (String c : List.of("rg", "/opt/homebrew/bin/rg", "/usr/local/bin/rg", "/usr/bin/rg")) {
            Path p = Path.of(c);
            if (c.equals("rg")) {
                try {
                    Process proc = new ProcessBuilder("which", "rg").start();
                    if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly();
                        continue;
                    }
                    if (proc.exitValue() == 0) {
                        String loc = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                        if (!loc.isEmpty() && Files.isExecutable(Path.of(loc))) {
                            return Path.of(loc);
                        }
                    }
                } catch (Exception ignored) {
                    // 没有 rg 就走 walk
                }
                continue;
            }
            if (Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean skipped(Path rel) {
        for (Path part : rel) {
            if (SKIP_DIR.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean codeFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".vue") || n.endsWith(".js") || n.endsWith(".ts")
                || n.endsWith(".json") || n.endsWith(".java") || n.endsWith(".kt")
                || n.endsWith(".swift");
    }

    private static int indexOfFirstPause(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '，' || c == ',' || c == '。' || c == '；') {
                return i;
            }
        }
        return s.length();
    }

    private static String longestChineseRun(String text) {
        Matcher m = Pattern.compile("[\\u4e00-\\u9fff]{4,40}").matcher(text);
        String best = null;
        while (m.find()) {
            if (best == null || m.group().length() > best.length()) {
                best = m.group();
            }
        }
        return best;
    }

    static boolean looksLikeApiPath(String p) {
        if (p == null) {
            return false;
        }
        String s = p.toLowerCase(Locale.ROOT);
        if (s.startsWith("/users/") || s.startsWith("/home/") || s.contains("/qa_coding/")) {
            return false;
        }
        return s.startsWith("/gld/") || s.startsWith("/glc/") || s.startsWith("/glag/")
                || s.startsWith("/appdatacenter/") || s.startsWith("/api/");
    }

    private static boolean looksLikeUiConditionQuestion(String text) {
        return text.contains("露出") || text.contains("显示") || text.contains("判断")
                || text.contains("什么时候") || text.contains("何时") || text.contains("条件");
    }

    static boolean looksLikeApiQuestion(String text) {
        return text.contains("接口") || text.contains("请求") || text.contains("matchData")
                || text.contains("从哪") || text.contains("哪个接口");
    }
}
