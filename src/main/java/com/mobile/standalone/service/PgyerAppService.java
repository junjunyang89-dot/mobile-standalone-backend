package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 蒲公英（Pgyer）版本列表 + 下载安装。
 * <p>
 * 鉴权使用 API Key（账号设置中获取），不要把网页登录密码写入代码或配置仓库。
 * 文档：https://www.pgyer.com/doc/view/api
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PgyerAppService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DeviceMapper deviceMapper;
    private final AppPackageInstallService appPackageInstallService;

    @Value("${pgyer.enabled:false}")
    private boolean enabled;

    @Value("${pgyer.api-base:https://www.pgyer.com}")
    private String apiBase;

    @Value("${pgyer.api-key:}")
    private String apiKey;

    @Value("${pgyer.default-app-key:2e2cadbddb5d5b67aa909ba7a570ac13}")
    private String defaultAppKey;

    @Value("${app.upload-dir:${user.home}/.mobile-standalone/uploads/applications}")
    private String downloadDir;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public void ensureConfigured() {
        if (!enabled) {
            throw new IllegalStateException("蒲公英未启用：请在 application.yml 设置 pgyer.enabled=true");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 pgyer.api-key（请到蒲公英后台「API 信息」复制 API Key，不要使用网页登录密码）");
        }
    }

    public List<PgyerBuildVo> listBuilds(String appKey, int page) {
        ensureConfigured();
        String key = (appKey == null || appKey.isBlank()) ? defaultAppKey : appKey.trim();
        try {
            String body = "_api_key=" + enc(apiKey)
                    + "&appKey=" + enc(key)
                    + "&page=" + Math.max(1, page);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(apiBase) + "/apiv2/app/builds"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JSON.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("蒲公英 builds 失败: code=" + code + ", message=" + root.path("message").asText());
            }
            JsonNode list = root.path("data").path("list");
            List<PgyerBuildVo> out = new ArrayList<>();
            if (list != null && list.isArray()) {
                for (JsonNode n : list) {
                    PgyerBuildVo vo = new PgyerBuildVo();
                    vo.setBuildKey(text(n, "buildKey"));
                    vo.setBuildName(text(n, "buildName"));
                    vo.setBuildVersion(text(n, "buildVersion"));
                    vo.setBuildVersionNo(text(n, "buildVersionNo"));
                    vo.setBuildBuildVersion(n.path("buildBuildVersion").asInt(0));
                    vo.setBuildType(n.path("buildType").asInt(0)); // 1 iOS, 2 Android
                    vo.setBuildIdentifier(text(n, "buildIdentifier"));
                    vo.setBuildFileSize(n.path("buildFileSize").asLong(0));
                    vo.setBuildCreated(text(n, "buildCreated"));
                    vo.setBuildUpdateDescription(text(n, "buildUpdateDescription"));
                    vo.setPlatformLabel(vo.getBuildType() == 1 ? "iOS" : (vo.getBuildType() == 2 ? "Android" : "unknown"));
                    out.add(vo);
                }
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("拉取蒲公英版本列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载指定 build 并安装到设备；可选先卸载同包名。
     */
    public InstallResult downloadAndInstall(String deviceId, String buildKey, boolean uninstallFirst) {
        ensureConfigured();
        if (deviceId == null || deviceId.isBlank() || buildKey == null || buildKey.isBlank()) {
            throw new IllegalArgumentException("deviceId / buildKey 不能为空");
        }
        Device device = deviceMapper.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }

        Path file = downloadBuild(buildKey);
        try {
            String pkgHint = guessPackageFromFileName(file.getFileName().toString());
            if (uninstallFirst && pkgHint != null) {
                appPackageInstallService.uninstallPackage(device, pkgHint);
            } else if (uninstallFirst) {
                // 若无法从文件名猜包名，尝试用 build 详情 identifier
                PgyerBuildVo meta = viewBuild(buildKey);
                if (meta != null && meta.getBuildIdentifier() != null && !meta.getBuildIdentifier().isBlank()) {
                    appPackageInstallService.uninstallPackage(device, meta.getBuildIdentifier());
                }
            }

            boolean ok = appPackageInstallService.installLocalPackage(device, file);
            InstallResult r = new InstallResult();
            r.setSuccess(ok);
            r.setLocalPath(file.toString());
            r.setMessage(ok ? "蒲公英包安装成功" : "蒲公英包下载成功但安装失败，请检查签名/平台是否匹配设备");
            return r;
        } finally {
            // 保留本地包便于排查；如需清理可改配置
        }
    }

    public Path downloadBuild(String buildKey) {
        ensureConfigured();
        try {
            Files.createDirectories(Path.of(downloadDir));
            String url = trimSlash(apiBase) + "/apiv2/app/install?_api_key=" + enc(apiKey)
                    + "&buildKey=" + enc(buildKey);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("下载失败 HTTP " + resp.statusCode());
            }
            String ext = ".bin";
            String cd = resp.headers().firstValue("Content-Disposition").orElse("");
            String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (cd.toLowerCase(Locale.ROOT).contains(".ipa") || ct.contains("iphone") || ct.contains("ios")) {
                ext = ".ipa";
            } else if (cd.toLowerCase(Locale.ROOT).contains(".apk") || ct.contains("android") || ct.contains("apk")) {
                ext = ".apk";
            } else if (cd.contains(".")) {
                int i = cd.lastIndexOf('.');
                if (i > 0 && i < cd.length() - 1) {
                    String e = cd.substring(i).replace("\"", "").trim();
                    if (e.length() <= 5) ext = e;
                }
            }
            // Content-Disposition 无扩展时，用 view 信息判断
            if (".bin".equals(ext)) {
                PgyerBuildVo meta = viewBuild(buildKey);
                if (meta != null) {
                    ext = meta.getBuildType() == 1 ? ".ipa" : ".apk";
                }
            }
            Path target = Path.of(downloadDir, "pgyer_" + buildKey + ext);
            try (InputStream in = resp.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(target);
            if (size < 1024) {
                String peek = Files.readString(target, StandardCharsets.UTF_8);
                if (peek.contains("\"code\"") || peek.contains("<html")) {
                    throw new IllegalStateException("下载内容不是安装包（可能 API Key 无效或无权限）: " + peek.substring(0, Math.min(200, peek.length())));
                }
            }
            log.info("蒲公英包已下载: buildKey={}, path={}, size={}", buildKey, target, size);
            return target;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("下载蒲公英包失败: " + e.getMessage(), e);
        }
    }

    private PgyerBuildVo viewBuild(String buildKey) {
        try {
            String body = "_api_key=" + enc(apiKey) + "&buildKey=" + enc(buildKey);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(apiBase) + "/apiv2/app/view"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JSON.readTree(resp.body());
            if (root.path("code").asInt(-1) != 0) {
                return null;
            }
            JsonNode n = root.path("data");
            PgyerBuildVo vo = new PgyerBuildVo();
            vo.setBuildKey(text(n, "buildKey"));
            vo.setBuildName(text(n, "buildName"));
            vo.setBuildVersion(text(n, "buildVersion"));
            vo.setBuildType(n.path("buildType").asInt(0));
            vo.setBuildIdentifier(text(n, "buildIdentifier"));
            return vo;
        } catch (Exception e) {
            log.debug("viewBuild 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String guessPackageFromFileName(String name) {
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String s) {
        if (s == null) return "https://www.pgyer.com";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Data
    public static class PgyerBuildVo {
        private String buildKey;
        private String buildName;
        private String buildVersion;
        private String buildVersionNo;
        private int buildBuildVersion;
        private int buildType;
        private String buildIdentifier;
        private long buildFileSize;
        private String buildCreated;
        private String buildUpdateDescription;
        private String platformLabel;
    }

    @Data
    public static class InstallResult {
        private boolean success;
        private String message;
        private String localPath;
    }
}
