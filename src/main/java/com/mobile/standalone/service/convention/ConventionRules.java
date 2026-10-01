package com.mobile.standalone.service.convention;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务仓库的<b>约定检查</b>：只查 Android Lint 不可能知道的、我们自己的约定。
 * <p>
 * 产物是「风险」，不是缺陷。X5 入口 160 处不是 160 个 bug，是暴露面——
 * 跟用例结果混在一起报，通过率就开始说谎，跟 BLOCKED 混进 FAILED 是同一个问题。
 * <p>
 * 计数类规则只判「不许增加」，不判「必须为 0」：一条永远红的规则等于没有规则，
 * 人第二周就会把它关掉。
 */
public final class ConventionRules {

    private ConventionRules() {
    }

    public enum Type {
        /** 命中数只许减不许增，对着 baseline 判 */
        RATCHET,
        /** 两份并行实现抽出来的集合必须相同，不需要 baseline */
        PARITY,
        /** 抽出来的每一项都必须已登记，baseline 就是登记表 */
        REGISTRY
    }

    public enum Status {
        OK, IMPROVED, REGRESSED,
        /** 还没锁 baseline：没有参照，不能说它过了 */
        NO_BASELINE,
        /** 仓库或文件不在：没跑成，不是通过 */
        SKIPPED
    }

    /**
     * @param extensions RATCHET 用：全仓扫哪些后缀
     * @param paths      PARITY / REGISTRY 用：仓库内相对路径；PARITY 恰好两个
     * @param pattern    RATCHET 数命中；PARITY / REGISTRY 取第 1 组作为集合元素
     */
    public record Rule(String id, String title, String why, Type type, String repo,
                       List<String> extensions, List<String> paths, Pattern pattern) {
    }

    private static final String ANDROID = "app_esports_android";
    private static final String ANDROID_SRC = "SteamESports/app/src/main/java/com/pwrd/steam/esports/";

    public static final List<Rule> DEFAULTS = List.of(
            new Rule("x5-entry",
                    "X5 WebView 入口只许减不许增",
                    "X5 白屏只在部分「机型 × 内核版本」出现，手头设备测不到；新页面应该走系统 WebView",
                    Type.RATCHET, ANDROID, List.of("kt", "java"), List.of(),
                    Pattern.compile("PWx5WebActivity\\.(?:start|startFullScreen)\\b")),
            new Rule("empty-on-received-error",
                    "空的 onReceivedError 只许减不许增",
                    "页面加载失败时连日志都不留，白屏了无从查起",
                    Type.RATCHET, ANDROID, List.of("kt", "java"), List.of(),
                    Pattern.compile("onReceivedError\\s*\\([^)]*\\)\\s*\\{\\s*\\}")),
            new Rule("jsbridge-parity",
                    "X5 与 sysweb 两份 JSBridge 方法集必须一致",
                    "改一份忘一份的表现是「某些页面有这个功能、某些没有」，不报错",
                    Type.PARITY, ANDROID, List.of(),
                    List.of(ANDROID_SRC + "web/jsbridge/PWJsBridgeHandler.kt",
                            ANDROID_SRC + "sysweb/jsbridge/PWJsBridgeHandler.kt"),
                    Pattern.compile("^\\s*\"([A-Za-z0-9_]+)\"\\s*->", Pattern.MULTILINE)),
            new Rule("server-switch-registry",
                    "服务端下发的开关必须登记",
                    "每个开关都是一条分叉，测试通常只走默认那条；登记即承诺开/关两条都有用例",
                    Type.REGISTRY, ANDROID, List.of(),
                    List.of(ANDROID_SRC + "manager/PWAppConfigManager.kt"),
                    Pattern.compile("PWSharedPrefConst\\.([A-Z0-9_]*SWITCH[A-Z0-9_]*)")));

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", ".idea", "build", "node_modules", "intermediates", "generated");
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_LINES_PER_FILE = 10;

