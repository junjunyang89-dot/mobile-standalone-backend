package com.mobile.standalone.service.pool;

import com.mobile.standalone.service.DeviceLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 在主控机上启停模拟器。
 *
 * <p><b>不维护「我起的进程」这份名单来判断状态</b>：后端重启一次，名单就没了，
 * 而模拟器还在跑——那之后平台会认为它没起，人点启动又会撞上「AVD 已在运行」。
 * 所以状态的<b>权威来源始终是 adb</b>：adb 里有就是起着的，不管是谁起的、什么时候起的。
 * 进程句柄只用来干一件 adb 干不了的事：<b>回答「起失败了，为什么」</b>。
 *
 * <p><b>关于「用完就销毁」</b>：这个类提供 stop，但不建议做成自动的。
 * 冷启动一个 AVD 要 30~60 秒，而设备池的价值就是「随时有可用的」；
 * 用完就关意味着排队的自动化任务每次都要先等一分钟开机——而自动化是无人值守的，
 * 没人会去点启动。脏状态该用 {@code pm clear} 或 {@code -wipe-data} 解决，
 * 那比关机再开机快一个数量级，也更有针对性。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmulatorLauncher {

    /** 一台模拟器在平台眼里的四种状态 */
    public enum State {
        /** 没在跑 */
        NOT_RUNNING,
        /** 进程起了，但还没开完机 */
        BOOTING,
        /** 开完机、adb 认了，设备池里能看到 */
        READY,
        /** 起失败了，message 里有原因 */
        FAILED
    }

    public record AvdView(
            String name,
            State state,
            String deviceId,
            LocalDateTime startedAt,
            Long elapsedSeconds,
            String message,
            /** 正被占用（跑任务或被人借着）；占着的不给关 */
            boolean busy,
            String occupiedBy,
            /** ANDROID = AVD，IOS = Simulator，HARMONYOS = DevEco 模拟器 */
            String platform,
            /** iOS 来自 simctl runtime；Android AVD 常常没有独立版本串 */
            String version
    ) {
    }

    public record Overview(
            boolean emulatorCliAvailable,
            List<AvdView> avds,
            List<String> runningUnmatched,
            int runningCount,
            int maxRunning,
            String hint
    ) {
    }

    public record ActionResult(boolean ok, State state, String message) {
    }

    /** 一次启动尝试。只为了在起不来的时候能说出原因 */
    private record Launch(Process process, LocalDateTime startedAt, Path logFile) {
    }

    private static final long CMD_TIMEOUT_SEC = 15;
    /** 超过这个时间还没开完机，就别再让人干等了 */
    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(5);

    private final EmulatorCatalog catalog;
    private final IosSimulatorCatalog iosSims;
    private final HarmonyEmulatorCatalog harmonyEmu;
    private final DeviceLockService deviceLockService;
    private final Map<String, LocalDateTime> iosBooting = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> harmonyBooting = new ConcurrentHashMap<>();

    /**
     * 同时最多起几台。
     * <p>
     * 这个上限不是保守，是硬约束：一台 AVD 常见占 2~4GB 内存，
     * 主控机上还跑着后端、adb、Appium。起超了不会有明确报错，
     * 表现是整机变慢、模拟器一个个卡死、用例大面积超时——比直接拒绝难查得多。
     */
    @Value("${app.emulator.max-running:4}")
    private int maxRunning = 4;

    /** 无窗口启动。主控机常年没人看屏幕时开着更省资源，但个别机型无窗口下不稳，所以可配 */
    @Value("${app.emulator.headless:false}")
    private boolean headless = false;

    /**
     * 给模拟器几个核。0 = 用 AVD 自己的 {@code hw.cpu.ncore}。
     *
     * <p>默认要覆盖，是因为 Android Studio 建出来的 AVD 常常是 {@code hw.cpu.ncore=1}——
     * 单核跑 Android 16 + Google Play 服务，人手动点都卡，自动化更是雪上加霜：
     * {@code adb shell input} 这种要现拉 JVM 的命令会慢到把超时顶穿，
     * 表现出来却是「点击失败」「滑动失败」，完全看不出是机器慢。
     */
    @Value("${app.emulator.cores:4}")
    private int cores = 4;

    /** 给模拟器多少内存（MB）。0 = 用 AVD 自己的 {@code hw.ramSize}。带 Play 服务的镜像 2G 是不够的 */
    @Value("${app.emulator.memory-mb:4096}")
    private int memoryMb = 4096;

    /**
     * 开机后自动关掉系统动画。
     *
     * <p>两个好处，第二个才是主要的：一是肉眼变快；
     * 二是 {@code uiautomator dump} 不会再抓到动画中间那一帧——
     * 转场动画期间控件的 bounds 是在移动的，抓到的树坐标全是错的，
     * 而这种错「偶尔出现、重跑就好」，是最难查的一类。
     */
    @Value("${app.emulator.disable-animations:true}")
    private boolean disableAnimations = true;

    /** 已经调过的设备，别每轮 overview 都去调一遍 */
    private final java.util.Set<String> tuned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Map<String, Launch> launches = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    // ==================== 查 ====================

    public Overview overview() {
        EmulatorCatalog.CatalogResult cat = catalog.list();
        Map<String, DeviceLockService.Holder> holders = deviceLockService.snapshot();

        List<AvdView> views = new ArrayList<>();
        for (EmulatorCatalog.AvdInfo a : cat.avds()) {
            views.add(viewOf(a, holders));
        }
        for (IosSimulatorCatalog.SimInfo s : iosSims.list()) {
            views.add(viewOfIos(s, holders));
        }
        for (HarmonyEmulatorCatalog.Instance h : harmonyEmu.list()) {
            views.add(viewOfHarmony(h, holders));
        }
        int running = (int) views.stream().filter(v -> v.state() == State.READY).count()
                + cat.runningUnmatched().size();

        return new Overview(
                cat.emulatorCliAvailable() || harmonyEmu.available(),
                views,
                cat.runningUnmatched(),
                running,
                maxRunning,
                hintFor(cat, views, running));
    }

    /**
     * 第一次看到这台起来了，关掉系统动画。每台只做一次。
     *
     * <p>放在后台线程里：{@code overview()} 是设备池页面每次刷新都要走的路径，
     * 在里面同步等三条 adb（卡的机器上一条就要好几秒）会把整个页面拖住。
     *
     * <p>失败不算错，只记一条日志——调优是锦上添花，不该让设备因此进不了池子。
     */
    private void tuneOnce(String deviceId) {
        if (!disableAnimations || deviceId == null || deviceId.isBlank()) return;
        if (!tuned.add(deviceId)) return;
        Thread t = new Thread(() -> {
            String[] keys = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale"};
            for (String k : keys) {
                try {
                    Process p = new ProcessBuilder("adb", "-s", deviceId, "shell",
                            "settings", "put", "global", k, "0")
                            .redirectErrorStream(true).start();
                    if (!p.waitFor(15, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        log.debug("[emulator] 关动画超时 {} {}", deviceId, k);
                    }
                } catch (Exception e) {
                    log.debug("[emulator] 关动画失败 {} {}: {}", deviceId, k, e.getMessage());
                }
            }
            log.info("[emulator] {} 已关闭系统动画（快，而且 dump 不会再抓到动画中间那一帧）", deviceId);
        }, "emu-tune-" + deviceId);
        t.setDaemon(true);
        t.start();
    }

    private AvdView viewOf(EmulatorCatalog.AvdInfo a, Map<String, DeviceLockService.Holder> holders) {
        String avd = a.name();
        Launch launch = launches.get(avd);
        DeviceLockService.Holder holder = a.deviceId() == null ? null : holders.get(a.deviceId());
        boolean busy = holder != null;
        String occupiedBy = holder == null ? null : holder.describe();

        if (a.running()) {
            // adb 认了就是起来了。这一刻起就不用再管是谁起的了，
            // 启动记录留着只会让后端重启前后表现不一致
            launches.remove(avd);
            failures.remove(avd);
            tuneOnce(a.deviceId());
            return new AvdView(avd, State.READY, a.deviceId(),
                    launch == null ? null : launch.startedAt(), null, null, busy, occupiedBy, "ANDROID", null);
        }

        if (launch != null) {
            long waited = Duration.between(launch.startedAt(), LocalDateTime.now()).getSeconds();
            boolean dead = !launch.process().isAlive();
            boolean timedOut = waited > BOOT_TIMEOUT.getSeconds();
            if (dead || timedOut) {
                String reason = dead
                        ? failureReason(launch, "模拟器进程已退出")
                        : failureReason(launch, "开机超过 " + BOOT_TIMEOUT.toMinutes() + " 分钟仍没被 adb 认到");
                launches.remove(avd);
                if (timedOut && !dead) {
                    launch.process().destroy();
                }
                failures.put(avd, reason);
                return new AvdView(avd, State.FAILED, null, launch.startedAt(), waited, reason, false, null, "ANDROID", null);
            }
            return new AvdView(avd, State.BOOTING, null, launch.startedAt(), waited,
                    "正在开机，通常 30~60 秒", false, null, "ANDROID", null);
        }

        String failed = failures.get(avd);
        if (failed != null) {
            return new AvdView(avd, State.FAILED, null, null, null, failed, false, null, "ANDROID", null);
        }
        return new AvdView(avd, State.NOT_RUNNING, null, null, null, null, false, null, "ANDROID", null);
    }

    private AvdView viewOfIos(IosSimulatorCatalog.SimInfo s, Map<String, DeviceLockService.Holder> holders) {
        DeviceLockService.Holder holder = holders.get(s.udid());
        boolean busy = holder != null;
        String occupiedBy = holder == null ? null : holder.describe();
        LocalDateTime started = iosBooting.get(s.udid());
        if (s.booted()) {
            iosBooting.remove(s.udid());
            return new AvdView(s.name(), State.READY, s.udid(), started, null, null, busy, occupiedBy, "IOS", s.runtime());
        }
        if (s.booting() || started != null) {
            long waited = started == null ? 0 : Duration.between(started, LocalDateTime.now()).getSeconds();
            if (started != null && waited > BOOT_TIMEOUT.getSeconds()) {
                iosBooting.remove(s.udid());
                return new AvdView(s.name(), State.FAILED, s.udid(), started, waited,
                        "开机超过 " + BOOT_TIMEOUT.toMinutes() + " 分钟仍没起来", false, null, "IOS", s.runtime());
            }
            return new AvdView(s.name(), State.BOOTING, s.udid(), started, waited,
                    "正在开机，通常 20～40 秒", false, null, "IOS", s.runtime());
        }
        return new AvdView(s.name(), State.NOT_RUNNING, s.udid(), null, null, null, false, null, "IOS", s.runtime());
    }

    private AvdView viewOfHarmony(HarmonyEmulatorCatalog.Instance h, Map<String, DeviceLockService.Holder> holders) {
        DeviceLockService.Holder holder = h.serial() == null ? null : holders.get(h.serial());
        boolean busy = holder != null;
        String occupiedBy = holder == null ? null : holder.describe();
        LocalDateTime started = harmonyBooting.get(h.name());
        Launch launch = launches.get(h.name());
        if (h.running()) {
            harmonyBooting.remove(h.name());
            launches.remove(h.name());
            failures.remove(h.name());
            return new AvdView(h.name(), State.READY, h.serial(),
                    started != null ? started : (launch == null ? null : launch.startedAt()),
                    null, null, busy, occupiedBy, "HARMONYOS", h.osVersion());
        }
        if (h.booting() || started != null || launch != null) {
            LocalDateTime from = started != null ? started : (launch == null ? null : launch.startedAt());
            long waited = from == null ? 0 : Duration.between(from, LocalDateTime.now()).getSeconds();
            boolean dead = launch != null && !launch.process().isAlive();
            boolean timedOut = from != null && waited > BOOT_TIMEOUT.getSeconds();
            if (dead || timedOut) {
                String reason = dead
                        ? failureReason(launch, "鸿蒙模拟器进程已退出")
                        : "开机超过 " + BOOT_TIMEOUT.toMinutes() + " 分钟仍没被 hdc 认到";
                launches.remove(h.name());
                harmonyBooting.remove(h.name());
                if (timedOut && launch != null && launch.process().isAlive()) {
                    launch.process().destroy();
                }
                failures.put(h.name(), reason);
                return new AvdView(h.name(), State.FAILED, null, from, waited, reason, false, null, "HARMONYOS", h.osVersion());
            }
            return new AvdView(h.name(), State.BOOTING, h.serial(), from, waited,
                    "正在开机，通常 40～90 秒", false, null, "HARMONYOS", h.osVersion());
        }
        String failed = failures.get(h.name());
        if (failed != null) {
            return new AvdView(h.name(), State.FAILED, null, null, null, failed, false, null, "HARMONYOS", h.osVersion());
        }
        return new AvdView(h.name(), State.NOT_RUNNING, h.serial(), null, null, null, false, null, "HARMONYOS", h.osVersion());
    }

    private String hintFor(EmulatorCatalog.CatalogResult cat, List<AvdView> views, int running) {
        if (running >= maxRunning) {
            return "已经起了 " + running + " 台，到上限了。再多会把主控机的内存吃光，"
                    + "表现是整机变慢、模拟器陆续卡死。要起新的先关一台。";
        }
        boolean hasAndroid = views.stream().anyMatch(v -> "ANDROID".equals(v.platform()));
        boolean hasIos = views.stream().anyMatch(v -> "IOS".equals(v.platform()));
        boolean hasHarmony = views.stream().anyMatch(v -> "HARMONYOS".equals(v.platform()));
        boolean harmonyCli = harmonyEmu.available();
        if (!hasAndroid && !cat.emulatorCliAvailable()) {
            if (hasIos || hasHarmony) {
                return "Android 的 emulator 命令还没被后端找到（装了 Android Studio 就把 ANDROID_HOME 配给后端进程）。"
                        + (hasIos ? "iOS 模拟器可以用。" : "")
                        + (hasHarmony ? "鸿蒙模拟器可以用。" : "");
            }
            if (!harmonyCli) {
                return cat.hint();
            }
        }
        if (views.isEmpty()) {
            return "主控机上还没有 AVD、iOS 或鸿蒙模拟器。Android 用 Android Studio Device Manager；"
                    + "iOS 用 Xcode → Devices and Simulators；"
                    + "鸿蒙用 DevEco Studio Device Manager，或 "
                    + "Emulator -create Phone -deviceType Phone -osVersion \"HarmonyOS 6.1.1(24)\"。";
        }
        return null;
    }

    // ==================== 起 ====================

    /**
     * 启动一台模拟器。<b>立刻返回</b>，不等开机——冷启动 30~60 秒，
     * 同步等的话这个 HTTP 请求必然超时，而超时之后模拟器其实还在起，
     * 人会以为失败了再点一次，于是撞上「AVD 已在运行」。
     *
     * @param wipeData 顺带清掉这台的数据，用于「状态脏了要个干净环境」
     */
    public ActionResult start(String avdName, boolean wipeData) {
        String avd = avdName == null ? "" : avdName.trim();
        if (avd.isEmpty()) {
            return new ActionResult(false, State.NOT_RUNNING, "没说要起哪个 AVD");
        }

        Overview now = overview();
        AvdView existing = now.avds().stream()
                .filter(v -> v.name().equals(avd) || avd.equals(v.deviceId()))
                .findFirst().orElse(null);
        if (existing != null && "IOS".equals(existing.platform())) {
            return startIos(existing, wipeData, now);
        }
        if (existing != null && "HARMONYOS".equals(existing.platform())) {
            return startHarmony(existing, wipeData, now);
        }
        String emuBin = SdkTools.emulator();
        if (emuBin == null) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "主控机上找不到 emulator 命令。确认 ~/Library/Android/sdk/emulator/emulator 存在，"
                            + "或把 ANDROID_HOME 配给后端进程后重启");
        }
        if (existing == null) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "主控机上没有叫「" + avd + "」的 AVD。在 Android Studio 的 Device Manager 里建一个");
        }
        if (wipeData && (existing.state() == State.READY || existing.state() == State.BOOTING)) {
            ActionResult stopped = stop(avd);
            if (!stopped.ok() && existing.busy()) {
                return stopped;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = overview();
            existing = now.avds().stream()
                    .filter(v -> v.name().equals(avd) || avd.equals(v.deviceId()))
                    .findFirst().orElse(existing);
        } else if (existing.state() == State.READY) {
            return new ActionResult(true, State.READY, "这台已经起着了（" + existing.deviceId() + "）");
        } else if (existing.state() == State.BOOTING) {
            return new ActionResult(true, State.BOOTING, "这台正在开机，等它一会儿就行");
        }
        if (now.runningCount() >= maxRunning) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "已经起了 " + now.runningCount() + " 台，到上限 " + maxRunning + " 了。先关一台再起");
        }

        clearStaleAvdLocks();

        List<String> cmd = new ArrayList<>(List.of(emuBin, "-avd", avd));
        // 每次干净启动。快照能省几十秒，但「上次残留的登录态、没关的弹窗」
        // 会让同一条用例时过时不过，这种偶现比多等半分钟贵得多
        cmd.add("-no-snapshot-load");
        cmd.add("-no-audio");
        // 覆盖 AVD 里的核数/内存。Android Studio 默认建出来常常是 1 核 2G，
        // 那个配置跑得动界面，跑不动自动化
        if (cores > 0) {
            cmd.add("-cores");
            cmd.add(String.valueOf(cores));
        }
        if (memoryMb > 0) {
            cmd.add("-memory");
            cmd.add(String.valueOf(memoryMb));
        }
        if (headless) {
            cmd.add("-no-window");
        }
        if (wipeData) {
            cmd.add("-wipe-data");
        }

        try {
            Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "emulator-" + avd.replaceAll("[^a-zA-Z0-9._-]", "_") + ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            String home = SdkTools.androidHome();
            if (home != null) {
                pb.environment().put("ANDROID_HOME", home);
                pb.environment().put("ANDROID_SDK_ROOT", home);
            }
            Process p = pb.start();
            launches.put(avd, new Launch(p, LocalDateTime.now(), logFile));
            failures.remove(avd);
            log.info("[emulator] 启动 {}{}，日志 {}", avd, wipeData ? "（清数据）" : "", logFile);
            return new ActionResult(true, State.BOOTING,
                    "已开始启动，通常 30~60 秒。开完机会自己出现在设备池里");
        } catch (Exception e) {
            String reason = "拉起 emulator 进程失败：" + e.getMessage();
            failures.put(avd, reason);
            log.warn("[emulator] 启动失败 {}: {}", avd, e.getMessage());
            return new ActionResult(false, State.FAILED, reason);
        }
    }

    private ActionResult startIos(AvdView existing, boolean wipeData, Overview now) {
        String udid = existing.deviceId();
        if (udid == null || udid.isBlank()) {
            return new ActionResult(false, State.NOT_RUNNING, "这台 iOS 模拟器没有 UDID");
        }
        if (existing.state() == State.READY) {
            return new ActionResult(true, State.READY, "这台已经起着了（" + udid + "）");
        }
        if (existing.state() == State.BOOTING) {
            return new ActionResult(true, State.BOOTING, "这台正在开机，等它一会儿就行");
        }
        if (now.runningCount() >= maxRunning) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "已经起了 " + now.runningCount() + " 台，到上限 " + maxRunning + " 了。先关一台再起");
        }
        if (wipeData) {
            iosSims.shutdown(udid);
            if (!iosSims.erase(udid)) {
                return new ActionResult(false, State.NOT_RUNNING, "清数据失败（simctl erase）。模拟器可能正被占用");
            }
        }
        iosBooting.put(udid, LocalDateTime.now());
        boolean ok = iosSims.boot(udid);
        if (!ok) {
            iosBooting.remove(udid);
            return new ActionResult(false, State.FAILED, "simctl boot 失败。去主控机终端跑：xcrun simctl boot " + udid);
        }
        return new ActionResult(true, State.BOOTING, "已开始启动 iOS 模拟器，通常 20～40 秒。开完机会自己出现在设备池里");
    }

    private ActionResult startHarmony(AvdView existing, boolean wipeData, Overview now) {
        String name = existing.name();
        if (existing.state() == State.READY) {
            return new ActionResult(true, State.READY, "这台已经起着了"
                    + (existing.deviceId() == null ? "" : "（" + existing.deviceId() + "）"));
        }
        if (existing.state() == State.BOOTING) {
            return new ActionResult(true, State.BOOTING, "这台正在开机，等它一会儿就行");
        }
        if (now.runningCount() >= maxRunning) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "已经起了 " + now.runningCount() + " 台，到上限 " + maxRunning + " 了。先关一台再起");
        }
        if (!harmonyEmu.available()) {
            return new ActionResult(false, State.NOT_RUNNING,
                    "找不到 DevEco 的 Emulator。确认 /Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator 存在后重启后端");
        }
        try {
            Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "harmony-emu-" + name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".log");
            Process p = harmonyEmu.start(name, wipeData, logFile);
            launches.put(name, new Launch(p, LocalDateTime.now(), logFile));
            harmonyBooting.put(name, LocalDateTime.now());
            failures.remove(name);
            log.info("[emulator] 启动鸿蒙 {}{}，日志 {}", name, wipeData ? "（清数据）" : "", logFile);
            return new ActionResult(true, State.BOOTING,
                    "已开始启动鸿蒙模拟器，通常 40～90 秒。开完机会自己出现在设备池里");
        } catch (Exception e) {
            String reason = "拉起鸿蒙模拟器失败：" + e.getMessage()
                    + "。若提示许可，去主控机跑：Emulator -license accept";
            failures.put(name, reason);
            log.warn("[emulator] 启动鸿蒙失败 {}: {}", name, e.getMessage());
            return new ActionResult(false, State.FAILED, reason);
        }
    }

    // ==================== 关 ====================

    /**
     * 关掉一台模拟器。
     *
     * <p>正被占用的一律不给关：跑着的用例会在中途莫名其妙失败，而失败信息里
     * 只会显示「设备连接断开」，查的人根本想不到是有人在另一个页面按了关闭。
     */
    public ActionResult stop(String avdName) {
        String avd = avdName == null ? "" : avdName.trim();
        AvdView v = overview().avds().stream()
                .filter(x -> x.name().equals(avd) || avd.equals(x.deviceId()))
                .findFirst().orElse(null);
        if (v == null) {
            return new ActionResult(false, State.NOT_RUNNING, "没有叫「" + avd + "」的 AVD");
        }
        if (v.busy()) {
            return new ActionResult(false, v.state(),
                    "这台正被占用：" + v.occupiedBy() + "。等它结束，或者先归还再关");
        }
        if (v.state() == State.BOOTING) {
            Launch launch = launches.remove(avd);
            if (launch != null) {
                launch.process().destroy();
            }
            harmonyBooting.remove(avd);
            if (v.deviceId() != null) {
                iosBooting.remove(v.deviceId());
            }
            if ("HARMONYOS".equals(v.platform())) {
                harmonyEmu.stop(v.name());
            } else if ("IOS".equals(v.platform()) && v.deviceId() != null) {
                iosSims.shutdown(v.deviceId());
            }
            return new ActionResult(true, State.NOT_RUNNING, "已中止启动");
        }
        if (v.state() != State.READY || v.deviceId() == null) {
            launches.remove(avd);
            failures.remove(avd);
            return new ActionResult(true, State.NOT_RUNNING, "这台本来就没在跑");
        }

        // 用 adb emu kill 而不是杀进程：让模拟器自己走关机流程，
        // 直接 kill 有一定概率把 AVD 的镜像写坏，下次启动报 "snapshot is corrupted"
        if ("IOS".equals(v.platform())) {
            iosBooting.remove(v.deviceId());
            boolean okIos = iosSims.shutdown(v.deviceId());
            if (okIos) {
                return new ActionResult(true, State.NOT_RUNNING,
                        "已关闭。设备池里最多再过一轮扫描（约 5 秒）就看不到它了");
            }
            return new ActionResult(false, v.state(), "关闭 iOS 模拟器失败，去主控机上看 Simulator 是否卡住");
        }
        if ("HARMONYOS".equals(v.platform())) {
            Launch launch = launches.remove(v.name());
            if (launch != null) {
                launch.process().destroy();
            }
            harmonyBooting.remove(v.name());
            failures.remove(v.name());
            boolean okHos = harmonyEmu.stop(v.name())
                    || (v.deviceId() != null && harmonyEmu.stop(v.deviceId()));
            if (okHos) {
                return new ActionResult(true, State.NOT_RUNNING,
                        "已关闭。设备池里最多再过一轮扫描（约 5 秒）就看不到它了");
            }
            return new ActionResult(false, v.state(), "关闭鸿蒙模拟器失败，去主控机上看 DevEco Emulator 是否卡住");
        }
        String adb = SdkTools.adb();
        boolean ok = adb != null && run(adb, "-s", v.deviceId(), "emu", "kill");
        launches.remove(avd);
        failures.remove(avd);
        if (ok) {
            log.info("[emulator] 已关闭 {} ({})", avd, v.deviceId());
            return new ActionResult(true, State.NOT_RUNNING,
                    "已关闭。设备池里最多再过一轮扫描（约 5 秒）就看不到它了");
        }
        return new ActionResult(false, State.READY, "关闭命令没执行成功，去主控机上看看 adb 是否正常");
    }

    /**
     * 上次窗口关了、进程死了，{@code multiinstance.lock} 常还在。
     * emulator 看见锁就秒退，设备池却报「已开始启动」——人点清数据重启，窗口根本不出现。
     */
    private void clearStaleAvdLocks() {
        Path home = Path.of(System.getProperty("user.home"), ".android", "avd");
        if (!Files.isDirectory(home)) {
            return;
        }
        try (var dirs = Files.list(home)) {
            dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith(".avd"))
                    .forEach(dir -> {
                        try (var files = Files.list(dir)) {
                            files.filter(p -> p.getFileName().toString().endsWith(".lock"))
                                    .forEach(p -> {
                                        try {
                                            Files.deleteIfExists(p);
                                            log.info("[emulator] 清掉残留锁 {}", p);
                                        } catch (Exception ignored) {
                                            // 锁还被活着的进程占着就留着
                                        }
                                    });
                        } catch (Exception ignored) {
                            // 单个 AVD 目录读失败不影响别的
                        }
                    });
        } catch (Exception e) {
            log.debug("[emulator] 扫锁失败: {}", e.getMessage());
        }
    }

    // ==================== 杂 ====================

    /** 起不来时，从模拟器自己的日志里找一句能用的原因 */
    private String failureReason(Launch launch, String fallback) {
        try {
            if (launch.logFile() != null && Files.isRegularFile(launch.logFile())) {
                List<String> lines = Files.readAllLines(launch.logFile(), StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 40; i--) {
                    String s = lines.get(i).trim();
                    if (s.isEmpty()) continue;
                    String lower = s.toLowerCase();
                    if (lower.contains("error") || lower.contains("cannot") || lower.contains("failed")
                            || lower.contains("not found") || lower.contains("already running")
                            || lower.contains("permission")) {
                        return fallback + "：" + s;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[emulator] 读启动日志失败: {}", e.getMessage());
        }
        return fallback + "（详细日志见主控机 " + launch.logFile() + "）";
    }

    private boolean run(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {
                    // 读干净，否则输出缓冲区满了进程会卡住
                }
            }
            return p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[emulator] 命令失败 {}: {}", String.join(" ", command), e.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** 序列化给前端用 */
    public static Map<String, Object> toMap(AvdView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", v.name());
        m.put("state", v.state().name());
        m.put("deviceId", v.deviceId());
        m.put("startedAt", v.startedAt());
        m.put("elapsedSeconds", v.elapsedSeconds());
        m.put("message", v.message());
        m.put("busy", v.busy());
        m.put("occupiedBy", v.occupiedBy());
        m.put("platform", v.platform() == null ? "ANDROID" : v.platform());
        m.put("version", v.version());
        return m;
    }
}
