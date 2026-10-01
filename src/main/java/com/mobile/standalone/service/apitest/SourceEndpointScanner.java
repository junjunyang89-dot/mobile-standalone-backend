package com.mobile.standalone.service.apitest;

import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一个本地源码目录里的 Controller 全部解析成接口。
 * <p>
 * 对比分析只报「两个分支之间变了的」，清单是空的项目靠它永远填不满；
 * 这里是全量：目录里有几个接口就出几个。
 */
public final class SourceEndpointScanner {

    /** 这些目录里的 Controller 不是线上接口：测试桩、编译产物、依赖 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", "target", "build", "out", "node_modules", "test", "tests");

    /** 防止误选到整个磁盘时扫到天荒地老 */
    private static final int MAX_JAVA_FILES = 50_000;

    /**
     * 方法或类上挂了名字带 Auth / Login 的注解＝要登录（@Auth、@NeedUserLogin、@PreAuthorize…
     * 各仓库自己起名，列不全）；@NoAuth、@IgnoreLogin 这类反着的不算
     */
    private static final Pattern AUTH_ANNOTATION = Pattern.compile(
            "@(?!(?i:no|ignore|skip|without|anonymous|pass|permit|allow|public|author))\\w*(?:Auth|Login)\\w*\\b");
    /** request.getHeader("token") 这类：服务从哪个头读登录态 */
    private static final Pattern TOKEN_HEADER = Pattern.compile(
            "getHeader\\(\\s*\"([^\"]*(?:[Tt]oken|Authorization|authorization)[^\"]*)\"\\s*\\)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)^\\s*//.*$");
    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:class|interface)\\s+\\w+");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    private SourceEndpointScanner() {
    }

    /**
     * @param auth 要不要登录：{@code true} 挂了鉴权注解；{@code false} 没挂（且这个仓库确实用注解鉴权）；
     *             {@code null} 仓库里一个鉴权注解都没有——多半走的拦截器，静态看不出来
     */
    public record Scanned(String httpMethod, String path, String handler, String summary, String file, Boolean auth) {
        public Scanned(String httpMethod, String path, String handler, String summary, String file) {
            this(httpMethod, path, handler, summary, file, null);
        }
    }

    /** @param tokenHeaders 服务从这些头读登录态，按出现次数排 */
    public record Result(int javaFiles, int controllers, List<Scanned> endpoints, List<String> tokenHeaders) {
    }

    public static Result scan(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在或不是目录: " + root);
        }
        Path base = root.toAbsolutePath().normalize();
        int[] javaFiles = {0};
        int[] controllers = {0};
        // 同一个 方法+路径 在多个模块里各声明了一次时只留第一个，否则入库时后一个会把前一个覆盖得莫名其妙
        Map<String, Scanned> byKey = new LinkedHashMap<>();
        Map<String, Integer> headers = new LinkedHashMap<>();

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
                if (!file.getFileName().toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                if (++javaFiles[0] > MAX_JAVA_FILES) {
                    throw new IllegalArgumentException("目录里的 Java 文件超过 " + MAX_JAVA_FILES + " 个，是不是选错了目录");
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher hm = TOKEN_HEADER.matcher(source);
                while (hm.find()) {
                    headers.merge(hm.group(1), 1, Integer::sum);
                }
                if (!JavaApiSourceParser.looksLikeController(source)) {
                    return FileVisitResult.CONTINUE;
                }
                List<ApiEndpoint> eps = JavaApiSourceParser.parseEndpoints(source);
                if (eps.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                controllers[0]++;
                String rel = base.relativize(file).toString().replace('\\', '/');
                String clean = stripComments(source);
                boolean classAuth = classRequiresAuth(clean);
                for (ApiEndpoint e : eps) {
                    if (e.path() == null || e.path().isBlank() || e.httpMethod() == null) {
                        continue;
                    }
                    boolean auth = classAuth || methodRequiresAuth(clean, e.handler());
                    byKey.putIfAbsent(e.key(),
                            new Scanned(e.httpMethod(), e.path(), e.handler(), e.summary(), rel, auth));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        List<Scanned> list = new ArrayList<>(byKey.values());
        // 一个鉴权注解都没有，说明这个仓库不靠注解鉴权；这时说「不用登录」是瞎猜
        if (list.stream().noneMatch(s -> Boolean.TRUE.equals(s.auth()))) {
            list.replaceAll(s -> new Scanned(s.httpMethod(), s.path(), s.handler(), s.summary(), s.file(), null));
        }
        List<String> tokenHeaders = headers.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey).toList();
        return new Result(javaFiles[0], controllers[0], list, tokenHeaders);
    }

    /** 去掉注释、清空字符串内容：注释里的 @Auth 不算，"/a/{id}" 里的花括号也不该被当成成员边界 */
    static String stripComments(String source) {
        String s = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
        return STRING_LITERAL.matcher(s).replaceAll("\"\"");
    }

    static boolean classRequiresAuth(String clean) {
        Matcher m = CLASS_DECL.matcher(clean);
        if (!m.find()) {
            return false;
        }
        // import 语句以分号结尾，类注解就在最后一个分号和 class 之间
        int from = clean.lastIndexOf(';', m.start()) + 1;
        return AUTH_ANNOTATION.matcher(clean.substring(from, m.start())).find();
    }

    /** 方法注解在「上一个成员结束」到方法名之间 */
    static boolean methodRequiresAuth(String clean, String handler) {
        if (handler == null || handler.isBlank()) {
            return false;
        }
        String s = clean;
        int at = 0;
        while ((at = s.indexOf(handler, at)) >= 0) {
            int nameAt = at;
            at += handler.length();
            boolean wordStart = nameAt == 0 || !Character.isJavaIdentifierPart(s.charAt(nameAt - 1));
            int p = at;
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
                p++;
            }
            if (!wordStart || p >= s.length() || s.charAt(p) != '(') {
                continue;
            }
            int from = Math.max(Math.max(s.lastIndexOf(';', nameAt), s.lastIndexOf('}', nameAt)),
                    s.lastIndexOf('{', nameAt)) + 1;
            String head = s.substring(from, nameAt);
            // 方法调用 foo(...) 也长这样，声明前面一定有 @XxxMapping
            if (!head.contains("Mapping")) {
                continue;
            }
            return AUTH_ANNOTATION.matcher(head).find();
        }
        return false;
    }
}
