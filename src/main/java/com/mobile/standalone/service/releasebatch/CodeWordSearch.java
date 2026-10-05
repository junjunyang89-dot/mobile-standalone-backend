package com.mobile.standalone.service.releasebatch;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 在若干份代码目录里整词搜一批词，数每个词出现在几个文件里。只走一遍目录 */
@Slf4j
final class CodeWordSearch {

    private CodeWordSearch() {
    }

    private static final long MAX_FILE_BYTES = 2_000_000;

    static Map<String, Integer> countFiles(List<Path> roots, Set<String> words) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (words.isEmpty()) {
            return out;
        }
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String w : words) {
            out.put(w, 0);
            patterns.put(w, Pattern.compile("(?<![\\w.])" + Pattern.quote(w) + "(?![\\w])"));
        }
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (!searchable(rel)) {
                        continue;
                    }
                    String text;
                    try {
                        if (Files.size(p) > MAX_FILE_BYTES) {
                            continue;
                        }
                        text = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        continue;
                    }
                    for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
                        if (text.contains(e.getKey()) && e.getValue().matcher(text).find()) {
                            out.merge(e.getKey(), 1, Integer::sum);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[api-change-batches] 搜代码失败 {}: {}", root, e.getMessage());
            }
        }
        return out;
    }

    private static boolean searchable(String rel) {
        if (rel.startsWith(".git/") || rel.contains("/target/") || rel.startsWith("target/")
                || rel.contains("node_modules/") || rel.contains("/src/test/") || rel.startsWith("src/test/")) {
            return false;
        }
        String l = rel.toLowerCase(Locale.ROOT);
        return l.endsWith(".java") || l.endsWith(".xml") || l.endsWith(".yml") || l.endsWith(".yaml")
                || l.endsWith(".properties") || l.endsWith(".sql") || l.endsWith(".kt");
    }
}
