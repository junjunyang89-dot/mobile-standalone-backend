package com.mobile.standalone.service.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机鸿蒙签名（{@code build-profile.json5} 的 {@code signingConfigs}）在
 * {@code git checkout -B origin/&lt;branch&gt;} 后会被远端 Windows 路径冲掉。
 * <p>
 * 做法：发现本机可用签名时缓存到 {@code ~/.mobile-standalone/harmony-signing/}，
 * 每次对齐远端后再写回 {@code signingConfigs}，产品/SDK 配置仍用远端最新。
 * 产品引用了不存在的签名名（DevEco 自动签名常只留 default）时，复用本机可用的那套。
 */
final class HarmonyLocalSigning {

    private static final Logger log = LoggerFactory.getLogger(HarmonyLocalSigning.class);

    private static final Pattern STORE_FILE = Pattern.compile("\"storeFile\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern PROFILE = Pattern.compile("\"profile\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRODUCT_SIGNING = Pattern.compile("\"signingConfig\"\\s*:\\s*\"([^\"]+)\"");

    private HarmonyLocalSigning() {
    }

    static Path cacheFile(Path projectDir) {
        String key = projectDir.toAbsolutePath().normalize().toString()
                .replaceAll("[^A-Za-z0-9._-]+", "_");
        if (key.length() > 120) {
            key = key.substring(key.length() - 120);
        }
        return Path.of(System.getProperty("user.home"),
                ".mobile-standalone", "harmony-signing", key, "signingConfigs.fragment.json5");
    }

    /** 当前 build-profile 是否含本机存在的 storeFile（一般是 /Users/...） */
    static boolean hasUsableLocalStore(Path projectDir) {
        Path file = projectDir.resolve("build-profile.json5");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return hasUsableLocalStore(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasUsableLocalStore(String json5) {
        Matcher m = STORE_FILE.matcher(json5);
        while (m.find()) {
            if (isUsableLocal(HarmonyBuildProfile.unescape(m.group(1)))) {
                return true;
            }
        }
        return false;
    }

    /** 若工程里已是本机签名，记下 signingConfigs；内容没变不重写 */
    static void rememberIfLocal(Path projectDir) {
        Path file = projectDir.resolve("build-profile.json5");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json5 = Files.readString(file, StandardCharsets.UTF_8);
            if (!hasUsableLocalStore(json5)) {
                return;
            }
            String block = extractSigningConfigsBlock(json5);
            if (block == null || block.isBlank()) {
                return;
            }
            Path cache = cacheFile(projectDir);
            if (Files.isRegularFile(cache)
                    && block.equals(Files.readString(cache, StandardCharsets.UTF_8))) {
                return;
            }
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, block, StandardCharsets.UTF_8);
            log.info("[app-pack] 已缓存本机鸿蒙签名 signingConfigs → {}", cache);
        } catch (Exception e) {
            log.warn("[app-pack] 缓存本机签名失败: {}", e.getMessage());
        }
    }

