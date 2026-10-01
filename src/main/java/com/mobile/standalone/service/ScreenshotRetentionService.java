package com.mobile.standalone.service;

import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.TaskMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import com.mobile.standalone.util.RetentionPlanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 截图产物的保留与清理。
 * <p>
 * 每条流程会留下几十张图，跑量上来之后磁盘是先顶不住的那一环——而且上了 AI 结果比对之后
 * 图还要长期留着追溯，只增不减必然出事。
 * <p>
 * 保留策略按任务成败区分：失败的任务图要留久一点（事后要查），成功的短一些（多半没人再看）。
 * 总量还超上限时按最旧优先删，保证磁盘有底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenshotRetentionService {

    private final TaskMapper taskMapper;

    @Value("${app.screenshot-dir:${user.home}/.mobile-standalone/screenshots}")
    private String screenshotDir;

    /** 真机点选的临时截图目录，只按天数清理 */
    @Value("${app.screenshot.inspect-dir:/tmp/screenshots}")
    private String inspectDir;

    @Value("${app.screenshot.cleanup-enabled:true}")
    private boolean cleanupEnabled;

    /** 失败任务的截图保留天数：事后排查往往要翻好几天前的 */
    @Value("${app.screenshot.retain-days-failed:7}")
    private int retainDaysFailed;

    /** 其余任务（成功/跳过/停止）的保留天数 */
    @Value("${app.screenshot.retain-days-default:7}")
    private int retainDaysDefault;

    /** 真机点选临时图的保留天数 */
    @Value("${app.screenshot.retain-days-inspect:2}")
    private int retainDaysInspect;

    /** {@code logs/} 下散落的调试 xml 目录 */
    @Value("${app.screenshot.stray-dump-dir:logs}")
    private String strayDumpDir;

    /** 那些调试 xml 留几天。跟日志同寿命即可——它们都是查当场问题用的 */
    @Value("${app.screenshot.retain-days-stray-dump:2}")
    private int retainDaysStrayDump;

    /** 总量上限（MB）；仍超出时按最旧优先删 */
    @Value("${app.screenshot.max-total-mb:20480}")
    private long maxTotalMb;

    /** 每天凌晨清一次，避开执行时段 */
    @Scheduled(cron = "${app.screenshot.cleanup-cron:0 30 3 * * ?}")
    public void scheduledCleanup() {
        if (!cleanupEnabled) {
            log.debug("截图清理已关闭（app.screenshot.cleanup-enabled=false）");
            return;
        }
        CleanupResult result = cleanup(false);
        if (result.getDeletedDirs() > 0 || result.getDeletedFiles() > 0) {
            log.info("截图清理完成: 删除 {} 个任务目录、{} 个临时文件，释放 {} MB，剩余 {} MB",
                    result.getDeletedDirs(), result.getDeletedFiles(),
                    toMb(result.getFreedBytes()), toMb(result.getRemainingBytes()));
        }
    }

    /**
     * 执行清理。
     *
     * @param dryRun true 时只统计不删除，用于先看清楚会删掉什么
     */
    public CleanupResult cleanup(boolean dryRun) {
        CleanupResult result = new CleanupResult();
        result.setDryRun(dryRun);

        Path root = safeRoot(screenshotDir);
        if (root != null) {
            cleanupTaskDirs(root, result, dryRun);
            enforceTotalCap(root, result, dryRun);
        }
        Path inspect = safeRoot(inspectDir);
        if (inspect != null) {
            cleanupInspectFiles(inspect, result, dryRun);
        }
        cleanupStrayDebugDumps(result, dryRun);
        result.setRemainingBytes(dirSize(root) + dirSize(inspect));
        return result;
    }

    /** 当前占用情况，供前端/排查查看 */
    public UsageStat usage() {
        UsageStat stat = new UsageStat();
        Path root = safeRoot(screenshotDir);
        stat.setFlowDir(screenshotDir);
        stat.setInspectDir(inspectDir);
        stat.setFlowBytes(dirSize(root));
        stat.setInspectBytes(dirSize(safeRoot(inspectDir)));
        stat.setTotalBytes(stat.getFlowBytes() + stat.getInspectBytes());
        stat.setMaxTotalMb(maxTotalMb);
        stat.setRetainDaysFailed(retainDaysFailed);
        stat.setRetainDaysDefault(retainDaysDefault);
        stat.setCleanupEnabled(cleanupEnabled);

        if (root != null) {
            try (Stream<Path> dirs = Files.list(root)) {
                List<Path> list = dirs.filter(Files::isDirectory).toList();
                stat.setTaskDirCount(list.size());
                list.stream()
                        .map(this::lastModified)
                        .filter(t -> t > 0)
                        .min(Long::compare)
                        .ifPresent(oldest -> stat.setOldestTaskDirAgeDays(ageDays(oldest)));
            } catch (IOException e) {
                log.warn("统计截图目录失败: {}", e.getMessage());
            }
        }
        return stat;
    }

    /** 按任务成败决定保留天数，过期的整目录删掉 */
    private void cleanupTaskDirs(Path root, CleanupResult result, boolean dryRun) {
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                long modified = lastModified(dir);
                if (modified <= 0) {
                    continue;
                }
                long age = ageDays(modified);
                int retain = retainDaysFor(dir.getFileName().toString());
                if (age <= retain) {
                    continue;
                }
                long size = dirSize(dir);
                if (!dryRun && !deleteRecursively(root, dir)) {
                    continue;
                }
                result.setDeletedDirs(result.getDeletedDirs() + 1);
                result.setFreedBytes(result.getFreedBytes() + size);
                result.getSamples().add(dir.getFileName() + "（" + age + " 天前，保留 " + retain + " 天，"
                        + toMb(size) + " MB）");
            }
        } catch (IOException e) {
            log.warn("清理截图任务目录失败: {}", e.getMessage());
        }
    }

    /**
     * 任务已被删除时按默认天数处理——孤儿目录没人认领，但也不能立刻删，
     * 有可能是任务记录先被清理、图还需要留着看。
     */
    private int retainDaysFor(String taskId) {
        try {
            Task task = taskMapper.findById(taskId);
            if (task != null && task.getStatus() == Task.TaskStatus.FAILED) {
                return retainDaysFailed;
            }
        } catch (Exception e) {
            log.debug("查询任务状态失败，按默认保留天数处理: taskId={}", taskId);
        }
        return retainDaysDefault;
    }

    /**
     * 按天数清理临时截图目录。<b>递归</b>。
     * <p>
     * 原先这里是 {@code Files.list(dir)} + {@code filter(isRegularFile)}——不递归，
     * 而且注释写着「扁平文件，不按任务分目录」。那句在写的时候是对的，
     * 后来性能测试开始往 {@code perf_<taskId>/} 写、兼容性测试往 {@code compatibility_<taskId>/} 写，
     * <b>假设过期了，代码却不会因此报错</b>：那些子目录里的图一张都不清，只增不减。
     * 6 台设备 × 50 人跑性能任务（每次约 120 张），量级和单人调试完全不是一回事。
     * <p>
     * 判定交给 {@link RetentionPlanner}——那里能单测，
     * 尤其是「目录不能按自己的 mtime 判断」这条：一个看起来很旧的目录里
     * 可能躺着今天刚生成、报告正在引用的图。
     */
    private void cleanupInspectFiles(Path dir, CleanupResult result, boolean dryRun) {
        List<RetentionPlanner.Entry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.toList()) {
                if (p.equals(dir)) {
                    continue; // 根目录自己永远不删
                }
                boolean isDir = Files.isDirectory(p);
                entries.add(new RetentionPlanner.Entry(
                        dir.relativize(p).toString().replace(java.io.File.separatorChar, '/'),
                        isDir,
                        lastModified(p),
                        isDir ? 0 : fileSize(p)));
            }
        } catch (IOException e) {
            log.warn("清理临时截图失败（遍历阶段）: {}", e.getMessage());
            return;
        }

        // 顶层用 inspect 的短保留期，子目录用默认保留期。
        //
        // 这个目录下混着两类性质完全不同的东西：
        //   顶层扁平文件 = 真机点选的临时图，看完就没用；
        //   perf_<taskId>/ 和 compatibility_<taskId>/ = <b>任务报告的证据</b>，
        //   路径被写进了报告 JSON，报告页面正指着它们。
        //
        // 递归之前这些子目录一张都不清（所以"报告能一直看到"，代价是磁盘只增不减）。
        // 修好递归之后如果还用 2 天的 inspect 保留期，就等于把一个磁盘问题
        // 换成了一个数据丢失问题——人打开上周的性能报告看到一片破图，
        // 只会觉得"报告坏了"，不会想到是清理干的。
        RetentionPlanner.Plan plan = RetentionPlanner.plan(
                entries, System.currentTimeMillis(), retainDaysInspect, retainDaysDefault);
        if (plan.keptUnknown() > 0) {
            // 拿不到修改时间的文件一直存在，说明这个目录有问题，别默默留着
            log.warn("临时截图目录里有 {} 个文件拿不到修改时间，本轮跳过它们: {}",
                    plan.keptUnknown(), dir);
        }

        for (String rel : plan.deletePaths()) {
            boolean isDir = rel.endsWith("/");
            Path target = dir.resolve(isDir ? rel.substring(0, rel.length() - 1) : rel);
            if (dryRun) {
                if (!isDir) {
                    result.setDeletedFiles(result.getDeletedFiles() + 1);
                }
                continue;
            }
            if (isDir) {
                // 空目录删不掉不算错（可能刚被别人写进新文件），跳过就行
                deleteEmptyDir(dir, target);
                continue;
            }
            if (!deleteSingle(dir, target)) {
                continue;
            }
            result.setDeletedFiles(result.getDeletedFiles() + 1);
        }
        // dryRun 和真删都报同一个预计释放量：dryRun 的意义就是「先看看会删多少」
        result.setFreedBytes(result.getFreedBytes() + plan.freedBytes());
    }

    /** 删空目录。越界检查和 deleteSingle 一致，防止配置写错时删到目录外 */
    private void deleteEmptyDir(Path root, Path target) {
        if (!isInside(root, target)) {
            log.error("拒绝删除截图目录外的目录: {}", target);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 非空（并发写入）或权限问题，下一轮再说
            log.debug("删除空目录失败: {}", target);
        }
    }

    /**
     * {@code logs/} 下那些调试用的 xml。
     *
     * <p>{@code dumpAllContextsForDebug} 之类会往 {@code logs/} 直接写
     * {@code cs_debug_*.xml}、{@code window_dump.xml}，<b>从来没人清</b>——
     * 实测里躺着大半年前的文件。它们不归 logback 管（logback 只轮转自己那个 .log），
     * 所以得单独扫一遍。
     *
     * <p>只删认得出的那几种命名，<b>绝不碰 .log / .gz</b>：
     * 日志轮转是 logback 的事，两边都去删同一批文件迟早出岔子。
     */
    private void cleanupStrayDebugDumps(CleanupResult result, boolean dryRun) {
        Path dir = safeRoot(strayDumpDir);
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".xml")) {
                    continue;
                }
                long modified = lastModified(f);
                if (modified <= 0 || ageDays(modified) <= retainDaysStrayDump) {
                    continue;
                }
                long size = fileSize(f);
                if (!dryRun && !deleteSingle(dir, f)) {
                    continue;
                }
                result.setDeletedFiles(result.getDeletedFiles() + 1);
                result.setFreedBytes(result.getFreedBytes() + size);
            }
        } catch (IOException e) {
            log.warn("清理 logs 下的调试 xml 失败: {}", e.getMessage());
        }
    }

    /** 按天数清完仍超上限时，最旧的先删，直到降到上限以下 */
    private void enforceTotalCap(Path root, CleanupResult result, boolean dryRun) {
        long cap = maxTotalMb * 1024L * 1024L;
        if (cap <= 0) {
            return;
        }
        long total = dirSize(root);
        if (total <= cap) {
            return;
        }
        log.warn("截图总量 {} MB 超过上限 {} MB，按最旧优先清理", toMb(total), maxTotalMb);
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> oldestFirst = new ArrayList<>(dirs.filter(Files::isDirectory).toList());
            oldestFirst.sort(Comparator.comparingLong(this::lastModified));
            for (Path dir : oldestFirst) {
                if (total <= cap) {
                    break;
                }
                long size = dirSize(dir);
                if (!dryRun && !deleteRecursively(root, dir)) {
                    continue;
                }
                total -= size;
                result.setDeletedDirs(result.getDeletedDirs() + 1);
                result.setFreedBytes(result.getFreedBytes() + size);
                result.setCapEvictedDirs(result.getCapEvictedDirs() + 1);
                result.getSamples().add(dir.getFileName() + "（超总量上限被清理，" + toMb(size) + " MB）");
            }
        } catch (IOException e) {
            log.warn("按总量上限清理失败: {}", e.getMessage());
        }
    }

    // ==================== 文件操作（都带边界校验） ====================

    /** 目录必须存在且是目录；顺便规范化，后续删除都以它为边界 */
    private Path safeRoot(String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path path = Path.of(dir).toAbsolutePath().normalize();
        return Files.isDirectory(path) ? path : null;
    }

    /** 删除前确认目标确实在根目录之下，避免配置写错时把别处的东西删掉 */
    private boolean deleteRecursively(Path root, Path target) {
        if (!isInside(root, target) || target.equals(root)) {
            log.error("拒绝删除截图目录外的路径: {}", target);
            return false;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("删除失败: {}", p);
                }
            });
            return !Files.exists(target);
        } catch (IOException e) {
            log.warn("删除截图目录失败: {}, {}", target, e.getMessage());
            return false;
        }
    }

    private boolean deleteSingle(Path root, Path file) {
        if (!isInside(root, file)) {
            log.error("拒绝删除截图目录外的文件: {}", file);
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("删除文件失败: {}", file);
            return false;
        }
    }

    private static boolean isInside(Path root, Path target) {
        return root != null && target != null
                && target.toAbsolutePath().normalize().startsWith(root);
    }

    /** 目录取其中文件的最新修改时间：目录本身的 mtime 不随内部文件变化 */
    private long lastModified(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.getLastModifiedTime(path).toMillis();
            }
            try (Stream<Path> walk = Files.walk(path)) {
                return walk.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        })
                        .max()
                        .orElseGet(() -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        });
            }
        } catch (IOException e) {
            return 0L;
        }
    }

    private long dirSize(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(this::fileSize).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long ageDays(long modifiedMillis) {
        return Duration.between(Instant.ofEpochMilli(modifiedMillis), Instant.now()).toDays();
    }

    private static long toMb(long bytes) {
        return bytes / 1024 / 1024;
    }

    @Data
    public static class CleanupResult {
        private boolean dryRun;
        private int deletedDirs;
        private int deletedFiles;
        /** 其中因超总量上限被清理的目录数 */
        private int capEvictedDirs;
        private long freedBytes;
        private long remainingBytes;
        /** 被清理对象的样例说明，便于确认策略是否符合预期 */
        private final List<String> samples = new ArrayList<>();

        public long getFreedMb() {
            return toMb(freedBytes);
        }

        public long getRemainingMb() {
            return toMb(remainingBytes);
        }
    }

    @Data
    public static class UsageStat {
        private String flowDir;
        private String inspectDir;
        private long flowBytes;
        private long inspectBytes;
        private long totalBytes;
        private int taskDirCount;
        private long oldestTaskDirAgeDays;
        private long maxTotalMb;
        private int retainDaysFailed;
        private int retainDaysDefault;
        private boolean cleanupEnabled;

        public long getTotalMb() {
            return toMb(totalBytes);
        }

        public long getFlowMb() {
            return toMb(flowBytes);
        }

        public long getInspectMb() {
            return toMb(inspectBytes);
        }

        /** 已用占上限的百分比，超过 80 该处理了 */
        public int getUsagePercent() {
            long cap = maxTotalMb * 1024L * 1024L;
            return cap <= 0 ? 0 : (int) Math.min(100, totalBytes * 100 / cap);
        }
    }
}
