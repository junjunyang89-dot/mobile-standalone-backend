package com.mobile.standalone.service.pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 主控机上有哪些模拟器（AVD），各自起没起。
 *
 * <p><b>为什么需要它</b>：设备列表只认「已经启动、并且被 adb 扫到」的东西。
 * 没启动的 AVD 在 adb 眼里根本不存在，于是设备池里模拟器永远是 0，
 * 而人的疑问是「那我怎么知道有哪些模拟器可以用」——这个问题设备列表回答不了，
 * 因为它问的是「主控机上装了什么」，不是「现在连着什么」。
 *
 * <p>这里只做<b>只读</b>的清点，不负责启动。启动模拟器是另一件事：
 * 要挑镜像、要等开机、要处理起不来的情况，值得单独做，不该混在一个列表接口里。
 */
@Service
@Slf4j
public class EmulatorCatalog {

    /** 这些命令都可能卡住（adb server 没起、模拟器无响应），必须有超时 */
    private static final long CMD_TIMEOUT_SEC = 10;

    /**
     * AVD 名单缓存 60 秒。
     * <p>
     * 前端要靠轮询这个接口画启动进度，而 {@code emulator -list-avds} 每次要一秒左右；
     * 但 AVD 名单只有人去 Android Studio 新建/删除时才变，几秒钟问一次纯属浪费。
     * adb 那两个查询不缓存——「起没起」必须是实时的。
     */
    private static final long AVD_LIST_TTL_MS = 60_000;

    private volatile List<String> cachedAvdNames;
    private volatile long cachedAvdNamesAt;

    /**
     * 序列号 → AVD 名。
     * <p>
     * 一个模拟器活着的时候不会换 AVD，所以问一次就够；序列号从 adb 里消失时顺手清掉。
     */
    private final Map<String, String> avdNameBySerial = new java.util.concurrent.ConcurrentHashMap<>();

    /** 一台 AVD 的清点结果 */
    public record AvdInfo(String name, boolean running, String deviceId) {
    }

    public record CatalogResult(
            /** emulator 命令在不在 PATH 上；不在的话 avds 一定是空的，但不代表没装模拟器 */
            boolean emulatorCliAvailable,
            List<AvdInfo> avds,
            /** 正在跑、但对不上任何 AVD 名字的：雷电/夜神/云机这类第三方模拟器走的是这条 */
            List<String> runningUnmatched,
            String hint
    ) {
    }

    public CatalogResult list() {
        List<String> avdNames = cachedAvdNames();
        boolean cliOk = avdNames != null;

        // 先看现在有哪些模拟器连着 adb，再问每台自己是哪个 AVD
        Map<String, String> serialByAvd = new LinkedHashMap<>();
        Set<String> unmatched = new LinkedHashSet<>();
        List<String> serials = runningEmulatorSerials();
        avdNameBySerial.keySet().retainAll(serials);
        for (String serial : serials) {
            // 问不出名字的存一个空串占位。不占位的话，雷电/夜神这类不认这个命令的模拟器
            // 每次轮询都要重问一遍，而「不认的命令」往往是等到超时才返回——
            // 一次 10 秒，接口就废了
            String avd = avdNameBySerial.computeIfAbsent(serial,
                    id -> {
                        String n = avdNameOf(id);
                        return n == null ? "" : n;
                    });
            if (avd != null && !avd.isBlank()) {
                serialByAvd.put(avd, serial);
            } else {
                // 拿不到 AVD 名的多半不是 AOSP 模拟器（雷电/夜神），
                // 它们照样能跑用例，所以要单独列出来而不是丢掉
                unmatched.add(serial);
            }
        }

        List<AvdInfo> out = new ArrayList<>();
        if (avdNames != null) {
            for (String name : avdNames) {
                String serial = serialByAvd.remove(name);
                out.add(new AvdInfo(name, serial != null, serial));
            }
        }
        // 起着的但不在 -list-avds 里（换过 ANDROID_AVD_HOME 之类），也得露出来
        serialByAvd.forEach((avd, serial) -> out.add(new AvdInfo(avd, true, serial)));

        return new CatalogResult(cliOk, out, new ArrayList<>(unmatched), hintFor(cliOk, out));
    }