    /**
     * 对齐远端后：写回缓存的 signingConfigs，并补齐产品引用但缺失的签名名。
     *
     * @return 文件是否被改动
     */
    static boolean restore(Path projectDir) {
        Path file = projectDir.resolve("build-profile.json5");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String current = Files.readString(file, StandardCharsets.UTF_8);
            String next = current;
            Path cache = cacheFile(projectDir);
            if (Files.isRegularFile(cache)) {
                String cached = Files.readString(cache, StandardCharsets.UTF_8).trim();
                if (cached.contains("signingConfigs") && hasUsableLocalStore(cached)) {
                    String replaced = replaceSigningConfigsBlock(next, cached);
                    if (replaced != null) {
                        next = replaced;
                    }
                }
            }
            next = ensureProductSigningAliases(next);
            if (next.equals(current)) {
                return false;
            }
            Files.writeString(file, next, StandardCharsets.UTF_8);
            log.info("[app-pack] 已恢复本机鸿蒙签名到 {}", file);
            return true;
        } catch (Exception e) {
            log.warn("[app-pack] 恢复本机签名失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * products 里引用的 signingConfig 若在 signingConfigs 中不存在，
     * 复制一份本机可用的签名并改名补上（例如 Testing/Debug 引用 debug，但本机只有 default）。
     */
    static String ensureProductSigningAliases(String json5) {
        String block = extractSigningConfigsBlock(json5);
        if (block == null) {
            return json5;
        }
        List<String> objects = configObjects(block);
        Set<String> have = new LinkedHashSet<>();
        String template = null;
        for (String obj : objects) {
            String name = nameOf(obj);
            if (name != null) {
                have.add(name);
            }
            if (template == null && name != null && usableStoreIn(obj)) {
                template = obj;
            }
        }
        if (template == null) {
            return json5;
        }
        int productsAt = json5.indexOf("\"products\"");
        if (productsAt < 0) {
            return json5;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher pm = PRODUCT_SIGNING.matcher(json5.substring(productsAt));
        while (pm.find()) {
            if (!have.contains(pm.group(1))) {
                missing.add(pm.group(1));
            }
        }
        if (missing.isEmpty()) {
            return json5;
        }
        String templateName = nameOf(template);
        StringBuilder add = new StringBuilder();
        for (String n : missing) {
            String clone = template.replaceFirst(
                    "\"name\"\\s*:\\s*\"" + Pattern.quote(templateName) + "\"",
                    Matcher.quoteReplacement("\"name\": \"" + n + "\""));
            add.append(",\n      ").append(clone);
        }
        int close = block.lastIndexOf(']');
        String newBlock = block.substring(0, close).stripTrailing() + add + "\n    ]";
        return json5.replace(block, newBlock);
    }

    /** 某个签名名对应的 profile（.p7b）路径；找不到或文件不存在返回 null */
    static Path profileOf(String json5, String signingName) {
        String block = extractSigningConfigsBlock(json5);
        if (block == null || signingName == null) {
            return null;
        }
        for (String obj : configObjects(block)) {
            if (!signingName.equals(nameOf(obj))) {
                continue;
            }
            Matcher m = PROFILE.matcher(obj);
            if (!m.find()) {
                return null;
            }
            Path p = Path.of(HarmonyBuildProfile.unescape(m.group(1)));
            return Files.isRegularFile(p) ? p : null;
        }
        return null;
    }

    static boolean looksLocal(String storeFile) {
        if (storeFile == null || storeFile.isBlank()) {
            return false;
        }
        String s = storeFile.replace('\\', '/');
        return s.startsWith("/Users/") || s.startsWith("/home/") || s.startsWith("/var/folders/");
    }

    private static boolean isUsableLocal(String storeFile) {
        return looksLocal(storeFile) && Files.isRegularFile(Path.of(storeFile));
    }

    private static boolean usableStoreIn(String configObject) {
        Matcher m = STORE_FILE.matcher(configObject);
        return m.find() && isUsableLocal(HarmonyBuildProfile.unescape(m.group(1)));
    }

    private static String nameOf(String configObject) {
        Matcher m = NAME.matcher(configObject);
        return m.find() ? m.group(1) : null;
    }

    /** signingConfigs 数组里每个顶层 {...} */
    static List<String> configObjects(String block) {
        List<String> out = new ArrayList<>();
        int open = block.indexOf('[');
        if (open < 0) {
            return out;
        }
        int i = open + 1;
        while (i < block.length()) {
            int start = block.indexOf('{', i);
            if (start < 0) {
                break;
            }
            int end = matchingBracket(block, start, '{', '}');
            if (end < 0) {
                break;
            }
            out.add(block.substring(start, end + 1));
            i = end + 1;
        }
        return out;
    }

    /** 抽出 {@code "signingConfigs": [ ... ]}（含键名）。 */
    static String extractSigningConfigsBlock(String json5) {
        int key = json5.indexOf("\"signingConfigs\"");
        if (key < 0) {
            return null;
        }
        int bracket = json5.indexOf('[', key);
        if (bracket < 0) {
            return null;
        }
        int end = matchingBracket(json5, bracket, '[', ']');
        if (end < 0) {
            return null;
        }
        return json5.substring(key, end + 1);
    }

    /**
     * 用 {@code replacement}（形如 {@code "signingConfigs": [...]}）替换原文中的段。
     */
    static String replaceSigningConfigsBlock(String json5, String replacement) {
        String block = extractSigningConfigsBlock(json5);
        if (block == null) {
            return null;
        }
        String repl = replacement.trim();
        if (!repl.startsWith("\"signingConfigs\"")) {
            repl = "\"signingConfigs\": " + repl;
        }
        return json5.replace(block, repl);
    }

    static int matchingBracket(String s, int openAt, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openAt; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