    /** 一次扫描看到了什么。RATCHET 填 hits；PARITY / REGISTRY 填 items（按文件） */
    public record Observation(Map<String, List<Integer>> hits, Map<String, Set<String>> items,
                              List<String> missingPaths) {

        int total() {
            return hits.values().stream().mapToInt(List::size).sum();
        }

        Set<String> allItems() {
            Set<String> all = new TreeSet<>();
            items.values().forEach(all::addAll);
            return all;
        }
    }

    public record Baseline(int total, Map<String, Integer> files, List<String> items,
                           String lockedAt, String commit, String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String id, String title, String why, Type type, Status status,
                         Integer current, Integer baseline, String detail, Map<String, Object> evidence) {
    }

    public static Observation observe(Rule rule, Path repoRoot) throws IOException {
        if (rule.type() == Type.RATCHET) {
            return new Observation(scanRepo(repoRoot, rule), Map.of(), List.of());
        }
        Map<String, Set<String>> items = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String rel : rule.paths()) {
            Path file = repoRoot.resolve(rel);
            if (!Files.isRegularFile(file)) {
                missing.add(rel);
                continue;
            }
            items.put(rel, extract(Files.readString(file, StandardCharsets.UTF_8), rule.pattern()));
        }
        return new Observation(Map.of(), items, missing);
    }

    static Map<String, List<Integer>> scanRepo(Path root, Rule rule) throws IOException {
        Map<String, List<Integer>> hits = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                return name != null && !dir.equals(root) && SKIP_DIRS.contains(name.toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.size() <= MAX_FILE_BYTES && hasExtension(file, rule.extensions())) {
                    List<Integer> lines = matchLines(
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8), rule.pattern());
                    if (!lines.isEmpty()) {
                        hits.put(root.relativize(file).toString().replace('\\', '/'), lines);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    static List<Integer> matchLines(String text, Pattern pattern) {
        List<Integer> lines = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        int line = 1;
        int pos = 0;
        while (m.find()) {
            for (; pos < m.start(); pos++) {
                if (text.charAt(pos) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    static Set<String> extract(String text, Pattern pattern) {
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static boolean hasExtension(Path file, List<String> extensions) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 && extensions.contains(n.substring(dot + 1));
    }

    public static Result skipped(Rule rule, String why) {
        return result(rule, Status.SKIPPED, null, null, why, null);
    }

    public static Result evaluate(Rule rule, Observation obs, Baseline base) {
        if (!obs.missingPaths().isEmpty()) {
            return skipped(rule, "文件不在：" + String.join("、", obs.missingPaths()) + "（被改名或挪走了，规则要跟着改）");
        }
        return switch (rule.type()) {
            case RATCHET -> evaluateRatchet(rule, obs, base);
            case PARITY -> evaluateParity(rule, obs);
            case REGISTRY -> evaluateRegistry(rule, obs, base);
        };
    }

    private static Result evaluateRatchet(Rule rule, Observation obs, Baseline base) {
        int now = obs.total();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("files", obs.hits().size());
        if (base == null) {
            return result(rule, Status.NO_BASELINE, now, null,
                    "现在 " + now + " 处，还没锁 baseline。锁定后只许减不许增", ev);
        }
        if (now > base.total()) {
            ev.put("grown", grownFiles(obs.hits(), base.files()));
            return result(rule, Status.REGRESSED, now, base.total(),
                    "比 baseline 多了 " + (now - base.total()) + " 处，见 grown", ev);
        }
        if (now < base.total()) {
            return result(rule, Status.IMPROVED, now, base.total(),
                    "比 baseline 少了 " + (base.total() - now) + " 处。锁定后就不能再涨回去", ev);
        }
        return result(rule, Status.OK, now, base.total(), "与 baseline 持平", ev);
    }

    /** 比 baseline 多出命中的文件。行号会漂，所以按文件计数比，不按行号比 */
    static List<Map<String, Object>> grownFiles(Map<String, List<Integer>> hits, Map<String, Integer> before) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> old = before == null ? Map.of() : before;
        hits.forEach((file, lines) -> {
            int was = old.getOrDefault(file, 0);
            if (lines.size() > was) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("file", file);
                g.put("before", was);
                g.put("after", lines.size());
                g.put("lines", lines.size() > MAX_LINES_PER_FILE ? lines.subList(0, MAX_LINES_PER_FILE) : lines);
                out.add(g);
            }
        });
        return out;
    }

    private static Result evaluateParity(Rule rule, Observation obs) {
        String a = rule.paths().get(0);
        String b = rule.paths().get(1);
        Set<String> sa = obs.items().get(a);
        Set<String> sb = obs.items().get(b);
        Set<String> onlyA = new TreeSet<>(sa);
        onlyA.removeAll(sb);
        Set<String> onlyB = new TreeSet<>(sb);
        onlyB.removeAll(sa);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("counts", Map.of(a, sa.size(), b, sb.size()));
        if (sa.isEmpty() && sb.isEmpty()) {
            return result(rule, Status.SKIPPED, 0, null,
                    "两份都没抽出东西：写法变了，规则的正则要跟着改", ev);
        }
        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            return result(rule, Status.OK, sa.size(), null, "两份一致，各 " + sa.size() + " 个", ev);
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put(a, onlyA);
        diff.put(b, onlyB);
        ev.put("onlyIn", diff);
        return result(rule, Status.REGRESSED, onlyA.size() + onlyB.size(), 0,
                "两份不一致：有一份没跟上，见 onlyIn", ev);
    }

    private static Result evaluateRegistry(Rule rule, Observation obs, Baseline base) {
        Set<String> now = obs.allItems();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("items", now);
        if (base == null) {
            return result(rule, Status.NO_BASELINE, now.size(), null,
                    "现在 " + now.size() + " 个，还没登记。锁定即登记当前这批", ev);
        }
        Set<String> registered = new TreeSet<>(base.items() == null ? List.of() : base.items());
        Set<String> unregistered = new TreeSet<>(now);
        unregistered.removeAll(registered);
        Set<String> gone = new TreeSet<>(registered);
        gone.removeAll(now);
        if (!gone.isEmpty()) {
            ev.put("gone", gone);
        }
        if (!unregistered.isEmpty()) {
            ev.put("unregistered", unregistered);
            return result(rule, Status.REGRESSED, now.size(), registered.size(),
                    "新开关没登记：" + String.join("、", unregistered) + "。给开/关两条分支配上用例后再登记", ev);
        }
        return result(rule, Status.OK, now.size(), registered.size(),
                gone.isEmpty() ? "全部已登记" : "全部已登记；有 " + gone.size() + " 个已从代码里消失，重新锁定可清掉", ev);
    }

    private static Result result(Rule r, Status s, Integer current, Integer baseline,
                                 String detail, Map<String, Object> evidence) {
        return new Result(r.id(), r.title(), r.why(), r.type(), s, current, baseline, detail, evidence);
    }

    /**
     * 锁 baseline。计数只许往下锁；要往上锁必须 force 且写明原因——
     * 否则「不许增加」就变成了「涨了就顺手锁一下」。
     *
     * @return 新的 baseline；PARITY 没有 baseline 返回 null
     * @throws IllegalStateException 想往上锁但没 force / 没写原因
     */
    public static Baseline lock(Rule rule, Observation obs, Baseline old, boolean force, String reason,
                                String commit) {
        String now = OffsetDateTime.now().toString();
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        return switch (rule.type()) {
            case PARITY -> null;
            case REGISTRY -> new Baseline(obs.allItems().size(), Map.of(), new ArrayList<>(obs.allItems()),
                    now, commit, why);
            case RATCHET -> {
                int total = obs.total();
                if (old != null && total > old.total()) {
                    if (!force) {
                        throw new IllegalStateException(rule.id() + " 现在 " + total + " 处，比 baseline "
                                + old.total() + " 多；只许往下锁。确实要放宽请传 force 并写 reason");
                    }
                    if (why == null) {
                        throw new IllegalStateException(rule.id() + " 放宽 baseline 必须写 reason");
                    }
                }
                Map<String, Integer> files = new TreeMap<>();
                obs.hits().forEach((f, lines) -> files.put(f, lines.size()));
                yield new Baseline(total, files, List.of(), now, commit, why);
            }
        };
    }
}
