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
    @Value("${app.screenshot.retain-days-failed:90}")
    private int retainDaysFailed;

    /** 其余任务（成功/跳过/停止）的保留天数 */
    @Value("${app.screenshot.retain-days-default:14}")
    private int retainDaysDefault;

    /** 真机点选临时图的保留天数 */
    @Value("${app.screenshot.retain-days-inspect:2}")
    private int retainDaysInspect;

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

    /** 按天数清理真机点选的临时图（扁平文件，不按任务分目录） */
    private void cleanupInspectFiles(Path dir, CleanupResult result, boolean dryRun) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                long modified = lastModified(file);
                if (modified <= 0 || ageDays(modified) <= retainDaysInspect) {
                    continue;
                }
                long size = fileSize(file);
                if (!dryRun && !deleteSingle(dir, file)) {
                    continue;
                }
                result.setDeletedFiles(result.getDeletedFiles() + 1);
                result.setFreedBytes(result.getFreedBytes() + size);
            }
        } catch (IOException e) {
            log.warn("清理点选临时截图失败: {}", e.getMessage());
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
