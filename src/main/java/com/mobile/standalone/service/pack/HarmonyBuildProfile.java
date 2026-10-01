package com.mobile.standalone.service.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 {@code build-profile.json5} 里打包需要的那几项。
 * <p>
 * 不引入 JSON5 库：这份文件带注释和尾逗号。密码字段读到了也不往外传。
 */
public final class HarmonyBuildProfile {

    private static final Pattern STORE_FILE = Pattern.compile("\"storeFile\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern SIGNING_NAME = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"type\"\\s*:\\s*\"HarmonyOS\"");
    private static final Pattern PRODUCT = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\"signingConfig\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.MULTILINE);

    private HarmonyBuildProfile() {
    }

    public record Product(String name, String label, String signingConfig, String storeFile, boolean signingReady) {
    }

    public static List<Product> products(Path projectDir) {
        Path file = projectDir.resolve("build-profile.json5");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return products(Files.readString(file));
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<Product> products(String json5) {
        Map<String, String> storeBySigning = signingStoreFiles(json5);
        List<Product> out = new ArrayList<>();
        // products 段在 signingConfigs 之后；从 "products" 起截，避免签名称也叫 default 时搅在一起
        int productsAt = json5.indexOf("\"products\"");
        String slice = productsAt >= 0 ? json5.substring(productsAt) : json5;
        Matcher m = PRODUCT.matcher(slice);
        while (m.find()) {
            String name = m.group(1);
            String signing = m.group(2);
            if ("ohosTest".equals(name)) {
                continue;
            }
            String store = storeBySigning.getOrDefault(signing, "");
            boolean ready = !store.isBlank() && Files.isRegularFile(Path.of(unescape(store)));
            String label = "debug".equals(name) ? "Debug" : "Release";
            out.add(new Product(name, label, signing, store, ready));
        }
        return out;
    }

    static Map<String, String> signingStoreFiles(String json5) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher names = SIGNING_NAME.matcher(json5);
        Matcher stores = STORE_FILE.matcher(json5);
        List<String> nameList = new ArrayList<>();
        while (names.find()) {
            nameList.add(names.group(1));
        }
        List<String> storeList = new ArrayList<>();
        while (stores.find()) {
            storeList.add(unescape(stores.group(1)));
        }
        int n = Math.min(nameList.size(), storeList.size());
        for (int i = 0; i < n; i++) {
            out.put(nameList.get(i), storeList.get(i));
        }
        return out;
    }

    static String unescape(String raw) {
        return raw.replace("\\\\", "\\");
    }

    public static String versionName(Path projectDir) {
        return firstGroup(readAppJson(projectDir), "\"versionName\"\\s*:\\s*\"([^\"]+)\"");
    }

    public static String versionCode(Path projectDir) {
        return firstGroup(readAppJson(projectDir), "\"versionCode\"\\s*:\\s*(\\d+)");
    }

    private static String readAppJson(Path projectDir) {
        Path file = projectDir.resolve("AppScope").resolve("app.json5");
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstGroup(String text, String regex) {
        if (text == null || text.isBlank()) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
