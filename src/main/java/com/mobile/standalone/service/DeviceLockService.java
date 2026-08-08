package com.mobile.standalone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        INSPECT("元素采集");

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
