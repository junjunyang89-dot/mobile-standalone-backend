package com.mobile.standalone.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按保留天数算出"该删哪些",纯计算,不碰文件系统。
 *
 * <p><b>为什么要单独一个类</b>:原先的清理是 {@code Files.list(dir)} +
 * {@code filter(isRegularFile)}——<b>不递归</b>。那行注释写着"扁平文件,不按任务分目录",
 * 写的时候是对的;后来性能测试开始往 {@code perf_<taskId>/} 里写、兼容性测试往
 * {@code compatibility_<taskId>/} 里写,<b>假设过期了,而代码不会因此报错</b>。
 * 结果是这些子目录里的图一张都不会被清,只增不减。
 *
 * <p>把决策抽出来之后,下面三条容易做错的规则就能被单测钉住。
 *
 * <h2>目录不按自己的 mtime 判断</h2>
 * 这是最容易写错的一条。目录的 mtime 只反映"最后一次增删子项"的时间,
 * <b>不反映里面内容的新旧</b>。按目录 mtime 删的话,一个看起来很旧的目录里
 * 可能躺着今天刚生成的截图,而报告页面正指着它们——删完就是一片破图。
 * 所以:只有当一个目录里的东西<b>全部</b>都该删,这个目录才跟着删。
 *
 * <h2>删除顺序必须自底向上</h2>
 * 先删父目录会直接失败(非空)。所以输出按路径深度倒序排。
 *
 * <h2>拿不到 mtime 就不删</h2>
 * 和"样本不足不下结论"是同一个道理:不确定的时候,留着一张多余的图,
 * 比删掉一张别人报告里正在引用的图便宜得多。
 */
public final class RetentionPlanner {

    private RetentionPlanner() {
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * 一个待判定的条目。
     *
     * @param path       相对根目录的路径,用 {@code /} 分隔
     * @param directory  是目录吗
     * @param modifiedMs 最后修改时间;取不到传 0 或负数
     * @param sizeBytes  文件大小,目录传 0
     */
    public record Entry(String path, boolean directory, long modifiedMs, long sizeBytes) {
    }

    /**
     * @param deletePaths 该删的路径,<b>已按自底向上排序</b>,可以直接顺序执行
     * @param freedBytes  预计能释放多少字节
     * @param keptUnknown 因为拿不到 mtime 而保留的条目数——这个值持续不为 0 说明文件系统有问题
     */
    public record Plan(List<String> deletePaths, long freedBytes, int keptUnknown) {

        public int fileCount() {
            return (int) deletePaths.stream().filter(p -> !p.endsWith("/")).count();
        }
    }

    /**
     * 算出删除计划。
     *
     * @param entries    根目录下的所有条目(递归展开后的),路径相对根目录
     * @param nowMs      当前时间
     * @param retainDays 保留天数;≤0 表示"只要过了今天就删"
     */
    /** 顶层和子目录用同一个保留期 */
    public static Plan plan(List<Entry> entries, long nowMs, int retainDays) {
        return plan(entries, nowMs, retainDays, retainDays);
    }

    /**
     * 顶层文件和子目录里的文件用<b>不同</b>的保留期。
     *
     * <p>为什么要分开:这两类东西的性质完全不同,而它们混在同一个目录下。
     * <ul>
     *   <li><b>顶层扁平文件</b>是真机点选时的临时图,看完就没用了,留 2 天足够</li>
     *   <li><b>子目录</b>({@code perf_<taskId>/}、{@code compatibility_<taskId>/})
     *       是<b>任务报告的证据</b>——它们的路径被写进了报告 JSON,报告页面正指着它们。
     *       按临时图的 2 天去清,报告打开就是一片破图,而人只会觉得"报告坏了"</li>
     * </ul>
     *
     * <p>这一条是修递归时才暴露出来的:递归之前这些子目录<b>一张都不清</b>(所以"能一直看到"),
     * 修好递归之后如果还用同一个保留期,就等于把一个磁盘问题换成了一个数据丢失问题。
     *
     * @param retainDaysTopLevel 顶层文件的保留天数
     * @param retainDaysNested   子目录里内容的保留天数
     */
    public static Plan plan(List<Entry> entries, long nowMs,
                            int retainDaysTopLevel, int retainDaysNested) {
        List<String> toDelete = new ArrayList<>();
        long freed = 0;
        int unknown = 0;

        Set<String> deletableFiles = new HashSet<>();
        Map<String, List<Entry>> childrenOf = new HashMap<>();
        List<Entry> dirs = new ArrayList<>();

        if (entries != null) {
            for (Entry e : entries) {
                if (e == null || e.path() == null || e.path().isBlank()) {
                    continue;
                }
                childrenOf.computeIfAbsent(parentOf(e.path()), k -> new ArrayList<>()).add(e);
                if (e.directory()) {
                    dirs.add(e);
                    continue;
                }
                if (e.modifiedMs() <= 0) {
                    // 不确定就不删
                    unknown++;
                    continue;
                }
                int retain = depth(e.path()) > 1 ? retainDaysNested : retainDaysTopLevel;
                if (ageDays(e.modifiedMs(), nowMs) > retain) {
                    deletableFiles.add(e.path());
                    freed += Math.max(0, e.sizeBytes());
                }
            }
        }

        toDelete.addAll(deletableFiles);

        // 目录:只有当它下面所有东西都在删除集合里,才跟着删。
        // 从深到浅处理,这样子目录先被判定,父目录才能看到子目录已经"空了"
        dirs.sort(Comparator.comparingInt((Entry d) -> depth(d.path())).reversed());
        Set<String> emptiedDirs = new HashSet<>();
        for (Entry dir : dirs) {
            List<Entry> kids = childrenOf.getOrDefault(dir.path(), List.of());
            boolean allGone = true;
            for (Entry kid : kids) {
                boolean kidGone = kid.directory()
                        ? emptiedDirs.contains(kid.path())
                        : deletableFiles.contains(kid.path());
                if (!kidGone) {
                    allGone = false;
                    break;
                }
            }
            if (allGone) {
                emptiedDirs.add(dir.path());
                // 目录路径带尾斜杠,调用方一眼能分清删的是文件还是目录
                toDelete.add(dir.path() + "/");
            }
        }

        // 排序有两个作用,但只有第二个是它独有的:
        //
        // 1. 自底向上(深的先删)。这一条<b>其实上面的构造顺序已经保证了</b>——
        //    文件全部先入集合,目录再按深度倒序追加,而删文件从不会被别的文件挡住。
        //    去掉这次排序,19 条测试一条都不会红。留着是防止将来有人改动构造顺序时
        //    悄悄破坏这个不变量。
        // 2. <b>输出确定性</b>。文件是从 HashSet 里倒出来的,顺序每次都可能不同。
        //    dry-run 的结果是给人看的——同样的目录两次跑出来的清单顺序不一样,
        //    人没法 diff,也没法确认"和上次比多删了什么"。这一条是排序真正在守的东西。
        toDelete.sort(Comparator.comparingInt(RetentionPlanner::depth).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return new Plan(List.copyOf(toDelete), freed, unknown);
    }

    /** 过了几天。用整除:同一天内的算 0 天 */
    static long ageDays(long modifiedMs, long nowMs) {
        long diff = nowMs - modifiedMs;
        return diff <= 0 ? 0 : diff / DAY_MS;
    }

    private static int depth(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int n = 1;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }

    /** 顶层条目的父目录用空串表示根 */
    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
