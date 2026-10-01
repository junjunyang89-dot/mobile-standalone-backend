package com.mobile.standalone.service.perf;

import com.mobile.standalone.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 冷启动耗时：跑若干轮，取中位数。
 *
 * <p><b>为什么必须多轮</b>：单轮冷启动的波动能到 15~20%，而基线对比的告警阈值就是 15%。
 * 也就是说，只跑一轮的话，<b>这个指标会天天报假劣化</b>——
 * 而报三次假的之后，第四次真的也没人信了。这份报告的全部价值就在于可信。
 *
 * <p>只做 Android。iOS 和鸿蒙没有 {@code am start -W} 的等价物，
 * <b>明确返回「不支持」而不是编一个数出来</b>：
 * 一个缺失的指标是显眼的，一个错的指标会被当真。
 */
@Component
@Slf4j
public class ColdStartMeasurer {

    /** 默认跑几轮。3 轮起步：丢掉第 1 轮预热，还剩 2 个样本能取中位 */
    public static final int DEFAULT_ROUNDS = 5;
    /** am start -W 会一直阻塞到启动完成，慢设备上给足时间 */
    private static final long START_TIMEOUT_MS = 60_000L;
    private static final long CMD_TIMEOUT_MS = 20_000L;
    /** force-stop 之后等一下，让系统把进程真的收干净，否则下一轮会变成「切到前台」 */
    private static final long SETTLE_MS = 1_500L;

    /**
     * @param medianMs  中位数，null＝一轮有效的都没有
     * @param samples   被采纳的每轮数值，报告里要能看到，好让人自己判断波动大不大
     * @param rejected  被丢掉的轮次及原因
     * @param supported 这个平台支不支持。不支持时 medianMs 必为 null
     */
    public record Measurement(boolean supported, Double medianMs, List<Integer> samples,
                              List<String> rejected, List<String> notes) {

        public static Measurement unsupported(String why) {
            return new Measurement(false, null, List.of(), List.of(), List.of(why));
        }
    }

    /**
     * @param activity 启动页，形如 {@code com.pwrd.steam.esports/.ui.activity.launcher.LauncherActivity}。
     *                 传空就现场 resolve——但<b>建议传</b>：resolve 出来的可能是别的 Activity，
     *                 那样测的就不是用户点图标走的那条路径
     */
    public Measurement measure(Device.Platform platform, String deviceId,
                               String packageName, String activity, int rounds) {
        if (platform != null && platform != Device.Platform.ANDROID
                && platform != Device.Platform.POG_ANDROID) {
            // 鸿蒙走 hdc、iOS 压根没有这个命令。这里宁可少一个指标，也不给一个来路不明的数
            return Measurement.unsupported("冷启动耗时目前只支持 Android（依赖 am start -W）；"
                    + platform + " 需要另外的采集方式，本次不采");
        }
        if (isBlank(deviceId) || isBlank(packageName)) {
            return Measurement.unsupported("缺少 deviceId 或包名，冷启动没法测");
        }

        String component = isBlank(activity) ? resolveLauncher(deviceId, packageName) : activity.trim();
        if (isBlank(component)) {
            return Measurement.unsupported("解析不到启动 Activity，冷启动没法测。"
                    + "可以在性能任务 config 里显式指定 launchActivity");
        }

        int total = rounds > 0 ? rounds : DEFAULT_ROUNDS;
        List<Integer> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (int i = 1; i <= total; i++) {
            forceStop(deviceId, packageName);
            sleep(SETTLE_MS);
            String out = exec(START_TIMEOUT_MS, "adb", "-s", deviceId, "shell",
                    "am", "start", "-W", "-S", "-n", component);
            ColdStartParser.Result r = ColdStartParser.parse(out);
            if (!r.usable()) {
                rejected.add("第 " + i + " 轮：" + r.problem());
                continue;
            }
            // 第 1 轮丢掉：force-stop 之后页缓存还在，但 ART 的 JIT 缓存、
            // 各种懒加载单例的状态跟后面几轮不一样，第 1 轮系统性偏高。
            // 轮数太少就不丢了，丢了就没样本了
            if (i == 1 && total >= 3) {
                notes.add("第 1 轮（" + r.totalTimeMs() + "ms）当预热丢弃，不进中位数");
                continue;
            }
            accepted.add(r.totalTimeMs());
        }

        forceStop(deviceId, packageName);

        Double median = ColdStartParser.median(accepted);
        if (median == null) {
            notes.add("没有一轮有效的冷启动。常见原因：force-stop 没生效（会被识别成「切到前台」）、"
                    + "启动 Activity 写错、或者设备掉线");
        } else {
            notes.add("取 " + accepted.size() + " 轮的中位数；样本 " + accepted
                    + "（用中位数不用平均，是为了不被偶发的那一轮拽走）");
            int min = accepted.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = accepted.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (min > 0 && (max - min) * 100.0 / min > 30.0) {
                // 波动比告警阈值还大，这时候的中位数不该被当成一个准数去跟上版比
                notes.add("⚠ 各轮波动很大（" + min + "~" + max + "ms，差 "
                        + Math.round((max - min) * 100.0 / min) + "%），"
                        + "这次的数字参考价值有限——先确认设备上没有别的东西在跑，再重测一次");
            }
        }
        log.info("[cold-start] {} {} 中位数={} 采纳={} 丢弃={}",
                deviceId, packageName, median, accepted, rejected.size());
        return new Measurement(true, median, accepted, rejected, notes);
    }

    /** 报告和基线用的指标表 */
    public Map<String, Double> toMetrics(Measurement m) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (m != null && m.supported() && m.medianMs() != null) {
            out.put("coldStartMs", m.medianMs());
        }
        return out;
    }

    // ==================== adb ====================

    private String resolveLauncher(String deviceId, String packageName) {
        String out = exec(CMD_TIMEOUT_MS, "adb", "-s", deviceId, "shell",
                "cmd", "package", "resolve-activity", "--brief", packageName);
        if (out == null) {
            return null;
        }
        for (String line : out.split("\\R")) {
            String s = line.trim();
            if (s.contains("/") && s.startsWith(packageName)) {
                return s;
            }
        }
        return null;
    }

    private void forceStop(String deviceId, String packageName) {
        exec(CMD_TIMEOUT_MS, "adb", "-s", deviceId, "shell", "am", "force-stop", packageName);
    }

    /**
     * 执行并读干净输出。
     *
     * <p>超时一定要给够：{@code am start -W} 阻塞到启动完成，而我们之前在单核模拟器上
     * 栽过一次——5 秒超时把 {@code input swipe} 掐了，报上来是「滑动失败」，
     * 查了半天手势。超时报错的方向天生是错的，所以宁可给宽。
     */
    private String exec(long timeoutMs, String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            Process proc = p;
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                    // 进程被掐时读到一半是正常的，已经读到的照样有用
                }
            });
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                log.warn("[cold-start] 命令超时（{}ms）: {}", timeoutMs, String.join(" ", cmd));
                return null;
            }
            drain.join(2_000L);
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[cold-start] 命令失败 {}: {}", String.join(" ", cmd), e.getMessage());
            return null;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
