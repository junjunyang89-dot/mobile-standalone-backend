package com.mobile.standalone.service.pool;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「这个任务该派给哪台设备」——规则本体。
 *
 * <p>刻意做成不依赖 Spring、不碰数据库的纯函数：这套规则错一条的后果是
 * <b>任务跑在了不该跑的设备上</b>，而且不会报错——测试包的用例跑到了生产机、
 * 生产巡检落到了模拟器。这两种都会安静地给出一份看着正常的结果，
 * 所以它不该只靠肉眼 review，得能直接测。
 *
 * <p>轮转用的是 LRU（最久没用过的先派），不是 round-robin：
 * 设备会上线下线、会被人借走，轮转指针那套一遇到设备增减就乱。
 * 而「谁闲得最久谁上」不需要记状态，天然均衡，设备增减也不影响。
 */
public final class DevicePicker {

    private DevicePicker() {
    }

    /** 候选设备的判定所需信息，从 devices 表取 */
    public record Candidate(
            String id,
            String name,
            String platform,
            /** 设备绑的环境：PROD / TEST / null（未绑＝哪边都能跑） */
            String environment,
            boolean emulator,
            boolean connected,
            /** 人工停用（坏了、在维护），不参与派发 */
            boolean disabled,
            /** 当前是否被占用：跑任务、被人借走都算 */
            boolean busy,
            /** 上次派活的时间，null 当成「从没用过」，优先派 */
            LocalDateTime lastUsedAt
    ) {
    }

    /** 任务对设备的要求 */
    public record Requirement(
            /** ANDROID / IOS / HARMONYOS；null＝不限 */
            String platform,
            /** 这次要跑的环境：PROD / TEST */
            String environment,
            /** 允不允许派给模拟器 */
            boolean allowEmulator
    ) {
    }

    /** 挑不到时为什么挑不到——「没有可用设备」这句话本身没有任何排查价值 */
    public record Result(Candidate picked, String reason) {
        public boolean ok() {
            return picked != null;
        }
    }

    /**
     * 从池子里挑一台。
     *
     * @return 挑中的设备；挑不到时 {@code picked} 为 null，{@code reason} 说清楚是被哪一条挡住的
     */
    public static Result pick(List<Candidate> pool, Requirement req) {
        if (pool == null || pool.isEmpty()) {
            return new Result(null, "设备池里一台设备都没有");
        }
        if (req == null) {
            return new Result(null, "没有说清楚要什么样的设备");
        }

        // 逐层过滤，每一层都记下被筛掉多少——挑不到的时候，
        // 「6 台里 4 台在忙、2 台环境不对」比「没有可用设备」有用得多
        int total = pool.size();
        List<Candidate> alive = pool.stream().filter(c -> c.connected() && !c.disabled()).toList();
        if (alive.isEmpty()) {
            return new Result(null, "共 " + total + " 台，全都离线或已停用");
        }

        List<Candidate> platformOk = alive.stream().filter(c -> platformMatches(c, req)).toList();
        if (platformOk.isEmpty()) {
            return new Result(null, "共 " + alive.size() + " 台在线，没有一台是 "
                    + (req.platform() == null ? "所需平台" : req.platform()));
        }

        List<Candidate> kindOk = platformOk.stream()
                .filter(c -> req.allowEmulator() || !c.emulator())
                .toList();
        if (kindOk.isEmpty()) {
            return new Result(null, "在线的 " + platformOk.size() + " 台全是模拟器，"
                    + "而这次执行不允许用模拟器（正式执行与生产环境只用真机）");
        }

        List<Candidate> envOk = kindOk.stream().filter(c -> environmentMatches(c, req)).toList();
        if (envOk.isEmpty()) {
            return new Result(null, "有 " + kindOk.size() + " 台可用，但没有一台绑的是「"
                    + envLabel(req.environment()) + "」。"
                    + "去设备页把其中一台的环境改过来，或者把它的环境留空（留空＝两边都能跑）");
        }

        List<Candidate> free = envOk.stream().filter(c -> !c.busy()).toList();
        if (free.isEmpty()) {
            return new Result(null, "符合条件的 " + envOk.size() + " 台都在忙，先排队等");
        }

        // LRU：最久没派过活的先上。从没用过的（lastUsedAt 为 null）排最前
        Candidate picked = free.stream()
                .min(Comparator
                        .comparing(DevicePicker::lastUsedKey)
                        // 时间相同就按 id 定序，保证同样的输入永远挑同一台，便于复现问题
                        .thenComparing(Candidate::id))
                .orElse(null);
        return new Result(picked, null);
    }

    /**
     * 从没用过的排最前。
     * <p>
     * 用 {@link LocalDateTime#MIN} 而不是把 null 排到最后：一台刚接进来的设备
     * 本来就该先被用上，把它排到最后，等于新设备要等所有老设备都轮一遍才轮得到。
     */
    private static LocalDateTime lastUsedKey(Candidate c) {
        return c.lastUsedAt() == null ? LocalDateTime.MIN : c.lastUsedAt();
    }

    private static boolean platformMatches(Candidate c, Requirement req) {
        if (req.platform() == null || req.platform().isBlank()) {
            return true;
        }
        return norm(c.platform()).equals(norm(req.platform()));
    }

    /**
     * 环境匹配。
     * <p>
     * 设备没绑环境＝通配，两边都能跑；绑了就必须一致。
     * 这一条不能放松：设备上装的就是某个环境的包，派错了的现象是「登录失败」，
     * 指向的方向完全不对——人会去查账号、查网络，最后才想到是包不对。
     */
    private static boolean environmentMatches(Candidate c, Requirement req) {
        String want = norm(req.environment());
        String has = norm(c.environment());
        if (has.isEmpty()) {
            return true;
        }
        if (want.isEmpty()) {
            return true;
        }
        return has.equals(want);
    }

    private static String envLabel(String env) {
        return "TEST".equalsIgnoreCase(env == null ? "" : env.trim()) ? "测试环境" : "生产环境";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