    private static String hintFor(boolean cliOk, List<AvdInfo> avds) {
        if (!cliOk) {
            return "主控机上找不到 emulator 命令。装了 Android Studio 的话，确认 "
                    + "~/Library/Android/sdk/emulator/emulator 存在，或把 ANDROID_HOME 配给后端进程。";
        }
        if (avds.isEmpty()) {
            return "主控机上一个 AVD 都没建。Android Studio → Device Manager → Create Device，"
                    + "Apple 芯片的机器镜像选 arm64-v8a。";
        }
        boolean anyRunning = avds.stream().anyMatch(AvdInfo::running);
        return anyRunning ? null
                : "这些 AVD 都没启动，所以设备池里看不到它们——模拟器要先在主控机上跑起来才会被 adb 扫到。";
    }

    /** AVD 名单，带 60 秒缓存；拿不到（emulator 命令不在）返回 null */
    private List<String> cachedAvdNames() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedAvdNames;
        if (cached != null && now - cachedAvdNamesAt < AVD_LIST_TTL_MS) {
            return cached;
        }
        String emu = SdkTools.emulator();
        if (emu == null) {
            return null;
        }
        List<String> fresh = runLines(emu, "-list-avds");
        if (fresh != null) {
            // 只缓存成功的结果：命令临时失败时缓存 null，会让接口在一分钟内
            // 一直说「找不到 emulator 命令」，而实际早就好了
            cachedAvdNames = fresh;
            cachedAvdNamesAt = now;
            return fresh;
        }
        return cached != null && now - cachedAvdNamesAt < AVD_LIST_TTL_MS ? cached : null;
    }

    /** adb devices 里在线的序列号 */
    private List<String> runningEmulatorSerials() {
        String adb = SdkTools.adb();
        if (adb == null) {
            return List.of();
        }
        return parseAdbDevices(runLines(adb, "devices")).stream()
                .filter(EmulatorCatalog::isEmulatorSerial)
                .toList();
    }

    /**
     * 只把「像模拟器」的序列号拿去问 AVD 名。
     * USB 真机（畅享 / PGT-AN00 这种）不认 {@code adb emu}，问了只会超时，
     * 还会被误列进「雷电/夜神」那条黄字。
     */
    static boolean isEmulatorSerial(String id) {
        String s = id == null ? "" : id.trim();
        if (s.startsWith("emulator-")) return true;
        return s.startsWith("127.0.0.1:") || s.startsWith("localhost:");
    }

    /**
     * 解析 {@code adb devices} 的输出，只取状态为 device 的那些。
     * <p>
     * 抽成静态方法是为了能直接测：这里认错一行的后果是模拟器凭空多一台或少一台，
     * 而 adb 的输出格式在不同版本、不同连接方式（USB / TCP）下并不完全一样。
     */
    static List<String> parseAdbDevices(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("List of devices")) continue;
            if (s.startsWith("*")) continue;   // "* daemon started successfully *"
            String[] parts = s.split("\\s+");
            // 状态必须正好是 device：offline / unauthorized / bootloader 都不算在线，
            // 而「包含 device」这种判法会把 "device:emu64a" 这类描述字段也算进去
            if (parts.length >= 2 && "device".equals(parts[1])) {
                out.add(parts[0]);
            }
        }
        return out;
    }

    /**
     * 问这台模拟器自己是哪个 AVD。
     * <p>
     * {@code adb emu avd name} 的输出是「名字 + 一行 OK」，要把 OK 去掉。
     * 第三方模拟器不认这个命令，返回 null——这是预期内的，不是错误。
     */
    private String avdNameOf(String serial) {
        String adb = SdkTools.adb();
        if (adb == null) {
            return null;
        }
        return parseAvdName(runLines(adb, "-s", serial, "emu", "avd", "name"));
    }

    /** 从 {@code adb emu avd name} 的输出里取出 AVD 名；取不到返回 null */
    static String parseAvdName(List<String> lines) {
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty() || "OK".equalsIgnoreCase(s)) continue;
            String lower = s.toLowerCase();
            if (lower.contains("error") || lower.contains("unknown command")
                    || lower.contains("not found") || lower.contains("ko:")) {
                return null;
            }
            return s;
        }
        return null;
    }

    /**
     * 跑个命令读输出。
     *
     * @return 输出行；命令不存在或超时返回 null（区别于「跑了但没有输出」的空列表）
     */
    private List<String> runLines(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            if (!p.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[emulator-catalog] 命令超时: {}", String.join(" ", command));
                return null;
            }
            return out;
        } catch (Exception e) {
            log.debug("[emulator-catalog] 命令跑不了 {}: {}", String.join(" ", command), e.getMessage());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
