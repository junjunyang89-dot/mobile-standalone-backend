package com.mobile.standalone.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一批失败按<b>原因</b>归类。
 * <p>
 * 一次回归红 20 条，逐条查是最费时间的做法——而且多半白费：
 * 那 20 条里往往 18 条是同一个原因（登录服务挂了、token 过期、某台设备掉线），
 * 真正不同的问题只有两三个。
 * <p>
 * 所以打开报告第一眼该看到的是「实际上是 3 个问题」，而不是 20 行红。
 * 先收敛再逐个查，顺序反了会把时间花在重复现象上。
 */
public final class FailureCluster {

    private FailureCluster() {
    }

    @Data
    public static class Cluster {
        /** 这一类的代表性原因，直接可读 */
        private String reason;
        /** 归到这一类的失败数 */
        private int count;
        /** 涉及哪些流程/用例，最多列几个 */
        private List<String> samples = new ArrayList<>();
        /**
         * 优先级：一类里条数越多越该先查——它挡住的东西最多。
         * 而且条数多本身就是信号：20 条都挂在登录，问题一定在登录，不在那 20 个功能上。
         */
        private int rank;
        /** 一句话建议 */
        private String hint;
    }

    /** 一条失败：哪个流程、失败在哪一步、报了什么 */
    public record Failure(String label, String failedStep, String message) {
    }

    private static final int MAX_SAMPLES = 5;

    /**
     * 归类。
     * <p>
     * 键取「失败步骤 + 报错指纹」：同一步同一个错才算一类。
     * 只按步骤分会把「登录超时」和「登录密码错」混成一类——那是两个问题；
     * 只按报错分会把不同步骤的同类超时混起来，定位时还得再拆一遍。
     */
    public static List<Cluster> cluster(List<Failure> failures) {
        List<Cluster> out = new ArrayList<>();
        if (failures == null || failures.isEmpty()) {
            return out;
        }
        Map<String, Cluster> byKey = new LinkedHashMap<>();
        for (Failure f : failures) {
            if (f == null) {
                continue;
            }
            String step = f.failedStep() == null ? "" : f.failedStep().trim();
            String key = step + "|" + fingerprint(f.message());
            Cluster c = byKey.computeIfAbsent(key, k -> {
                Cluster n = new Cluster();
                n.setReason(readableReason(step, f.message()));
                n.setHint(hintFor(f.message()));
                return n;
            });
            c.setCount(c.getCount() + 1);
            if (c.getSamples().size() < MAX_SAMPLES && f.label() != null) {
                c.getSamples().add(f.label());
            }
        }
        out.addAll(byKey.values());
        // 条数多的排前面：它挡住的东西最多，也最可能是根因
        out.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        for (int i = 0; i < out.size(); i++) {
            out.get(i).setRank(i + 1);
        }
        return out;
    }

    /**
     * 报错指纹：抹掉每次都变的部分。
     * <p>
     * 不抹的话「等待 5000ms 超时」和「等待 8000ms 超时」会被算成两个问题，
     * 20 条失败还是 20 类——聚类就白做了。
     */
    static String fingerprint(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String s = message.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("[/\\\\][\\w.#-]+", "/p")
                .replaceAll("\\s+", " ");
        return s.length() <= 60 ? s : s.substring(0, 60);
    }

    private static String readableReason(String step, String message) {
        String m = message == null || message.isBlank() ? "未知失败" : message.trim();
        if (m.length() > 80) {
            m = m.substring(0, 80) + "…";
        }
        return step.isEmpty() ? m : step + "：" + m;
    }

    /**
     * 按报错给一句可执行的建议。
     * <p>
     * 只对<b>认得出的</b>给，认不出就不给——瞎给一句「请检查配置」比不给更糟，
     * 它会让人以为系统知道原因。
     */
    static String hintFor(String message) {
        if (message == null) {
            return null;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("401") || m.contains("token") || m.contains("登录") || m.contains("未授权")) {
            return "集中在登录态上。先确认账号能手动登进去、token 没过期，再看功能本身";
        }
        if (m.contains("找不到元素") || m.contains("no such element") || m.contains("元素定位")) {
            return "元素定位失效。多半是这一版改了界面——去元素管理核对，或用真机点选重采";
        }
        if (m.contains("超时") || m.contains("timeout")) {
            return "全是超时。先排除环境问题（服务慢、设备卡、网络），别急着当成功能 bug";
        }
        if (m.contains("设备") || m.contains("device") || m.contains("adb")) {
            return "设备侧问题。检查连接、USB 授权、是否被别的任务占着";
        }
        if (m.contains("安装") || m.contains("install")) {
            return "装包失败。先单独跑一次安装流程，跟功能验证分开看";
        }
        return null;
    }

    /** 给报告顶部用的一句话 */
    public static String summarize(int failedCount, List<Cluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return failedCount > 0 ? "有 " + failedCount + " 条失败" : "全部通过";
        }
        if (clusters.size() == 1) {
            return failedCount + " 条失败其实是<b>同一个问题</b>：" + clusters.get(0).getReason();
        }
        return failedCount + " 条失败归为 <b>" + clusters.size() + " 类</b>，最多的一类占 "
                + clusters.get(0).getCount() + " 条：" + clusters.get(0).getReason();
    }
}
