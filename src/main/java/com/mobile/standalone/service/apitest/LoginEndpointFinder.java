package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.apichange.ApiParam;
import com.mobile.standalone.service.apichange.DtoField;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在并排放着的各仓库里找「token 从哪个接口来」。
 * <p>
 * 找的是路径最后一段带 login 的接口，再按两点排：客户端真在调的排前面
 * （仓库里往往有一堆废弃的登录方式）；入参像「账号 + 密码/验证码」的排前面
 * （扫码、第三方登录没法自动化）。
 */
public final class LoginEndpointFinder {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "target", "build", "out", "node_modules", "test", "tests");
    private static final int MAX_JAVA_FILES = 200_000;
    private static final int MAX_CANDIDATES = 8;

    private static final Pattern LOGIN_SEGMENT = Pattern.compile("(?i).*login.*|signin|sign-in");
    private static final Pattern NOT_LOGIN = Pattern.compile("(?i).*(logout|callback|time|status|check|page|record|log$|info).*");
    /** 第三方 / 扫码 / 一键登录：要真人或真设备，自动化拿不到 token */
    private static final Pattern HARD_LOGIN = Pattern.compile("(?i).*(qr|oauth|apple|wx|wechat|weixin|steam|mini|oneclick|txlogin|quick|scan|douyu|qq).*");
    private static final Pattern ACCOUNT_FIELD = Pattern.compile("(?i).*(phone|mobile|account|username|user_name|loginname|email).*");
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(password|passwd|pwd|securitycode|verifycode|smscode|code)$");
    private static final Pattern NOT_SECRET = Pattern.compile("(?i).*(area|country|region|invite|channel).*");
    private static final Pattern APP_ID_FIELD = Pattern.compile("(?i)app_?id");
    private static final Pattern APP_ID_VALUE = Pattern.compile("(?i)\\bapp_?id\\b\\s*[=:]\\s*(\\d{1,6})\\b");

    private LoginEndpointFinder() {
    }

    public record Candidate(String repo, String httpMethod, String path, String summary, String file,
                            List<String> fields, String bodyTemplate, List<String> callerRepos, boolean automatable) {
    }

    private record Found(String repo, ApiEndpoint ep, String file) {
    }

    public static List<Candidate> find(List<Path> roots) throws IOException {
        List<Found> found = new ArrayList<>();
        // 仓库 → 类名 → 文件，入参 DTO 按类名在同仓库里找
        Map<String, Map<String, Path>> classFiles = new HashMap<>();
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
                    if (!dir.equals(base) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++files[0] > MAX_JAVA_FILES) {
                        throw new IllegalArgumentException("搜索范围里的 Java 文件超过 " + MAX_JAVA_FILES + " 个，是不是选错了目录");
                    }
                    Path rel = base.relativize(file);
                    String repo = baseIsRepo || rel.getNameCount() < 2
                            ? base.getFileName().toString() : rel.getName(0).toString();
                    classFiles.computeIfAbsent(repo, k -> new HashMap<>())
                            .putIfAbsent(name.substring(0, name.length() - 5), file);
                    if (!name.contains("Controller") && !name.contains("Api") && !name.contains("Resource")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String src;
                    try {
                        src = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!JavaApiSourceParser.looksLikeController(src)) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (ApiEndpoint ep : JavaApiSourceParser.parseEndpoints(src)) {
                        if (isLoginPath(ep.path())) {
                            found.add(new Found(repo, ep, rel.toString().replace('\\', '/')));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (found.isEmpty()) {
            return List.of();
        }

        Map<String, List<CallerRefScanner.Ref>> callers = CallerRefScanner.scan(
                found.stream().map(f -> f.ep.path()).distinct().toList(), roots, null);
        Map<String, String> appIdByRepo = new HashMap<>();

        List<Candidate> out = new ArrayList<>();
        for (Found f : found) {
            List<String> callerRepos = callers.getOrDefault(f.ep.path(), List.of()).stream()
                    .filter(c -> !c.unused() && !c.repo().equals(f.repo))
                    .map(CallerRefScanner.Ref::repo).distinct().toList();
            List<String> fields = fieldsOf(f, classFiles.getOrDefault(f.repo, Map.of()));
            String appId = null;
            if (fields.stream().anyMatch(n -> APP_ID_FIELD.matcher(n).matches())) {
                appId = callerRepos.stream()
                        .map(repo -> appIdByRepo.computeIfAbsent(repo, k -> guessAppId(roots, k)))
                        .filter(v -> v != null && !v.isEmpty()).findFirst().orElse(null);
            }
            boolean hasAccount = fields.stream().anyMatch(n -> ACCOUNT_FIELD.matcher(n).matches());
            boolean hasSecret = fields.stream().anyMatch(LoginEndpointFinder::isSecretField);
            boolean automatable = hasAccount && hasSecret && !HARD_LOGIN.matcher(f.ep.path()).matches();
            out.add(new Candidate(f.repo, f.ep.httpMethod(), f.ep.path(), f.ep.summary(), f.file,
                    fields, bodyTemplate(fields, appId), callerRepos, automatable));
        }
        out.sort(Comparator.comparingInt(LoginEndpointFinder::score).reversed());
        return out.size() > MAX_CANDIDATES ? out.subList(0, MAX_CANDIDATES) : out;
    }

    static boolean isLoginPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String last = path.replaceAll("/+$", "");
        last = last.substring(last.lastIndexOf('/') + 1);
        return LOGIN_SEGMENT.matcher(last).matches() && !NOT_LOGIN.matcher(last).matches();
    }

    private static int score(Candidate c) {
        return (c.automatable() ? 100 : 0) + c.callerRepos().size() * 10 + ("POST".equals(c.httpMethod()) ? 1 : 0);
    }

    private static boolean isSecretField(String n) {
        return SECRET_FIELD.matcher(n).matches() && !NOT_SECRET.matcher(n).matches();
    }

    /** @RequestBody 的类字段；没有 body 就用 query 参数 */
    private static List<String> fieldsOf(Found f, Map<String, Path> classes) {
        Set<String> out = new LinkedHashSet<>();
        String bodyType = f.ep.bodyType();
        if (bodyType != null) {
            String simple = bodyType.replaceAll("<.*", "").replaceAll(".*\\.", "");
            Path p = classes.get(simple);
            if (p != null) {
                try {
                    for (DtoField d : JavaApiSourceParser.parseFields(Files.readString(p, StandardCharsets.UTF_8))) {
                        if (!d.ignored()) {
                            out.add(d.wireName());
                        }
                    }
                } catch (IOException ignore) {
                    // 读不到就当没有字段，人自己填
                }
            }
        }
        for (ApiParam ap : f.ep.params()) {
            if (!"BODY".equals(ap.source()) && ap.name() != null && !ap.name().isBlank()
                    && !"HEADER".equals(ap.source())) {
                out.add(ap.name());
            }
        }
        return new ArrayList<>(out);
    }

    static String bodyTemplate(List<String> fields, String appId) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean accountUsed = false;
        boolean secretUsed = false;
        for (String n : fields) {
            if (!accountUsed && ACCOUNT_FIELD.matcher(n).matches()) {
                body.put(n, "{{loginAccount}}");
                accountUsed = true;
            } else if (!secretUsed && isSecretField(n)) {
                body.put(n, "{{loginSecret}}");
                secretUsed = true;
            } else if (APP_ID_FIELD.matcher(n).matches() && appId != null) {
                body.put(n, Integer.parseInt(appId));
            } else {
                body.put(n, "");
            }
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 客户端里 APP_ID = 2 这类常量，只看常量/配置文件，全仓库扫一遍不值 */
    private static String guessAppId(List<Path> roots, String repo) {
        Map<String, Integer> votes = new HashMap<>();
        for (Path r : roots) {
            Path dir = r.resolve(repo);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var s = Files.walk(dir, 14)) {
                s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    String ps = p.toString();
                    return (n.contains("const") || n.contains("config")) && !ps.contains("/node_modules/")
                            && !ps.contains("/build/") && Files.isRegularFile(p);
                }).limit(2000).forEach(p -> {
                    try {
                        if (Files.size(p) > 300_000) {
                            return;
                        }
                        Matcher m = APP_ID_VALUE.matcher(Files.readString(p, StandardCharsets.UTF_8));
                        while (m.find()) {
                            votes.merge(m.group(1), 1, Integer::sum);
                        }
                    } catch (Exception ignore) {
                        // 二进制或编码不对的文件跳过
                    }
                });
            } catch (IOException ignore) {
                // 目录读不了就不猜
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
}
