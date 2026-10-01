package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 环境搭建：读写本地 k8s-config/{biz}/test/{service}/ 下的部署文件，供页面 Tab 展示与人工修改。
 */
@Slf4j
@Service
public class EnvSetupK8sFilesService {

    private static final Set<String> BIZ = Set.of("app", "pvp");
    private static final Set<String> ALLOWED_NAMES = Set.of(
            "dockerfile", "jenkinsfile", "deploy.yaml", "application-test.properties");

    @Value("${cursor.ask.k8s-cwd:${user.home}/projects/qa_coding}")
    private String k8sCwd;

    @Value("${env-setup.k8s-config-rel:app_coding/k8s-config}")
    private String k8sConfigRel;

    public Map<String, Object> listFiles(String bizLine, String serviceName) throws IOException {
        Path dir = resolveServiceDir(bizLine, serviceName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bizLine", bizLine.trim().toLowerCase(Locale.ROOT));
        out.put("serviceName", serviceName.trim());
        out.put("dir", dir.toString());
        out.put("exists", Files.isDirectory(dir));
        List<Map<String, Object>> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            out.put("files", files);
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isAllowedFileName(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> tabOrder(p.getFileName().toString())))
                    .collect(Collectors.toList());
            for (Path p : paths) {
                Map<String, Object> f = new LinkedHashMap<>();
                String name = p.getFileName().toString();
                f.put("name", name);
                f.put("content", Files.readString(p, StandardCharsets.UTF_8));
                f.put("size", Files.size(p));
                files.add(f);
            }
        }
        out.put("files", files);
        return out;
    }

    public Map<String, Object> saveFile(String bizLine, String serviceName, String fileName, String content)
            throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("内容不能为空字符串以外的 null");
        }
        Path dir = resolveServiceDir(bizLine, serviceName);
        String name = requireAllowedName(fileName);
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        log.info("[env-setup] 已保存 {}/{}", dir, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dir", dir.toString());
        out.put("name", name);
        out.put("size", Files.size(target));
        return out;
    }

    private Path resolveServiceDir(String bizLine, String serviceName) {
        String biz = requireBiz(bizLine);
        String svc = requireServiceName(serviceName);
        Path root = Path.of(k8sCwd).resolve(k8sConfigRel).normalize().toAbsolutePath();
        Path dir = root.resolve(biz).resolve("test").resolve(svc).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException("非法服务目录");
        }
        return dir;
    }

    private static String requireBiz(String bizLine) {
        if (bizLine == null || bizLine.isBlank()) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        String b = bizLine.trim().toLowerCase(Locale.ROOT);
        if (!BIZ.contains(b)) {
            throw new IllegalArgumentException("业务线仅支持 app / pvp");
        }
        return b;
    }

    private static String requireServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("服务名不能为空");
        }
        String s = serviceName.trim();
        if (s.contains("/") || s.contains("\\") || s.contains("..") || s.contains("\0")) {
            throw new IllegalArgumentException("服务名非法");
        }
        return s;
    }

    private static String requireAllowedName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = fileName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.contains("\0")) {
            throw new IllegalArgumentException("文件名非法");
        }
        if (!isAllowedFileName(name)) {
            throw new IllegalArgumentException("不允许的文件类型：" + name);
        }
        return name;
    }

    private static boolean isAllowedFileName(String name) {
        if (name == null || name.isBlank() || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (ALLOWED_NAMES.contains(lower)) {
            return true;
        }
        return lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".properties");
    }

    /** Tab 展示顺序：Dockerfile → Jenkinsfile → deploy → properties → filebeat → ingress → 其它 */
    private static int tabOrder(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if ("dockerfile".equals(lower)) return 10;
        if ("jenkinsfile".equals(lower)) return 20;
        if ("deploy.yaml".equals(lower) || "deploy.yml".equals(lower)) return 30;
        if ("application-test.properties".equals(lower)) return 40;
        if (lower.contains("filebeat")) return 50;
        if (lower.contains("ingress")) return 60;
        return 100;
    }
}
