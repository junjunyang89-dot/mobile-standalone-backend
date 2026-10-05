package com.mobile.standalone.service.apichange;

import lombok.Data;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码这次新读 / 改读的配置，在待测分支各环境的配置文件里有没有、配了什么。
 * <p>
 * 这几个项目的配置写在仓库里的 application-dev / application-prod.properties，跟代码一起发——
 * 只在 dev 里加了、prod 里忘了加，测试环境一切正常，线上一发就启动失败或走默认值。
 * 仓库里没有配置文件（配在配置中心 / k8s）时，结果里没有任何环境，调用方要提示人去核对，而不是当成「都配好了」。
 */
@Data
public class ConfigPresence {

    private String key;
    /** 代码里没写默认值：不配启动失败 */
    private boolean noDefault;
    /** 列表配置至少要配几个值；没有这个要求为 null */
    private Integer needCount;
    /** 环境 → 配置文件里的值，没配为 null。「公共」是 application.properties，各环境都会读 */
    private Map<String, String> profiles = new LinkedHashMap<>();
    /** 在哪些文件里找的 */
    private List<String> files = new ArrayList<>();

    public static final String COMMON = "公共";

    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(?:^|/)(?:application|bootstrap)(?:-([\\w.]+))?\\.(properties|ya?ml)$");

    static boolean isConfigFile(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        return CONFIG_FILE.matcher(lower).find() && !lower.contains("/src/test/") && !lower.startsWith("src/test/")
                && !lower.contains("/target/");
    }

    /**
     * @param changes 方法级改动里的 CONFIG 项（带 ref 的）
     * @param files   待测侧的配置文件（相对路径）
     */
    static List<ConfigPresence> of(List<MethodLogicDiff.Change> changes, List<String> files, Function<String, String> read) {
        Map<String, ConfigPresence> byKey = new LinkedHashMap<>();
        for (MethodLogicDiff.Change c : changes) {
            if (!"CONFIG".equals(c.getKind()) || c.getRef() == null || c.getTitle() == null
                    || c.getTitle().startsWith("不再读取")) {
                continue;
            }
            ConfigPresence p = byKey.computeIfAbsent(c.getRef(), k -> {
                ConfigPresence x = new ConfigPresence();
                x.setKey(k);
                return x;
            });
            p.setNoDefault(p.isNoDefault() || c.isNoDefault());
            if (c.getNeedCount() != null && (p.getNeedCount() == null || p.getNeedCount() < c.getNeedCount())) {
                p.setNeedCount(c.getNeedCount());
            }
        }
        if (byKey.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> byProfile = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        for (String f : files == null ? List.<String>of() : files) {
            String text = read.apply(f);
            if (text == null) {
                continue;
            }
            Matcher m = CONFIG_FILE.matcher(f.toLowerCase(Locale.ROOT));
            if (!m.find()) {
                continue;
            }
            String profile = m.group(1) == null ? COMMON : m.group(1);
            byProfile.computeIfAbsent(profile, k -> new LinkedHashMap<>())
                    .putAll(m.group(2).equals("properties") ? properties(text) : yaml(text));
            used.add(f);
        }
        Map<String, String> common = byProfile.getOrDefault(COMMON, Map.of());
        List<String> envs = byProfile.keySet().stream().filter(p -> !COMMON.equals(p)).toList();
        for (ConfigPresence p : byKey.values()) {
            p.setFiles(used);
            String k = norm(p.getKey());
            if (envs.isEmpty() && !byProfile.isEmpty()) {
                p.getProfiles().put(COMMON, common.get(k));
            }
            for (String env : envs) {
                String v = byProfile.get(env).get(k);
                p.getProfiles().put(env, v != null ? v : common.get(k));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** 这个值有几个（逗号分隔） */
    public static int count(String value) {
        return value == null || value.isBlank() ? 0 : value.split(",").length;
    }

    /** Spring 宽松绑定：a.b-c、a.bC、a.b_c 是同一个 key */
    static String norm(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static Map<String, String> properties(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (Exception e) {
            return out;
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            String v = p.getProperty(name);
            out.put(norm(name), v);
            Matcher idx = Pattern.compile("^(.*)\\[\\d+]$").matcher(name);
            if (idx.matches()) {
                lists.computeIfAbsent(norm(idx.group(1)), k -> new ArrayList<>()).add(v);
            }
        }
        lists.forEach((k, v) -> out.putIfAbsent(k, String.join(",", v)));
        return out;
    }

    private static Map<String, String> yaml(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Object doc : new org.yaml.snakeyaml.Yaml().loadAll(text)) {
                flatten("", doc, out);
            }
        } catch (Exception ignored) {
            // 写坏的 yml 当成没配，调用方会提示缺这个 key
        }
        return out;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(k, e.getValue(), out);
            }
        } else if (node instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) {
                parts.add(String.valueOf(o));
            }
            out.put(norm(prefix), String.join(",", parts));
        } else if (!prefix.isEmpty()) {
            out.put(norm(prefix), node == null ? "" : String.valueOf(node));
        }
    }
}
