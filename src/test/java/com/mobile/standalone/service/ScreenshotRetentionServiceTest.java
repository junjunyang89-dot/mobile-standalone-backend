package com.mobile.standalone.service;

import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截图保留与清理。这是会真删文件的功能，用临时目录跑真实文件操作，
 * 重点覆盖「该删的删掉、不该删的一个都别碰」。
 */
class ScreenshotRetentionServiceTest {

    @TempDir
    Path tempRoot;

    private Path flowDir;
    private Path inspectDir;
    private ScreenshotRetentionService service;
    private final Map<String, Task> tasks = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        flowDir = Files.createDirectories(tempRoot.resolve("screenshots"));
        inspectDir = Files.createDirectories(tempRoot.resolve("inspect"));

        TaskMapper taskMapper = org.mockito.Mockito.mock(TaskMapper.class);
        org.mockito.Mockito.when(taskMapper.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> tasks.get(inv.getArgument(0, String.class)));

        service = new ScreenshotRetentionService(taskMapper);
        ReflectionTestUtils.setField(service, "screenshotDir", flowDir.toString());
        ReflectionTestUtils.setField(service, "inspectDir", inspectDir.toString());
        ReflectionTestUtils.setField(service, "cleanupEnabled", true);
        ReflectionTestUtils.setField(service, "retainDaysFailed", 90);
        ReflectionTestUtils.setField(service, "retainDaysDefault", 14);
        ReflectionTestUtils.setField(service, "retainDaysInspect", 2);
        ReflectionTestUtils.setField(service, "maxTotalMb", 20480L);
    }

    // ==================== 保留天数 ====================

    @Test
    void 保留期内的目录不动() throws IOException {
        taskDir("task-recent", TaskStatusStub.COMPLETED, 3, 10);

        service.cleanup(false);

        assertTrue(Files.isDirectory(flowDir.resolve("task-recent")));
    }

    @Test
    void 超过默认保留天数的成功任务被清理() throws IOException {
        taskDir("task-old", TaskStatusStub.COMPLETED, 30, 10);

        var result = service.cleanup(false);

        assertFalse(Files.exists(flowDir.resolve("task-old")));
        assertEquals(1, result.getDeletedDirs());
    }

    /** 失败任务的图要留久：30 天前的失败任务不该被当成过期 */
    @Test
    void 失败任务在更长的保留期内不被清理() throws IOException {
        taskDir("task-failed", TaskStatusStub.FAILED, 30, 10);

        service.cleanup(false);

        assertTrue(Files.isDirectory(flowDir.resolve("task-failed")),
                "失败任务保留 90 天，30 天前的不该删");
    }

    @Test
    void 失败任务超过更长保留期后也会被清理() throws IOException {
        taskDir("task-ancient", TaskStatusStub.FAILED, 120, 10);

        service.cleanup(false);

        assertFalse(Files.exists(flowDir.resolve("task-ancient")));
    }

    /** 任务记录已删除的孤儿目录按默认天数处理，不能立刻删也不能永久留 */
    @Test
    void 孤儿目录按默认天数处理() throws IOException {
        Files.createDirectories(flowDir.resolve("orphan-young"));
        writeAged(flowDir.resolve("orphan-young").resolve("a.png"), 3, 10);
        Files.createDirectories(flowDir.resolve("orphan-old"));
        writeAged(flowDir.resolve("orphan-old").resolve("a.png"), 30, 10);

        service.cleanup(false);

        assertTrue(Files.exists(flowDir.resolve("orphan-young")));
        assertFalse(Files.exists(flowDir.resolve("orphan-old")));
    }

    // ==================== 试运行 ====================

    @Test
    void 试运行只统计不删除() throws IOException {
        taskDir("task-old", TaskStatusStub.COMPLETED, 30, 10);

        var result = service.cleanup(true);

        assertTrue(result.isDryRun());
        assertEquals(1, result.getDeletedDirs(), "试运行应统计出将被删除的目录");
        assertTrue(Files.isDirectory(flowDir.resolve("task-old")), "试运行不能真删");
        assertFalse(result.getSamples().isEmpty(), "应给出将被清理的样例说明");
    }

    // ==================== 总量上限 ====================

    @Test
    void 超过总量上限时最旧的先删() throws IOException {
        // 每个目录 2 个文件共 1.4MB，上限 2MB：删掉最旧的之后刚好降到上限以下，较新的应留住
        ReflectionTestUtils.setField(service, "maxTotalMb", 2L);
        // 都在保留期内，只能靠总量上限清理
        taskDir("task-oldest", TaskStatusStub.COMPLETED, 5, 700 * 1024);
        taskDir("task-newest", TaskStatusStub.COMPLETED, 1, 700 * 1024);

        var result = service.cleanup(false);

        assertTrue(result.getCapEvictedDirs() >= 1);
        assertFalse(Files.exists(flowDir.resolve("task-oldest")), "应先删最旧的");
        assertTrue(Files.exists(flowDir.resolve("task-newest")), "较新的应保留");
    }

    // ==================== 点选临时图 ====================

    @Test
    void 点选临时图按天数清理() throws IOException {
        writeAged(inspectDir.resolve("inspect_old.png"), 5, 10);
        writeAged(inspectDir.resolve("inspect_new.png"), 1, 10);

        var result = service.cleanup(false);

        assertFalse(Files.exists(inspectDir.resolve("inspect_old.png")));
        assertTrue(Files.exists(inspectDir.resolve("inspect_new.png")));
        assertEquals(1, result.getDeletedFiles());
    }

    // ==================== 占用统计 ====================

    @Test
    void 占用统计能反映目录数与总量() throws IOException {
        taskDir("t1", TaskStatusStub.COMPLETED, 1, 1024);
        taskDir("t2", TaskStatusStub.COMPLETED, 20, 1024);
        writeAged(inspectDir.resolve("x.png"), 1, 512);

        var stat = service.usage();

        assertEquals(2, stat.getTaskDirCount());
        assertTrue(stat.getFlowBytes() >= 2048);
        assertTrue(stat.getInspectBytes() >= 512);
        assertEquals(stat.getFlowBytes() + stat.getInspectBytes(), stat.getTotalBytes());
        assertTrue(stat.getOldestTaskDirAgeDays() >= 19);
    }

    @Test
    void 目录不存在时统计为零且不报错() {
        ReflectionTestUtils.setField(service, "screenshotDir", tempRoot.resolve("nope").toString());
        ReflectionTestUtils.setField(service, "inspectDir", tempRoot.resolve("nope2").toString());

        var stat = service.usage();
        var result = service.cleanup(false);

        assertEquals(0, stat.getTotalBytes());
        assertEquals(0, result.getDeletedDirs());
    }

    // ==================== 安全边界 ====================

    /** 配置写错也不能把根目录外的东西删掉 */
    @Test
    void 不会删除根目录之外的内容() throws IOException {
        Path outside = Files.createDirectories(tempRoot.resolve("important"));
        writeAged(outside.resolve("keep.txt"), 999, 10);

        service.cleanup(false);

        assertTrue(Files.exists(outside.resolve("keep.txt")), "根目录之外的文件绝不能被清理");
    }

    @Test
    void 根目录本身不会被删除() throws IOException {
        taskDir("task-old", TaskStatusStub.COMPLETED, 999, 10);

        service.cleanup(false);

        assertTrue(Files.isDirectory(flowDir), "清空之后根目录仍要在");
    }

    // ==================== 辅助 ====================

    /** 用 Task.TaskStatus 的名字，避免测试里直接依赖枚举顺序 */
    private enum TaskStatusStub {
        COMPLETED, FAILED
    }

    private void taskDir(String taskId, TaskStatusStub status, int ageDays, int bytes) throws IOException {
        Task task = new Task();
        task.setId(taskId);
        task.setStatus(Task.TaskStatus.valueOf(status.name()));
        tasks.put(taskId, task);

        Path dir = Files.createDirectories(flowDir.resolve(taskId));
        for (String name : List.of("step-1.png", "step-2.png")) {
            writeAged(dir.resolve(name), ageDays, bytes);
        }
    }

    private void writeAged(Path file, int ageDays, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file,
                FileTime.from(Instant.now().minus(Duration.ofDays(ageDays))));
    }
}
