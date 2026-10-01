package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队列里有任务、又没有空闲设备时，自己起一台模拟器；起来的那台闲够久了再自己关掉。
 *
 * <p>这一块是让「六台轮着跑」在<b>无人值守</b>时也成立的最后一环。
 * 没有它的话，半夜排队的任务只能一路排到超时——因为没人会去点「启动模拟器」。
 *
 * <p><b>它的每一条约束都是在防同一件事：把主控机搞垮。</b>
 * 自动起模拟器最容易出的事故不是起不来，而是<b>起太多</b>：
 * 队列里堆了十个任务，十台模拟器一起开机，主控机内存吃光、整机卡死，
 * 连带把正在跑的真机用例也拖挂。所以：
 *
 * <ul>
 *   <li><b>一次只起一台</b>，且要等它开完机才考虑下一台</li>
 *   <li>受 {@code app.emulator.max-running} 上限约束</li>
 *   <li><b>任务得先等一会儿</b>：真机十秒后就跑完了的话，为它起一台开机要一分钟的模拟器是净亏</li>
 *   <li>起失败的 AVD 进冷却，不反复撞同一堵墙</li>
 * </ul>
 *
 * <p><b>关于自动关闭</b>：只关自己起的那些。人手动起的不碰——
 * 那是别人有意留着的，平台没有资格替他决定什么时候不用了。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmulatorAutoScaler {

    private final TaskMapper taskMapper;
    private final DevicePoolReader poolReader;
    private final EmulatorLauncher launcher;

    @Value("${app.emulator.auto-start.enabled:true}")
    private boolean enabled = true;

    /**
     * 任务排队多久之后才考虑为它起模拟器。
     * <p>
     * 不等的话会白起：真机上那条用例可能十几秒就跑完了，而模拟器开机要一分钟——
     * 等模拟器起来，任务早被真机接走了，白占一份内存。
     */
    @Value("${app.emulator.auto-start.min-queue-wait-seconds:30}")
    private long minQueueWaitSeconds = 30;

    /**
     * 自动起的模拟器闲多久之后自动关掉；0 = 不关。
     * <p>
     * 只回收自己起的。设成 0 就是「起了就一直留着」，
     * 适合白天连续有人用的场景——反复开关机比留着更浪费。
     */
    @Value("${app.emulator.auto-start.idle-reclaim-minutes:30}")
    private long idleReclaimMinutes = 30;

    /** 起失败之后多久内不再试这台 */
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(10);

    /** 平台自己起的那些。人手动起的不在里面，也就不会被自动关掉 */
    private final Set<String> autoStarted = ConcurrentHashMap.newKeySet();
    /** AVD → 冷却到什么时候 */
    private final ConcurrentHashMap<String, LocalDateTime> cooldownUntil = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${app.emulator.auto-start.interval-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            EmulatorLauncher.Overview overview = launcher.overview();
            if (!overview.emulatorCliAvailable()) {
                return;
            }
            boolean queueNeedsEmulator = maybeStart(overview);
            maybeReclaim(overview, queueNeedsEmulator);
        } catch (Exception e) {
            log.warn("[emulator-auto] 这一轮出错: {}", e.getMessage());
        }
    }

    // ==================== 起 ====================

    /**
     * @return 队列里还有等着模拟器的任务
     */
    private boolean maybeStart(EmulatorLauncher.Overview overview) {
        List<Task> queued;
        try {
            queued = taskMapper.findQueued(20);
        } catch (Exception e) {
            log.debug("[emulator-auto] 取排队任务失败: {}", e.getMessage());
            return false;
        }
        if (queued == null || queued.isEmpty()) {
            return false;
        }

        List<DevicePicker.Candidate> pool = poolReader.snapshot(null);
        Task waiting = null;
        for (Task t : queued) {
            DevicePicker.Requirement req = TaskDeviceRequirement.of(t);
            if (!req.allowEmulator()) {
                // 只用真机的任务，起模拟器帮不上忙
                continue;
            }
            if (DevicePicker.pick(pool, req).ok()) {
                // 有设备能接，调度器下一轮就会派下去，不用起
                continue;
            }
            if (waitedSeconds(t) < minQueueWaitSeconds) {
                // 还没等够。真机可能马上就空出来了
                continue;
            }
            waiting = t;
            break;
        }
        if (waiting == null) {
            return false;
        }

        // 有一台正在开机就先等它。一次只起一台是这里最重要的一条约束——
        // 队列里堆了十个任务时，不加这一条就是十台模拟器一起开机
        boolean booting = overview.avds().stream()
                .anyMatch(v -> v.state() == EmulatorLauncher.State.BOOTING);
        if (booting) {
            return true;
        }
        if (overview.runningCount() >= overview.maxRunning()) {
            // 到上限了。这不是错误，是设计——记 debug 级别，否则日志会被刷满
            log.debug("[emulator-auto] 队列在等，但已达运行上限 {}", overview.maxRunning());
            return true;
        }

        String avd = pickAvdToStart(overview);
        if (avd == null) {
            return true;
        }
        EmulatorLauncher.ActionResult r = launcher.start(avd, false);
        if (r.ok()) {
            autoStarted.add(avd);
            log.info("[emulator-auto] 队列里「{}」等了 {} 秒还没设备，自动启动 {}",
                    waiting.getName(), waitedSeconds(waiting), avd);
        } else {
            cooldownUntil.put(avd, LocalDateTime.now().plus(FAILURE_COOLDOWN));
            log.warn("[emulator-auto] 自动启动 {} 失败，{} 分钟内不再试：{}",
                    avd, FAILURE_COOLDOWN.toMinutes(), r.message());
        }
        return true;
    }

    /** 挑一台没在跑、也不在冷却里的 AVD */
    private String pickAvdToStart(EmulatorLauncher.Overview overview) {
        LocalDateTime now = LocalDateTime.now();
        for (EmulatorLauncher.AvdView v : overview.avds()) {
            if (v.state() != EmulatorLauncher.State.NOT_RUNNING
                    && v.state() != EmulatorLauncher.State.FAILED) {
                continue;
            }
            LocalDateTime until = cooldownUntil.get(v.name());
            if (until != null && now.isBefore(until)) {
                continue;
            }
            cooldownUntil.remove(v.name());
            return v.name();
        }
        return null;
    }

    // ==================== 关 ====================

    /**
     * 把自己起的、已经闲够久的关掉。
     *
     * @param queueNeedsEmulator 队列里还有等模拟器的任务——这时候关掉等于自己拆自己的台
     */
    private void maybeReclaim(EmulatorLauncher.Overview overview, boolean queueNeedsEmulator) {
        if (idleReclaimMinutes <= 0 || queueNeedsEmulator || autoStarted.isEmpty()) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(idleReclaimMinutes);
        for (EmulatorLauncher.AvdView v : overview.avds()) {
            if (!autoStarted.contains(v.name())) {
                continue;
            }
            if (v.state() != EmulatorLauncher.State.READY) {
                // 已经不在跑了（被人手动关了之类），从名单里摘掉就行
                if (v.state() == EmulatorLauncher.State.NOT_RUNNING) {
                    autoStarted.remove(v.name());
                }
                continue;
            }
            if (v.busy()) {
                continue;
            }
            LocalDateTime lastUsed = v.deviceId() == null ? null : poolReader.lastUsedAt(v.deviceId());
            // 从没派过活的：用启动时间当基准，否则一台起来就没人用的模拟器永远不会被回收
            LocalDateTime since = lastUsed != null ? lastUsed : v.startedAt();
            if (since == null || since.isAfter(deadline)) {
                continue;
            }
            EmulatorLauncher.ActionResult r = launcher.stop(v.name());
            if (r.ok()) {
                autoStarted.remove(v.name());
                log.info("[emulator-auto] {} 闲了超过 {} 分钟，自动关闭回收内存",
                        v.name(), idleReclaimMinutes);
            } else {
                log.debug("[emulator-auto] 回收 {} 没成功: {}", v.name(), r.message());
            }
            // 一轮只关一台：关机也要时间，而且下一轮的 overview 才是准的
            return;
        }
    }

    private long waitedSeconds(Task t) {
        return t.getCreatedAt() == null
                ? Long.MAX_VALUE
                : Duration.between(t.getCreatedAt(), LocalDateTime.now()).getSeconds();
    }
}
