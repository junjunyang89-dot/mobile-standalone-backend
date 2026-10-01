package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备占用锁：测试任务 / 线上回归 / 元素采集共用同一把锁，保证一台真机同时只被一条执行链占用。
 * <p>
 * 取代原先「查 tasks 表里 RUNNING 记录」的判断方式——那种做法有两个问题：
 * 查询与写入之间没有互斥（并发提交会同时通过），且只认任务、管不到采集与回归。
 * <p>
 * 单实例内存锁；多实例部署时需换成 DB 行锁或分布式锁，接口保持不变。
 */
@Service
@Slf4j
public class DeviceLockService {

    /** 占用来源，仅用于提示文案与排查 */
    public enum Kind {
        TASK("测试任务"),
        REGRESSION("线上回归"),
        INSPECT("元素采集"),
        /**
         * 有人把这台借走手动用。
         * <p>
         * 走同一把锁而不是另起一套「预约」字段，是因为它要挡的东西完全一样：
         * 借走的设备不能再被派任务，跑着任务的设备也不能被借走。
         * 同一件事用两套机制表达，迟早会出现「两边都认为自己拿到了」的情况。
         */
        MANUAL("人工借用"),
        /**
         * 正在录制流程。
         * <p>
         * 不占住的话调度器会往这台派任务，两边同时操作同一台手机——
         * 录出来的步骤里会混进别人点的东西，而且现象是「录的跟我点的对不上」，很难往这上面想。
         */
        RECORD("流程录制");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static final DateTimeFormatter SINCE_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * @param refId 持有者唯一标识（taskId / runId 等），释放时凭它校验，避免误放别人的锁
     * @param label 展示名，用于「设备正被 XXX 占用」提示
     */
    public record Holder(Kind kind, String refId, String label, LocalDateTime since) {

        /** 供错误提示直接使用的一句话 */
        public String describe() {
            return kind.getDisplayName() + "「" + (label != null && !label.isBlank() ? label : refId) + "」"
                    + "（自 " + (since != null ? since.format(SINCE_FMT) : "未知时间") + " 起）";
        }
    }

    private final ConcurrentHashMap<String, Holder> holders = new ConcurrentHashMap<>();

    /**
     * 已请求停止后的宽限期。超过就当持有者已经死了，抢过来。
     * <p>
     * 「停止」意味着执行线程要么正在退出、要么已经异常死掉。收尾最慢的是 Appium 会话关闭
     * 和最后一张截图，两分钟足够；再等下去就不是在等收尾，是在等一个永远不会来的释放。
     */
    private static final Duration CANCELLED_GRACE = Duration.ofMinutes(2);

    /**
     * 无人认领的上限。
     * <p>
     * 锁是内存里的，只在执行线程正常收尾时释放——线程卡死、进程被 kill、异常路径漏了 finally，
     * 都会让这台设备<b>永远占着</b>，而现象是「任务明明停了还说被占用」，只能重启后端。
     * 兜一个上限：最长的性能遍历是 60 分钟，留一倍余量。
     */
    private static final Duration MAX_HOLD = Duration.ofHours(2);

    /** 判断某个 refId 是否已被请求停止；由外部注入，避免这里反向依赖执行层 */
    private volatile java.util.function.Predicate<String> cancelledCheck = ref -> false;

    public void setCancelledCheck(java.util.function.Predicate<String> check) {
        if (check != null) {
            this.cancelledCheck = check;
        }
    }

    /**
     * 这个持有者是不是已经没救了。
     * <p>
     * 两种：已请求停止且过了宽限期、或者持有时间超过任何任务的合理上限。
     */
    boolean isStale(Holder h) {
        if (h == null || h.since() == null) {
            return false;
        }
        Duration held = Duration.between(h.since(), LocalDateTime.now());
        if (held.compareTo(MAX_HOLD) > 0) {
            return true;
        }
        return cancelledCheck.test(h.refId()) && held.compareTo(CANCELLED_GRACE) > 0;
    }

