package com.mobile.standalone.service.pack;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.DeviceManager;
import com.mobile.standalone.service.DeviceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * APP 打包任务。
 * <p>
 * 鸿蒙：调 DevEco 的 {@code hvigorw}，签名材料继续用工程里已有的。
 * 完美电竞安卓：固定切 {@code testing} 分支后 {@code assembleDebug}。
 * 完美电竞 iOS：页面选分支，对齐 origin 后 {@code pod install} + {@code xcodebuild} 打 Debug IPA。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppPackService {

    private static final int LOG_DB_CAP = 400_000;
    private static final String SELECT_COLS = """
            id, platform, product, output_type AS outputType, status,
            version_name AS versionName, version_code AS versionCode,
            created_by AS createdBy, command, artifact_name AS artifactName,
            error_message AS errorMessage, git_branch AS gitBranch,
            install_device_id AS installDeviceId,
            install_status AS installStatus, install_message AS installMessage,
            started_at AS startedAt, finished_at AS finishedAt,
            created_at AS createdAt, updated_at AS updatedAt
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DeviceManager deviceManager;
    private final DeviceService deviceService;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "app-pack");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Process> running = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Value("${app.pack.harmony.project-dir:${user.home}/projects/qa_coding/app_coding/app_esports_harmonyos/SteamESports}")
    private String harmonyProjectDir;

    @Value("${app.pack.harmony.hvigorw:}")
    private String hvigorwConfig;

    @Value("${app.pack.harmony.node-home:}")
    private String nodeHomeConfig;

    @Value("${app.pack.harmony.module:entry}")
    private String harmonyModule;

    @Value("${app.pack.harmony.timeout-minutes:40}")
    private int timeoutMinutes;

    /** 鸿蒙打包默认分支；页面可改，每次按所选分支对齐 origin */
    @Value("${app.pack.harmony.branch:dev}")
    private String harmonyBranch;

    @Value("${app.pack.harmony.git-dir:}")
    private String harmonyGitDir;

    @Value("${app.pack.android.project-dir:${user.home}/projects/qa_coding/app_coding/app_esports_android/SteamESports}")
    private String androidProjectDir;

    @Value("${app.pack.android.git-dir:}")
    private String androidGitDir;

    @Value("${app.pack.android.branch:testing}")
    private String androidBranch;

    @Value("${app.pack.android.timeout-minutes:50}")
    private int androidTimeoutMinutes;

    @Value("${app.pack.ios.project-dir:${user.home}/projects/qa_coding/app_coding/esportapp-ios}")
    private String iosProjectDir;

    @Value("${app.pack.ios.git-dir:}")
    private String iosGitDir;

    @Value("${app.pack.ios.branch:testing}")
    private String iosBranch;

    @Value("${app.pack.ios.team-id:Z3U4KP2R7K}")
    private String iosTeamId;

    @Value("${app.pack.ios.timeout-minutes:70}")
    private int iosTimeoutMinutes;

    @Value("${app.pack.output-dir:${user.home}/.mobile-standalone/packs}")
    private String outputDir;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS app_pack_jobs (
                        id VARCHAR(64) PRIMARY KEY,
                        platform VARCHAR(16) NOT NULL COMMENT 'HARMONYOS/ANDROID/IOS',
                        product VARCHAR(32) NOT NULL DEFAULT 'default' COMMENT 'hvigor product：default=Release, debug=Debug',
                        output_type VARCHAR(8) NOT NULL DEFAULT 'HAP' COMMENT 'HAP/APP',
                        status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCESS/FAILED/CANCELLED',
                        version_name VARCHAR(64) DEFAULT NULL,
                        version_code VARCHAR(32) DEFAULT NULL,
                        created_by VARCHAR(64) DEFAULT NULL,
                        command TEXT DEFAULT NULL,
                        log_text MEDIUMTEXT DEFAULT NULL,
                        artifact_path VARCHAR(1024) DEFAULT NULL,
                        artifact_name VARCHAR(255) DEFAULT NULL,
                        error_message VARCHAR(1024) DEFAULT NULL,
                        install_device_id VARCHAR(128) DEFAULT NULL,
                        install_status VARCHAR(32) DEFAULT NULL,
                        install_message VARCHAR(512) DEFAULT NULL,
                        started_at DATETIME DEFAULT NULL,
                        finished_at DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_pack_created (created_at),
                        INDEX idx_pack_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("install_device_id",
                    "ALTER TABLE app_pack_jobs ADD COLUMN install_device_id VARCHAR(128) DEFAULT NULL");
            ensureColumn("install_status",
                    "ALTER TABLE app_pack_jobs ADD COLUMN install_status VARCHAR(32) DEFAULT NULL");
            ensureColumn("install_message",
                    "ALTER TABLE app_pack_jobs ADD COLUMN install_message VARCHAR(512) DEFAULT NULL");
            ensureColumn("git_branch",
                    "ALTER TABLE app_pack_jobs ADD COLUMN git_branch VARCHAR(128) DEFAULT NULL");
            // 进程没了，库里还挂着 RUNNING 的任务永远结束不了，页面会一直显示「有人在打」
            int n = jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET status = 'FAILED',
                           error_message = '服务重启，任务中断',
                           finished_at = NOW(),
                           updated_at = NOW()
                     WHERE status IN ('QUEUED', 'RUNNING')
                    """);
            if (n > 0) {
                log.warn("[app-pack] 已把 {} 条重启前未完成的任务标失败", n);
            }
            log.info("[app-pack] 表已就绪");
        } catch (Exception e) {
            log.warn("[app-pack] 建表失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running.values().forEach(Process::destroyForcibly);
        pool.shutdownNow();
    }

    public Map<String, Object> options() {
        Map<String, Object> android = androidOptions();
        Map<String, Object> ios = iosOptions();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platforms", List.of(
                Map.of("id", "HARMONYOS", "label", "鸿蒙", "ready", true),
                Map.of("id", "ANDROID", "label", "完美电竞安卓",
                        "ready", Boolean.TRUE.equals(android.get("ready")),
                        "hint", String.valueOf(android.getOrDefault("hint", ""))),
                Map.of("id", "IOS", "label", "完美电竞 iOS",
                        "ready", Boolean.TRUE.equals(ios.get("ready")),
                        "hint", String.valueOf(ios.getOrDefault("hint", "")))));
        Path project = Path.of(harmonyProjectDir).toAbsolutePath().normalize();
        String hvigorw = HarmonyPackTools.hvigorw(hvigorwConfig);
        String nodeHome = HarmonyPackTools.nodeHome(nodeHomeConfig);
        boolean projectOk = Files.isDirectory(project)
                && Files.isRegularFile(project.resolve("build-profile.json5"));
        List<HarmonyBuildProfile.Product> products = projectOk
                ? HarmonyBuildProfile.products(project) : List.of();
        Map<String, HarmonyBuildProfile.Product> byName = new LinkedHashMap<>();
        for (HarmonyBuildProfile.Product p : products) {
            byName.put(p.name(), p);
        }
        // Testing 不在 products 里，是 buildMode=devTest；产物目录复用 debug
        List<Map<String, Object>> productVos = new ArrayList<>();
        productVos.add(harmonyPackKindVo(
                HarmonyPackCommand.KIND_TESTING,
                byName.get(HarmonyPackCommand.KIND_DEBUG),
                byName.get(HarmonyPackCommand.KIND_RELEASE),
                "buildMode=devTest，对应 DevEco 测试包（可切测试环境）"));
        productVos.add(harmonyPackKindVo(
                HarmonyPackCommand.KIND_DEBUG,
                byName.get(HarmonyPackCommand.KIND_DEBUG),
                byName.get(HarmonyPackCommand.KIND_RELEASE),
                null));
        productVos.add(harmonyPackKindVo(
                HarmonyPackCommand.KIND_RELEASE,
                byName.get(HarmonyPackCommand.KIND_RELEASE),
                null,
                null));
        if (productVos.stream().noneMatch(it -> Boolean.TRUE.equals(it.get("signingReady"))) && projectOk) {
            productVos.clear();
            productVos.add(Map.of(
                    "id", HarmonyPackCommand.KIND_TESTING,
                    "label", "Testing",
                    "signingReady", true,
                    "hint", "buildMode=devTest，对应 DevEco 测试包（可切测试环境）"));
        }
        boolean ready = projectOk && hvigorw != null && nodeHome != null;
        String defaultBranch = harmonyBranch == null || harmonyBranch.isBlank()
                ? HarmonyGitSync.DEFAULT_BRANCH : harmonyBranch.trim();
        Map<String, Object> harmony = new LinkedHashMap<>();
        harmony.put("ready", ready);
        harmony.put("projectDir", project.toString());
        harmony.put("projectExists", projectOk);
        harmony.put("hvigorw", hvigorw);
        harmony.put("nodeHome", nodeHome);
        harmony.put("branch", defaultBranch);
        Path gitRoot = HarmonyGitSync.gitRoot(project, harmonyGitDir);
        harmony.put("gitDir", gitRoot == null ? null : gitRoot.toString());
        List<String> branches = HarmonyGitSync.listRemoteBranches(gitRoot);
        if (!branches.contains(defaultBranch)) {
            List<String> withDefault = new ArrayList<>();
            withDefault.add(defaultBranch);
            withDefault.addAll(branches);
            branches = withDefault;
        }
        harmony.put("branches", branches);
        harmony.put("versionName", projectOk ? HarmonyBuildProfile.versionName(project) : null);
        harmony.put("versionCode", projectOk ? HarmonyBuildProfile.versionCode(project) : null);
        harmony.put("products", productVos);
        harmony.put("outputTypes", List.of(
                Map.of("id", "HAP", "label", "HAP（装真机 / 模拟器）"),
                Map.of("id", "APP", "label", "APP（上架包）")));
        if (!projectOk) {
            harmony.put("hint", "找不到鸿蒙工程：" + project
                    + "。把 app.pack.harmony.project-dir 指到 SteamESports 目录。");
        } else if (hvigorw == null) {
            harmony.put("hint", "找不到 hvigorw。请安装 DevEco Studio，或配置 app.pack.harmony.hvigorw。");
        } else if (nodeHome == null) {
            harmony.put("hint", "找不到 DevEco 自带的 Node。请安装 DevEco Studio，或配置 app.pack.harmony.node-home。");
        }
        out.put("harmony", harmony);
        out.put("android", android);
        out.put("ios", ios);
        out.put("busy", busy.get() || hasRunningJob());
        return out;
    }

    private Map<String, Object> androidOptions() {
        Path project = Path.of(androidProjectDir).toAbsolutePath().normalize();
        Path gitRoot = AndroidGitSync.gitRoot(project, androidGitDir);
        Path gradlew = AndroidPackTools.gradlew(project);
        Path keystore = AndroidPackTools.keystore(project);
        String sdk = AndroidPackTools.androidHome();
        String javaHome = AndroidPackTools.javaHome();
        boolean projectOk = Files.isDirectory(project) && gradlew != null;
        boolean gitOk = gitRoot != null;
        boolean ready = projectOk && gitOk && sdk != null && javaHome != null && keystore != null;
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("ready", ready);
        android.put("label", "完美电竞安卓");
        android.put("projectDir", project.toString());
        android.put("projectExists", projectOk);
        android.put("gitDir", gitRoot == null ? null : gitRoot.toString());
        android.put("branch", androidBranch == null || androidBranch.isBlank()
                ? AndroidPackCommand.BRANCH : androidBranch.trim());
        android.put("product", AndroidPackCommand.PRODUCT);
        android.put("outputType", AndroidPackCommand.OUTPUT_TYPE);
        android.put("gradlew", gradlew == null ? null : gradlew.toString());
        android.put("androidHome", sdk);
        android.put("javaHome", javaHome);
        android.put("signingReady", keystore != null);
        android.put("versionName", projectOk ? AndroidBuildProfile.versionName(project) : null);
        android.put("versionCode", projectOk ? AndroidBuildProfile.versionCode(project) : null);
        if (!projectOk) {
            android.put("hint", "找不到完美电竞安卓工程：" + project
                    + "。把 app.pack.android.project-dir 指到 SteamESports 目录。");
        } else if (!gitOk) {
            android.put("hint", "找不到安卓 Git 仓库。把 app.pack.android.git-dir 指到 app_esports_android。");
        } else if (sdk == null) {
            android.put("hint", "找不到 Android SDK。请安装 Android Studio，或设置 ANDROID_HOME。");
        } else if (javaHome == null) {
            android.put("hint", "找不到 JDK。请安装 Android Studio / JDK 17，或设置 JAVA_HOME。");
        } else if (keystore == null) {
            android.put("hint", "找不到签名文件 app/sign_esports_app.keystore。");
        } else {
            android.put("hint", "固定打 testing 分支的 debug 包，页面上不能改。");
        }
        return android;
    }

    private Map<String, Object> iosOptions() {
        Path project = Path.of(iosProjectDir).toAbsolutePath().normalize();
        Path gitRoot = IosGitSync.gitRoot(project, iosGitDir);
        Path workspace = IosPackTools.workspace(project);
        String xcode = IosPackTools.xcodebuild();
        String pod = IosPackTools.pod();
        boolean xcodeOk = IosPackTools.xcodeReady();
        boolean signingOk = IosPackTools.developmentIdentityPresent();
        boolean projectOk = Files.isDirectory(project)
                && (workspace != null || Files.isRegularFile(project.resolve("Podfile")));
        boolean gitOk = gitRoot != null;
        boolean ready = projectOk && gitOk && xcodeOk && pod != null && signingOk;
        String defaultBranch = iosBranch == null || iosBranch.isBlank()
                ? IosPackCommand.DEFAULT_BRANCH : iosBranch.trim();
        Map<String, Object> ios = new LinkedHashMap<>();
        ios.put("ready", ready);
        ios.put("label", "完美电竞 iOS");
        ios.put("projectDir", project.toString());
        ios.put("projectExists", projectOk);
        ios.put("gitDir", gitRoot == null ? null : gitRoot.toString());
        ios.put("branch", defaultBranch);
        List<String> branches = IosGitSync.listRemoteBranches(gitRoot);
        if (!branches.contains(defaultBranch)) {
            List<String> withDefault = new ArrayList<>();
            withDefault.add(defaultBranch);
            withDefault.addAll(branches);
            branches = withDefault;
        }
        ios.put("branches", branches);
        ios.put("product", IosPackCommand.PRODUCT);
        ios.put("outputType", IosPackCommand.OUTPUT_TYPE);
        ios.put("scheme", IosPackCommand.SCHEME);
        ios.put("workspace", workspace == null ? null : workspace.toString());
        ios.put("xcodebuild", xcode);
        ios.put("pod", pod);
        ios.put("signingReady", signingOk);
        ios.put("teamId", iosTeamId);
        ios.put("versionName", projectOk ? IosBuildProfile.versionName(project) : null);
        ios.put("versionCode", projectOk ? IosBuildProfile.versionCode(project) : null);
        if (!projectOk) {
            ios.put("hint", "找不到完美电竞 iOS 工程：" + project
                    + "。把 app.pack.ios.project-dir 指到 esportapp-ios。");
        } else if (!gitOk) {
            ios.put("hint", "找不到 iOS Git 仓库。把 app.pack.ios.git-dir 指到 esportapp-ios。");
        } else if (xcode == null || !xcodeOk) {
            ios.put("hint", "找不到可用的 Xcode / xcodebuild。请安装 Xcode 并执行 xcode-select。");
        } else if (pod == null) {
            ios.put("hint", "找不到 CocoaPods（pod）。请 brew install cocoapods，或把 pod 加到 PATH。");
        } else if (!signingOk) {
            ios.put("hint", "钥匙串里没有 Apple Development 证书，development IPA 签不了。"
                    + "用 Xcode 登录 Team " + iosTeamId + " 下过一次自动签名即可。");
        } else {
            ios.put("hint", "按所选分支对齐 origin 后打 Debug / development IPA，可装到已注册的开发设备。");
        }
        return ios;
    }

    public List<Map<String, Object>> list(String platform) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM app_pack_jobs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (platform != null && !platform.isBlank()) {
            sql.append(" AND platform = ?");
            args.add(platform.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY created_at DESC LIMIT 50");
        try {
            return jdbcTemplate.queryForList(sql.toString(), args.toArray());
        } catch (Exception e) {
            log.debug("[app-pack] 列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT " + SELECT_COLS + ", log_text AS logText FROM app_pack_jobs WHERE id = ?",
                    id.trim());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("[app-pack] 查询失败: {}", e.getMessage());
            return null;
        }
    }

    public Path artifactFile(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            String path = jdbcTemplate.queryForObject(
                    "SELECT artifact_path FROM app_pack_jobs WHERE id = ?", String.class, id.trim());
            if (path == null || path.isBlank()) {
                return null;
            }
            Path p = Path.of(path).toAbsolutePath().normalize();
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public String start(String platform, String product, String outputType, String createdBy) {
        return start(platform, product, outputType, createdBy, null, null);
    }

    public String start(String platform, String product, String outputType, String createdBy, String deviceId) {
        return start(platform, product, outputType, createdBy, deviceId, null);
    }

    public String start(String platform, String product, String outputType, String createdBy,
                        String deviceId, String branch) {
        String plat = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
        if ("ANDROID".equals(plat)) {
            return startAndroid(createdBy, blankToNull(deviceId));
        }
        if ("IOS".equals(plat)) {
            return startIos(createdBy, blankToNull(deviceId), branch);
        }
        if (!"HARMONYOS".equals(plat)) {
            throw new IllegalArgumentException("目前只接了鸿蒙、完美电竞安卓和完美电竞 iOS");
        }
        Map<String, Object> opts = options();
        @SuppressWarnings("unchecked")
        Map<String, Object> harmony = (Map<String, Object>) opts.get("harmony");
        if (!Boolean.TRUE.equals(harmony.get("ready"))) {
            throw new IllegalStateException(String.valueOf(harmony.getOrDefault("hint", "鸿蒙打包环境未就绪")));
        }
        String defaultBranch = String.valueOf(harmony.getOrDefault("branch", HarmonyGitSync.DEFAULT_BRANCH));
        String gitBranch = HarmonyGitSync.normalizeBranch(branch, defaultBranch);
        String packKind = HarmonyPackCommand.normalizePackKind(product);
        String outType = HarmonyPackCommand.normalizeOutputType(outputType);
        String did = blankToNull(deviceId);
        if (did != null) {
            if (!"HAP".equals(outType)) {
                throw new IllegalStateException("只有 HAP 能装真机；APP 上架包请下载后自行处理");
            }
            requireHarmonyDevice(did);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) harmony.get("products");
        Map<String, Object> chosen = products.stream()
                .filter(it -> packKind.equals(String.valueOf(it.get("id"))))
                .findFirst()
                .orElse(null);
        if (chosen != null && Boolean.FALSE.equals(chosen.get("signingReady"))) {
            throw new IllegalStateException(String.valueOf(chosen.get("hint")));
        }
        acquireLock();

        Path project = Path.of(harmonyProjectDir).toAbsolutePath().normalize();
        List<String> args = HarmonyPackCommand.hvigorArgs(packKind, outType, harmonyModule);
        String hvigorw = HarmonyPackTools.hvigorw(hvigorwConfig);
        String command = "git checkout " + gitBranch + " && " + hvigorw + " " + String.join(" ", args);
        String id = UUID.randomUUID().toString().replace("-", "");
        String versionName = HarmonyBuildProfile.versionName(project);
        String versionCode = HarmonyBuildProfile.versionCode(project);
        String installStatus = did == null ? "SKIPPED" : "PENDING";
        try {
            jdbcTemplate.update("""
                            INSERT INTO app_pack_jobs
                                (id, platform, product, output_type, status, version_name, version_code,
                                 created_by, command, log_text, install_device_id, install_status,
                                 git_branch, created_at, updated_at)
                            VALUES (?, 'HARMONYOS', ?, ?, 'QUEUED', ?, ?, ?, ?, '', ?, ?, ?, NOW(), NOW())
                            """,
                    id, packKind, outType, versionName, versionCode, createdBy, command,
                    did, installStatus, gitBranch);
        } catch (Exception e) {
            busy.set(false);
            throw new IllegalStateException("写入打包任务失败：" + e.getMessage());
        }
        pool.execute(() -> runHarmony(id, project, packKind, outType, hvigorw, args, did, gitBranch));
        return id;
    }

    private static Map<String, Object> harmonyPackKindVo(
            String kind,
            HarmonyBuildProfile.Product primary,
            HarmonyBuildProfile.Product fallback,
            String extraHint) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", kind);
        vo.put("label", HarmonyPackCommand.label(kind));
        HarmonyBuildProfile.Product sign = primary != null ? primary : fallback;
        boolean ready = sign == null || sign.signingReady();
        vo.put("signingReady", ready);
        if (!ready && sign != null) {
            vo.put("hint", "本机找不到该产品的签名文件（" + sign.signingConfig()
                    + "）。Testing/Debug 请用 DevEco 自动签名，或把 signingConfig 指到本机已有材料。");
        } else if (extraHint != null && !extraHint.isBlank()) {
            vo.put("hint", extraHint);
        }
        return vo;
    }

    private String startAndroid(String createdBy, String deviceId) {
        Map<String, Object> android = androidOptions();
        if (!Boolean.TRUE.equals(android.get("ready"))) {
            throw new IllegalStateException(String.valueOf(android.getOrDefault("hint", "安卓打包环境未就绪")));
        }
        if (deviceId != null) {
            requireAndroidDevice(deviceId);
        }
        acquireLock();
        Path project = Path.of(androidProjectDir).toAbsolutePath().normalize();
        Path gradlew = AndroidPackTools.gradlew(project);
        String branch = String.valueOf(android.get("branch"));
        List<String> args = AndroidPackCommand.gradleArgs();
        String command = "git checkout " + branch + " && " + gradlew + " " + String.join(" ", args);
        String id = UUID.randomUUID().toString().replace("-", "");
        String versionName = AndroidBuildProfile.versionName(project);
        String versionCode = AndroidBuildProfile.versionCode(project);
        String installStatus = deviceId == null ? "SKIPPED" : "PENDING";
        try {
            jdbcTemplate.update("""
                            INSERT INTO app_pack_jobs
                                (id, platform, product, output_type, status, version_name, version_code,
                                 created_by, command, log_text, install_device_id, install_status,
                                 created_at, updated_at)
                            VALUES (?, 'ANDROID', ?, ?, 'QUEUED', ?, ?, ?, ?, '', ?, ?, NOW(), NOW())
                            """,
                    id, AndroidPackCommand.PRODUCT, AndroidPackCommand.OUTPUT_TYPE,
                    versionName, versionCode, createdBy, command, deviceId, installStatus);
        } catch (Exception e) {
            busy.set(false);
            throw new IllegalStateException("写入打包任务失败：" + e.getMessage());
        }
        pool.execute(() -> runAndroid(id, project, branch, gradlew, args, deviceId));
        return id;
    }

    private String startIos(String createdBy, String deviceId, String branch) {
        Map<String, Object> ios = iosOptions();
        if (!Boolean.TRUE.equals(ios.get("ready"))) {
            throw new IllegalStateException(String.valueOf(ios.getOrDefault("hint", "iOS 打包环境未就绪")));
        }
        if (deviceId != null) {
            requireIosDevice(deviceId);
        }
        String defaultBranch = String.valueOf(ios.getOrDefault("branch", IosPackCommand.DEFAULT_BRANCH));
        String gitBranch = IosGitSync.normalizeBranch(branch, defaultBranch);
        acquireLock();
        Path project = Path.of(iosProjectDir).toAbsolutePath().normalize();
        String command = "git checkout " + gitBranch + " && pod install && xcodebuild archive && xcodebuild -exportArchive";
        String id = UUID.randomUUID().toString().replace("-", "");
        String versionName = IosBuildProfile.versionName(project);
        String versionCode = IosBuildProfile.versionCode(project);
        String installStatus = deviceId == null ? "SKIPPED" : "PENDING";
        try {
            jdbcTemplate.update("""
                            INSERT INTO app_pack_jobs
                                (id, platform, product, output_type, status, version_name, version_code,
                                 created_by, command, log_text, install_device_id, install_status,
                                 git_branch, created_at, updated_at)
                            VALUES (?, 'IOS', ?, ?, 'QUEUED', ?, ?, ?, ?, '', ?, ?, ?, NOW(), NOW())
                            """,
                    id, IosPackCommand.PRODUCT, IosPackCommand.OUTPUT_TYPE,
                    versionName, versionCode, createdBy, command, deviceId, installStatus, gitBranch);
        } catch (Exception e) {
            busy.set(false);
            throw new IllegalStateException("写入打包任务失败：" + e.getMessage());
        }
        pool.execute(() -> runIos(id, project, gitBranch, deviceId));
        return id;
    }

    /** 已成功的包装到指定设备（安卓 APK / 鸿蒙 HAP / iOS IPA；可补装 / 换机） */
    public void installArtifact(String id, String deviceId) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("缺少任务 id");
        }
        String did = blankToNull(deviceId);
        if (did == null) {
            throw new IllegalArgumentException("请选择要安装的设备");
        }
        Map<String, Object> row = get(id.trim());
        if (row == null) {
            throw new IllegalStateException("找不到打包任务");
        }
        if (!"SUCCESS".equals(String.valueOf(row.get("status")))) {
            throw new IllegalStateException("只有打包成功的任务才能安装");
        }
        String plat = String.valueOf(row.get("platform"));
        Path artifact = artifactFile(id.trim());
        if (artifact == null) {
            throw new IllegalStateException("找不到产物文件");
        }
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET install_device_id = ?, install_status = 'PENDING',
                       install_message = NULL, updated_at = NOW()
                 WHERE id = ?
                """, did, id.trim());
        StringBuilder logBuf = new StringBuilder();
        appendLine(logBuf, "[平台] 手动安装到 " + did);
        flushLog(id.trim(), logBuf);
        if ("ANDROID".equalsIgnoreCase(plat)) {
            requireAndroidDevice(did);
            installApkToDevice(id.trim(), artifact, did, logBuf);
            return;
        }
        if ("HARMONYOS".equalsIgnoreCase(plat)) {
            if (!"HAP".equalsIgnoreCase(String.valueOf(row.get("outputType")))) {
                throw new IllegalStateException("只有 HAP 能装真机");
            }
            requireHarmonyDevice(did);
            installHapToDevice(id.trim(), artifact, did, logBuf);
            return;
        }
        if ("IOS".equalsIgnoreCase(plat)) {
            requireIosDevice(did);
            installIpaToDevice(id.trim(), artifact, did, logBuf);
            return;
        }
        throw new IllegalStateException("目前只支持安卓 / 鸿蒙 / iOS 装机");
    }

    private void acquireLock() {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("已有打包任务在跑，等它结束再点。同时打会抢同一台机器上的工程目录");
        }
        if (hasRunningJob()) {
            busy.set(false);
            throw new IllegalStateException("已有打包任务在跑，等它结束再点。同时打会抢同一台机器上的工程目录");
        }
    }

    public boolean cancel(String id) {
        Map<String, Object> row = get(id);
        if (row == null) {
            return false;
        }
        String status = String.valueOf(row.get("status"));
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw new IllegalStateException("任务已经结束，不能取消");
        }
        Process proc = running.get(id);
        if (proc != null) {
            proc.destroyForcibly();
        }
        appendLog(id, "\n[平台] 用户取消\n");
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET status = 'CANCELLED', error_message = '已取消',
                       finished_at = NOW(), updated_at = NOW()
                 WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, id);
        busy.set(false);
        return true;
    }

    /** 删掉记录，顺带清产物目录；若还在跑会先强停。 */
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String jobId = id.trim();
        Map<String, Object> row = get(jobId);
        if (row == null) {
            return false;
        }
        String status = String.valueOf(row.get("status"));
        if ("QUEUED".equals(status) || "RUNNING".equals(status)) {
            Process proc = running.get(jobId);
            if (proc != null) {
                proc.destroyForcibly();
            }
            busy.set(false);
        }
        running.remove(jobId);
        deleteJobOutputDir(jobId);
        try {
            String path = jdbcTemplate.queryForObject(
                    "SELECT artifact_path FROM app_pack_jobs WHERE id = ?", String.class, jobId);
            if (path != null && !path.isBlank()) {
                deleteIfUnderOutput(Path.of(path));
            }
        } catch (Exception ignored) {
            // 没有产物或行已不在，继续删库
        }
        int n = jdbcTemplate.update("DELETE FROM app_pack_jobs WHERE id = ?", jobId);
        return n > 0;
    }

    private void deleteJobOutputDir(String jobId) {
        Path root = Path.of(outputDir).toAbsolutePath().normalize();
        Path dir = root.resolve(jobId).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 删不掉就留着，记录已经不在了
                    }
                });
            }
        } catch (Exception e) {
            log.debug("[app-pack] 清理产物目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private void deleteIfUnderOutput(Path file) {
        if (file == null) {
            return;
        }
        Path root = Path.of(outputDir).toAbsolutePath().normalize();
        Path p = file.toAbsolutePath().normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            log.debug("[app-pack] 清理产物失败 {}: {}", p, e.getMessage());
        }
    }

    private void runHarmony(String id, Path project, String product, String outputType,
                            String hvigorw, List<String> args, String deviceId, String branch) {
        StringBuilder logBuf = new StringBuilder();
        try {
            markRunning(id);
            Path gitRoot = HarmonyGitSync.gitRoot(project, harmonyGitDir);
            if (gitRoot == null) {
                throw new IllegalStateException("找不到鸿蒙 Git 仓库。把 app.pack.harmony.git-dir 指到 app_esports_harmonyos");
            }
            String packKind = HarmonyPackCommand.normalizePackKind(product);
            String hvigorProduct = HarmonyPackCommand.hvigorProduct(packKind);
            String gitBranch = HarmonyGitSync.normalizeBranch(branch, HarmonyGitSync.DEFAULT_BRANCH);
            appendLine(logBuf, "[平台] Git 仓库 " + gitRoot);
            appendLine(logBuf, "[平台] 切到 " + gitBranch + " 并对齐 origin/" + gitBranch);
            flushLog(id, logBuf);
            HarmonyGitSync.Result git = HarmonyGitSync.sync(gitRoot, gitBranch);
            appendLine(logBuf, "[平台] 当前 " + git.branch() + " @" + git.commit());
            appendLine(logBuf, "[平台] 包类型 " + HarmonyPackCommand.label(packKind)
                    + "（product=" + hvigorProduct
                    + ", buildMode=" + HarmonyPackCommand.buildMode(packKind) + "）");
            flushLog(id, logBuf);

            // 清掉旧 hap，再记 beforeMtime：否则增量 UP-TO-DATE 会把旧包冒充新包
            HarmonyPackArtifacts.clearHapOutputs(project, hvigorProduct);
            Path before = HarmonyPackArtifacts.find(project, hvigorProduct, outputType).orElse(null);
            long beforeMtime = before == null ? 0L : mtime(before);

            String nodeHome = HarmonyPackTools.nodeHome(nodeHomeConfig);
            List<String> cmd = new ArrayList<>();
            cmd.add(hvigorw);
            cmd.addAll(args);
            appendLine(logBuf, "[平台] 工作目录 " + project);
            appendLine(logBuf, "[平台] " + String.join(" ", cmd));
            flushLog(id, logBuf);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(project.toFile());
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            if (nodeHome != null) {
                env.put("NODE_HOME", nodeHome);
            }
            String sdk = HarmonyPackTools.sdkHome();
            if (sdk != null) {
                env.put("DEVECO_SDK_HOME", sdk);
            }
            env.put("PATH", HarmonyPackTools.joinPath(
                    HarmonyPackTools.extraPathEntries(nodeHome), env.get("PATH")));

            int code = executeLogged(id, pb, logBuf, timeoutMinutes);
            appendLine(logBuf, "[平台] hvigor 退出码 " + code);
            flushLog(id, logBuf);
            if (code != 0) {
                throw new IllegalStateException("hvigor 失败，退出码 " + code);
            }
            Path artifact = HarmonyPackArtifacts.find(project, hvigorProduct, outputType)
                    .orElseThrow(() -> new IllegalStateException("构建成功但没找到 hap/app 产物"));
            if (mtime(artifact) <= beforeMtime) {
                throw new IllegalStateException("构建成功，但产物时间戳没有更新，不敢把旧包当新包发出去。请先 clean 再打");
            }
            String version = HarmonyBuildProfile.versionName(project);
            Path stored = copyArtifact(id, artifact, packKind, version);
            markSuccess(id, stored);
            appendLine(logBuf, "[平台] 产物 " + stored.getFileName());
            flushLog(id, logBuf);
            if (deviceId != null && !deviceId.isBlank() && "HAP".equalsIgnoreCase(outputType)) {
                installHapToDevice(id, stored, deviceId.trim(), logBuf);
            }
        } catch (Exception e) {
            markFailed(id, logBuf, e);
        } finally {
            running.remove(id);
            busy.set(false);
        }
    }

    private void runAndroid(String id, Path project, String branch, Path gradlew, List<String> args,
                            String deviceId) {
        Path before = AndroidPackArtifacts.findDebugApk(project).orElse(null);
        long beforeMtime = before == null ? 0L : mtime(before);
        StringBuilder logBuf = new StringBuilder();
        try {
            markRunning(id);
            Path gitRoot = AndroidGitSync.gitRoot(project, androidGitDir);
            appendLine(logBuf, "[平台] Git 仓库 " + gitRoot);
            appendLine(logBuf, "[平台] 切到 " + branch + " 并快进 origin/" + branch);
            flushLog(id, logBuf);
            AndroidGitSync.Result git = AndroidGitSync.syncTesting(gitRoot, branch);
            appendLine(logBuf, "[平台] 当前 " + git.branch() + " @" + git.commit());

            Path captureXml = AndroidLocalCapturePatch.ensure(project);
            appendLine(logBuf, "[平台] 已确保本地抓包配置（debug 信任用户证书）：" + captureXml);
            flushLog(id, logBuf);

            String versionName = AndroidBuildProfile.versionName(project);
            String versionCode = AndroidBuildProfile.versionCode(project);
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET version_name = ?, version_code = ?, updated_at = NOW()
                     WHERE id = ?
                    """, versionName, versionCode, id);

            List<String> cmd = new ArrayList<>();
            if (Files.isExecutable(gradlew)) {
                cmd.add(gradlew.toString());
            } else {
                cmd.add("sh");
                cmd.add(gradlew.toString());
            }
            cmd.addAll(args);
            appendLine(logBuf, "[平台] 工作目录 " + project);
            appendLine(logBuf, "[平台] " + String.join(" ", cmd));
            flushLog(id, logBuf);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(project.toFile());
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String javaHome = AndroidPackTools.javaHome();
            if (javaHome != null) {
                env.put("JAVA_HOME", javaHome);
            }
            String sdk = AndroidPackTools.androidHome();
            if (sdk != null) {
                env.put("ANDROID_HOME", sdk);
                env.put("ANDROID_SDK_ROOT", sdk);
            }
            env.put("PATH", HarmonyPackTools.joinPath(
                    AndroidPackTools.extraPathEntries(), env.get("PATH")));

            int code = executeLogged(id, pb, logBuf, androidTimeoutMinutes);
            appendLine(logBuf, "[平台] gradle 退出码 " + code);
            flushLog(id, logBuf);
            if (code != 0) {
                throw new IllegalStateException("gradle 失败，退出码 " + code);
            }
            Path artifact = AndroidPackArtifacts.findDebugApk(project)
                    .orElseThrow(() -> new IllegalStateException("构建成功但没找到 debug apk"));
            if (mtime(artifact) < beforeMtime) {
                throw new IllegalStateException("构建成功，但产物时间戳没有更新，不敢把旧包当新包发出去");
            }
            Path stored = copyArtifact(id, artifact, AndroidPackCommand.PRODUCT, versionName);
            markSuccess(id, stored);
            appendLine(logBuf, "[平台] 产物 " + stored.getFileName());
            flushLog(id, logBuf);
            if (deviceId != null && !deviceId.isBlank()) {
                installApkToDevice(id, stored, deviceId.trim(), logBuf);
            }
        } catch (Exception e) {
            markFailed(id, logBuf, e);
        } finally {
            running.remove(id);
            busy.set(false);
        }
    }

    private void runIos(String id, Path project, String branch, String deviceId) {
        StringBuilder logBuf = new StringBuilder();
        try {
            markRunning(id);
            Path gitRoot = IosGitSync.gitRoot(project, iosGitDir);
            if (gitRoot == null) {
                throw new IllegalStateException("找不到 iOS Git 仓库。把 app.pack.ios.git-dir 指到 esportapp-ios");
            }
            String gitBranch = IosGitSync.normalizeBranch(branch, IosPackCommand.DEFAULT_BRANCH);
            appendLine(logBuf, "[平台] Git 仓库 " + gitRoot);
            appendLine(logBuf, "[平台] 切到 " + gitBranch + " 并对齐 origin/" + gitBranch);
            flushLog(id, logBuf);
            AndroidGitSync.Result git = IosGitSync.sync(gitRoot, gitBranch);
            appendLine(logBuf, "[平台] 当前 " + git.branch() + " @" + git.commit());
            flushLog(id, logBuf);

            String versionName = IosBuildProfile.versionName(project);
            String versionCode = IosBuildProfile.versionCode(project);
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET version_name = ?, version_code = ?, updated_at = NOW()
                     WHERE id = ?
                    """, versionName, versionCode, id);

            String pod = IosPackTools.pod();
            if (pod == null) {
                throw new IllegalStateException("找不到 pod");
            }
            List<String> podCmd = List.of(pod, "install");
            appendLine(logBuf, "[平台] " + String.join(" ", podCmd));
            flushLog(id, logBuf);
            ProcessBuilder podPb = new ProcessBuilder(podCmd);
            podPb.directory(project.toFile());
            podPb.redirectErrorStream(true);
            podPb.environment().put("PATH", HarmonyPackTools.joinPath(
                    IosPackTools.extraPathEntries(), podPb.environment().get("PATH")));
            podPb.environment().put("LANG", "en_US.UTF-8");
            podPb.environment().put("LC_ALL", "en_US.UTF-8");
            int podCode = executeLogged(id, podPb, logBuf, 25);
            appendLine(logBuf, "[平台] pod 退出码 " + podCode);
            flushLog(id, logBuf);
            if (podCode != 0) {
                throw new IllegalStateException("pod install 失败，退出码 " + podCode);
            }

            Path jobDir = Path.of(outputDir).toAbsolutePath().normalize().resolve(id);
            Files.createDirectories(jobDir);
            Path archivePath = jobDir.resolve("esport-app.xcarchive");
            Path exportDir = jobDir.resolve("export");
            Path plist = jobDir.resolve("ExportOptions.plist");
            IosPackCommand.writeDevelopmentExportPlist(plist, iosTeamId);
            Files.createDirectories(exportDir);

            String xcode = IosPackTools.xcodebuild();
            List<String> archiveCmd = new ArrayList<>();
            archiveCmd.add(xcode);
            archiveCmd.addAll(IosPackCommand.archiveArgs(
                    IosPackCommand.WORKSPACE, archivePath.toString(), iosTeamId));
            appendLine(logBuf, "[平台] 工作目录 " + project);
            appendLine(logBuf, "[平台] " + String.join(" ", archiveCmd));
            flushLog(id, logBuf);
            ProcessBuilder archivePb = new ProcessBuilder(archiveCmd);
            archivePb.directory(project.toFile());
            archivePb.redirectErrorStream(true);
            archivePb.environment().put("PATH", HarmonyPackTools.joinPath(
                    IosPackTools.extraPathEntries(), archivePb.environment().get("PATH")));
            archivePb.environment().put("LANG", "en_US.UTF-8");
            int archiveCode = executeLogged(id, archivePb, logBuf, iosTimeoutMinutes);
            appendLine(logBuf, "[平台] xcodebuild archive 退出码 " + archiveCode);
            flushLog(id, logBuf);
            if (archiveCode != 0) {
                throw new IllegalStateException("xcodebuild archive 失败，退出码 " + archiveCode);
            }

            List<String> exportCmd = new ArrayList<>();
            exportCmd.add(xcode);
            exportCmd.addAll(IosPackCommand.exportArgs(
                    archivePath.toString(), exportDir.toString(), plist.toString()));
            appendLine(logBuf, "[平台] " + String.join(" ", exportCmd));
            flushLog(id, logBuf);
            ProcessBuilder exportPb = new ProcessBuilder(exportCmd);
            exportPb.directory(project.toFile());
            exportPb.redirectErrorStream(true);
            exportPb.environment().put("PATH", HarmonyPackTools.joinPath(
                    IosPackTools.extraPathEntries(), exportPb.environment().get("PATH")));
            int exportCode = executeLogged(id, exportPb, logBuf, 15);
            appendLine(logBuf, "[平台] xcodebuild export 退出码 " + exportCode);
            flushLog(id, logBuf);
            if (exportCode != 0) {
                throw new IllegalStateException("xcodebuild exportArchive 失败，退出码 " + exportCode);
            }

            Path artifact = IosPackArtifacts.findIpa(exportDir)
                    .orElseThrow(() -> new IllegalStateException("构建成功但没找到 ipa 产物"));
            Path stored = copyArtifact(id, artifact, IosPackCommand.PRODUCT, versionName);
            markSuccess(id, stored);
            appendLine(logBuf, "[平台] 产物 " + stored.getFileName());
            flushLog(id, logBuf);
            if (deviceId != null && !deviceId.isBlank()) {
                installIpaToDevice(id, stored, deviceId.trim(), logBuf);
            }
        } catch (Exception e) {
            markFailed(id, logBuf, e);
        } finally {
            running.remove(id);
            busy.set(false);
        }
    }

    private void requireAndroidDevice(String deviceId) {
        Device device = deviceService.getDeviceById(deviceId);
        if (device == null) {
            throw new IllegalStateException("设备不存在：" + deviceId);
        }
        if (!Device.isAndroidOrPogAndroid(device.getPlatform())) {
            throw new IllegalStateException("请选安卓设备，当前是 " + device.getPlatform());
        }
        if (device.getStatus() != Device.DeviceStatus.CONNECTED) {
            throw new IllegalStateException("设备未连接：" + deviceId + "。请插上 USB 后点「刷新设备」再试");
        }
    }

    private void requireHarmonyDevice(String deviceId) {
        Device device = deviceService.getDeviceById(deviceId);
        if (device == null) {
            throw new IllegalStateException("设备不存在：" + deviceId);
        }
        if (device.getPlatform() != Device.Platform.HARMONYOS) {
            throw new IllegalStateException("请选鸿蒙设备，当前是 " + device.getPlatform());
        }
        if (device.getStatus() != Device.DeviceStatus.CONNECTED) {
            throw new IllegalStateException("设备未连接：" + deviceId + "。请插上 USB 后点「刷新设备」再试");
        }
    }

    private void requireIosDevice(String deviceId) {
        Device device = deviceService.getDeviceById(deviceId);
        if (device == null) {
            throw new IllegalStateException("设备不存在：" + deviceId);
        }
        if (!Device.usesIosAutomation(device.getPlatform())) {
            throw new IllegalStateException("请选 iOS 设备，当前是 " + device.getPlatform());
        }
        if (device.getStatus() != Device.DeviceStatus.CONNECTED) {
            throw new IllegalStateException("设备未连接：" + deviceId + "。请插上 USB 后点「刷新设备」再试");
        }
    }

    private void installApkToDevice(String id, Path apk, String deviceId, StringBuilder logBuf) {
        appendLine(logBuf, "[平台] 安装到设备 " + deviceId + " …");
        flushLog(id, logBuf);
        String detail = deviceManager.installAndroidAppDetailed(deviceId, apk.toString());
        if (detail == null) {
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET install_device_id = ?, install_status = 'SUCCESS',
                           install_message = ?, updated_at = NOW()
                     WHERE id = ?
                    """, deviceId, "已安装到 " + deviceId, id);
            replaceLogAfterInstall(id, logBuf, apk, deviceId, true, null);
            return;
        }
        String msg = detail.length() > 500 ? detail.substring(0, 500) : detail;
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET install_device_id = ?, install_status = 'FAILED',
                       install_message = ?, updated_at = NOW()
                 WHERE id = ?
                """, deviceId, msg, id);
        replaceLogAfterInstall(id, logBuf, apk, deviceId, false, msg);
    }

    private void installHapToDevice(String id, Path hap, String deviceId, StringBuilder logBuf) {
        appendLine(logBuf, "[平台] hdc 安装到设备 " + deviceId + " …");
        flushLog(id, logBuf);
        String detail = deviceManager.installHarmonyAppDetailed(deviceId, hap.toString());
        if (detail == null) {
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET install_device_id = ?, install_status = 'SUCCESS',
                           install_message = ?, updated_at = NOW()
                     WHERE id = ?
                    """, deviceId, "已安装到 " + deviceId, id);
            replaceLogAfterInstall(id, logBuf, hap, deviceId, true, null);
            return;
        }
        String msg = detail.length() > 500 ? detail.substring(0, 500) : detail;
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET install_device_id = ?, install_status = 'FAILED',
                       install_message = ?, updated_at = NOW()
                 WHERE id = ?
                """, deviceId, msg, id);
        replaceLogAfterInstall(id, logBuf, hap, deviceId, false, msg);
    }

    private void installIpaToDevice(String id, Path ipa, String deviceId, StringBuilder logBuf) {
        appendLine(logBuf, "[平台] 安装到设备 " + deviceId + " …");
        flushLog(id, logBuf);
        boolean ok = deviceManager.installIOSApp(deviceId, ipa.toString());
        if (ok) {
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET install_device_id = ?, install_status = 'SUCCESS',
                           install_message = ?, updated_at = NOW()
                     WHERE id = ?
                    """, deviceId, "已安装到 " + deviceId, id);
            replaceLogAfterInstall(id, logBuf, ipa, deviceId, true, null);
            return;
        }
        String msg = "xcrun/ideviceinstaller 安装失败。核对这台设备是否在 development 描述文件里，以及是否开了开发者模式";
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET install_device_id = ?, install_status = 'FAILED',
                       install_message = ?, updated_at = NOW()
                 WHERE id = ?
                """, deviceId, msg, id);
        replaceLogAfterInstall(id, logBuf, ipa, deviceId, false, msg);
    }

    /** 安装结束后丢掉 Gradle 长日志，只留几行摘要，避免 MEDIUMTEXT 越积越大 */
    private void replaceLogAfterInstall(String id, StringBuilder logBuf, Path apk,
                                        String deviceId, boolean ok, String err) {
        logBuf.setLength(0);
        appendLine(logBuf, "[平台] 详细构建日志已清理（安装结束后自动清空）");
        if (apk != null) {
            appendLine(logBuf, "[平台] 产物 " + apk.getFileName());
        }
        if (ok) {
            appendLine(logBuf, "[平台] 安装成功：" + deviceId);
        } else {
            appendLine(logBuf, "[平台] 安装失败：" + deviceId
                    + (err == null || err.isBlank() ? "" : " → " + err));
        }
        flushLog(id, logBuf);
    }

    private void ensureColumn(String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'app_pack_jobs'
                       AND COLUMN_NAME = ?
                    """, Integer.class, column);
            if (n == null || n == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception e) {
            log.debug("[app-pack] 补列 {} 跳过: {}", column, e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private int executeLogged(String id, ProcessBuilder pb, StringBuilder logBuf, int timeoutMin)
            throws Exception {
        Process proc = pb.start();
        running.put(id, proc);
        // 日志另线程读：构建卡住时如果主线程堵在 readLine，超时永远触发不了
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int sinceFlush = 0;
                while ((line = reader.readLine()) != null) {
                    synchronized (logBuf) {
                        appendLine(logBuf, line);
                    }
                    sinceFlush++;
                    if (sinceFlush >= 20) {
                        synchronized (logBuf) {
                            flushLog(id, logBuf);
                        }
                        sinceFlush = 0;
                    }
                }
            } catch (Exception e) {
                log.debug("[app-pack] 读日志结束: {}", e.getMessage());
            }
        }, "app-pack-log-" + id);
        logThread.setDaemon(true);
        logThread.start();
        boolean finished = proc.waitFor(Math.max(5, timeoutMin), TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            logThread.join(2000);
            throw new IllegalStateException("打包超过 " + timeoutMin + " 分钟，已强制结束");
        }
        logThread.join(5000);
        return proc.exitValue();
    }

    private void markRunning(String id) {
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET status = 'RUNNING', started_at = NOW(), updated_at = NOW()
                 WHERE id = ?
                """, id);
    }

    private void markSuccess(String id, Path stored) {
        jdbcTemplate.update("""
                UPDATE app_pack_jobs
                   SET status = 'SUCCESS', artifact_path = ?, artifact_name = ?,
                       error_message = NULL, finished_at = NOW(), updated_at = NOW()
                 WHERE id = ?
                """, stored.toString(), stored.getFileName().toString(), id);
    }

    private void markFailed(String id, StringBuilder logBuf, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (msg.length() > 1000) {
            msg = msg.substring(0, 1000);
        }
        appendLine(logBuf, "[平台] 失败：" + msg);
        flushLog(id, logBuf);
        String status = "CANCELLED".equals(currentStatus(id)) ? "CANCELLED" : "FAILED";
        if (!"CANCELLED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET status = ?, error_message = ?, finished_at = NOW(), updated_at = NOW()
                     WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                    """, status, msg, id);
        }
        log.warn("[app-pack] 任务 {} 失败: {}", id, msg);
    }

    private Path copyArtifact(String id, Path source, String product, String version) throws Exception {
        Path dir = Path.of(outputDir).toAbsolutePath().normalize().resolve(id);
        Files.createDirectories(dir);
        String ext = source.getFileName().toString();
        int dot = ext.lastIndexOf('.');
        String suffix = dot >= 0 ? ext.substring(dot) : "";
        String name = "SteamESports-"
                + (version == null || version.isBlank() ? "unknown" : version)
                + "-" + product
                + (source.getFileName().toString().contains("unsigned") ? "-unsigned" : "")
                + suffix;
        Path dest = dir.resolve(name);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    private boolean hasRunningJob() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM app_pack_jobs WHERE status IN ('QUEUED', 'RUNNING')",
                    Integer.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String currentStatus(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT status FROM app_pack_jobs WHERE id = ?", String.class, id);
        } catch (Exception e) {
            return null;
        }
    }

    private void appendLine(StringBuilder buf, String line) {
        if (buf.length() > LOG_DB_CAP) {
            buf.delete(0, buf.length() - LOG_DB_CAP + 2000);
            buf.insert(0, "...(前面日志已截断)\n");
        }
        buf.append(line).append('\n');
    }

    private void flushLog(String id, StringBuilder buf) {
        try {
            jdbcTemplate.update(
                    "UPDATE app_pack_jobs SET log_text = ?, updated_at = NOW() WHERE id = ?",
                    buf.toString(), id);
        } catch (Exception e) {
            log.debug("[app-pack] 写日志失败: {}", e.getMessage());
        }
    }

    private void appendLog(String id, String extra) {
        try {
            jdbcTemplate.update("""
                    UPDATE app_pack_jobs
                       SET log_text = CONCAT(IFNULL(log_text, ''), ?), updated_at = NOW()
                     WHERE id = ?
                    """, extra, id);
        } catch (Exception e) {
            log.debug("[app-pack] 追加日志失败: {}", e.getMessage());
        }
    }
}