    /**
     * 占用设备。
     *
     * @param parentRefId 上级执行链标识；与当前持有者一致时视为同链重入（如线上回归 run 下派生的子任务），
     *                    此时保持原持有者不变，子任务释放时不会误放父锁。
     * @return 占用成功返回 {@code null}；已被他人占用时返回当前持有者
     */
    public Holder tryAcquire(String deviceId, Kind kind, String refId, String label, String parentRefId) {
        String key = normalize(deviceId);
        if (key == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException("refId 不能为空");
        }
        Holder desired = new Holder(kind, refId, label, LocalDateTime.now());
        Holder[] blocker = new Holder[1];
        holders.compute(key, (k, current) -> {
            if (current == null) {
                return desired;
            }
            // 自己已经持有：重复调用幂等
            if (refId.equals(current.refId())) {
                return current;
            }
            // 同一执行链的子级：沿用父持有者，不改写
            if (parentRefId != null && parentRefId.equals(current.refId())) {
                return current;
            }
            // 持有者已经没救了就抢过来。不这么做的话，一次线程异常退出会让这台设备
            // 一直占到重启后端为止，而人看到的是「任务明明停了还说被占用」
            if (isStale(current)) {
                log.warn("抢占失效的设备占用: deviceId={}, 原持有者={}, 自 {} 起",
                        key, current.refId(), current.since());
                return desired;
            }
            blocker[0] = current;
            return current;
        });
        if (blocker[0] == null) {
            log.debug("设备占用: deviceId={}, kind={}, refId={}", key, kind, refId);
        }
        return blocker[0];
    }

    /** 无上级链的占用 */
    public Holder tryAcquire(String deviceId, Kind kind, String refId, String label) {
        return tryAcquire(deviceId, kind, refId, label, null);
    }

    /**
     * 释放设备。只有持有者本人能释放——同链子级用自己的 refId 调用时是空操作，父锁不受影响。
     *
     * @return 真正释放了返回 true
     */
    public boolean release(String deviceId, String refId) {
        String key = normalize(deviceId);
        if (key == null || refId == null || refId.isBlank()) {
            return false;
        }
        boolean[] released = new boolean[1];
        holders.computeIfPresent(key, (k, current) -> {
            if (refId.equals(current.refId())) {
                released[0] = true;
                return null;
            }
            return current;
        });
        if (released[0]) {
            log.debug("设备释放: deviceId={}, refId={}", key, refId);
        }
        return released[0];
    }

    /**
     * 续期：把持有时间的起点重置到现在，持有者不变。
     * <p>
     * 借用有 {@link #MAX_HOLD} 的上限，到点会被当成失效占用抢走——这个上限是必要的
     * （不然一次忘记归还就等于永久占用），但人还在用的时候得能续。
     * <p>
     * 做成一个原子操作，而不是「先释放再重新占用」：那中间有个窗口，
     * 调度器正好在这一瞬间扫过就会把设备派给别人，而借的人毫不知情，
     * 两边同时操作同一台手机——这种问题查起来极其痛苦，不值得为省十行代码去冒。
     *
     * @return 续上了返回 true；不是你持有的返回 false
     */
    public boolean renew(String deviceId, String refId) {
        String key = normalize(deviceId);
        if (key == null || refId == null || refId.isBlank()) {
            return false;
        }
        boolean[] renewed = new boolean[1];
        holders.computeIfPresent(key, (k, current) -> {
            if (!refId.equals(current.refId())) {
                return current;
            }
            renewed[0] = true;
            return new Holder(current.kind(), current.refId(), current.label(), LocalDateTime.now());
        });
        return renewed[0];
    }

    /** 占用上限，供界面提示「最长能借多久」 */
    public static Duration maxHold() {
        return MAX_HOLD;
    }

    /** 强制释放（设备断开、人工解锁等），不校验持有者 */
    public Holder forceRelease(String deviceId) {
        String key = normalize(deviceId);
        if (key == null) {
            return null;
        }
        Holder removed = holders.remove(key);
        if (removed != null) {
            log.warn("强制释放设备占用: deviceId={}, 原持有者={}", key, removed.describe());
        }
        return removed;
    }

    /** 当前持有者，未被占用返回 null */
    public Holder current(String deviceId) {
        String key = normalize(deviceId);
        return key == null ? null : holders.get(key);
    }

    /** 指定持有者是否正持有该设备 */
    public boolean isHeldBy(String deviceId, String refId) {
        Holder holder = current(deviceId);
        return holder != null && holder.refId().equals(refId);
    }

    /** 全部占用快照，供设备列表/排查展示 */
    public Map<String, Holder> snapshot() {
        return new LinkedHashMap<>(holders);
    }

    private static String normalize(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? null : deviceId.trim();
    }
}
