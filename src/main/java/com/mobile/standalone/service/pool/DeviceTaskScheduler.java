package com.mobile.standalone.service.pool;

import com.mobile.standalone.entity.Task;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.TaskExecution;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.service.DeviceLockService;
import com.mobile.standalone.service.TaskAppResolver;
import com.mobile.standalone.service.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设备池排队调度：任务不指定具体设备时，由这里挑一台空闲的派过去。
 *
 * <p><b>为什么需要它</b>：六台设备（真机 + 模拟器）供全组用的时候，
 * 让人每次执行都自己挑一台是行不通的——
 * 挑到的那台可能正被别人跑着，于是「设备正被占用」直接失败；
 * 而人的反应是换一台再点一次，几个人同时这么干就变成互相抢。
 * 正确的做法是把「挑哪台」交给平台：没有空闲的就排队，空出来一台就派下去。
 *
 * <p><b>为什么是轮询而不是事件驱动</b>：设备空出来有好几种来路——任务正常跑完、
 * 被人停掉、执行线程崩了被锁服务判定失效回收、有人归还借用的设备。
 * 每一处都去发一个「我空了」的事件，迟早会漏掉一处，而漏掉的现象是
 * 任务永远排在队里没人管。三秒一轮的轮询没有这个问题：
 * 不管因为什么空出来的，下一轮都会看到。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceTaskScheduler {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一轮最多派几个：留出余量给同一轮里别人手动发起的执行，不要一次把设备吃光 */
    private static final int MAX_DISPATCH_PER_TICK = 3;

    /**
     * 排队多久还没派出去就判失败。
     * <p>
     * 不设上限的话，一个条件根本满足不了的任务（比如要 iOS 但池子里一台 iOS 都没有）
     * 会永远排着，而界面上显示「排队中」——人会一直等，等到下班才发现不对。
     * 超时失败并把原因写清楚，比无限期排队诚实得多。
     */
    private static final Duration QUEUE_TIMEOUT = Duration.ofMinutes(30);

    private final TaskMapper taskMapper;
    private final DeviceMapper deviceMapper;
    private final DevicePoolReader poolReader;
    private final DeviceLockService deviceLockService;
    private final TaskService taskService;
    private final TaskAppResolver taskAppResolver;
    private final TaskExecutionMapper executionMapper;

    @Scheduled(fixedDelayString = "${app.device-pool.dispatch-interval-ms:3000}")
    public void dispatch() {
        List<Task> queued;
        try {
            queued = taskMapper.findQueued(20);
        } catch (Exception e) {
            log.debug("[device-pool] 取排队任务失败: {}", e.getMessage());
            return;
        }
        if (queued == null || queued.isEmpty()) {
            return;
        }

        // 本轮已经派出去的设备：锁虽然当场就拿了，但多一层保险，
        // 免得同一轮里两个任务算出同一台
        Set<String> takenThisTick = new HashSet<>();
        int dispatched = 0;

        for (Task task : queued) {
            if (dispatched >= MAX_DISPATCH_PER_TICK) {
                break;
            }
            // 要求和池子都走公共那份：自动起模拟器的那个用的是同一套，
            // 两边各写一份的话迟早出现「调度器认为派不出去、扩容器认为不用起」的僵局
            DevicePicker.Requirement req = TaskDeviceRequirement.of(task);
            List<DevicePicker.Candidate> pool = poolReader.snapshot(takenThisTick);
            DevicePicker.Result result = DevicePicker.pick(pool, req);

            if (!result.ok()) {
                expireIfWaitedTooLong(task, result.reason());
                continue;
            }

            String deviceId = result.picked().id();
            // 先把锁拿到手再改库：反过来的话，两个进程（或人手动点的执行）会同时认为这台是空的。
            // refId 用 taskId，与 TaskService.runTask 里的一致——它再 tryAcquire 一次是幂等的，
            // 释放也仍然由执行线程的 finally 负责，这里不重复管
            DeviceLockService.Holder blocker = deviceLockService.tryAcquire(
                    deviceId, DeviceLockService.Kind.TASK, task.getId(),
                    task.getName() != null ? task.getName() : task.getId());
            if (blocker != null) {
                // 刚才还是空的，这会儿被别人抢了。不算异常，下一轮再说
                takenThisTick.add(deviceId);
                continue;
            }

            try {
                // 应用要等设备定下来才能解析：apps 是按设备记录「这台上装的是哪个包」的，
                // 排队的时候还不知道会派到哪一台，自然也解析不了
                if (!resolveApp(task, deviceId)) {
                    deviceLockService.release(deviceId, task.getId());
                    continue;
                }
                task.setDeviceId(deviceId);
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.update(task);
                deviceMapper.markUsed(deviceId, LocalDateTime.now());
                takenThisTick.add(deviceId);
                dispatched++;
                log.info("[device-pool] 派活: task={} → device={}（排队 {} 分钟）",
                        task.getId(), deviceId, waitedMinutes(task));
                taskService.enqueueRun(task.getId());
            } catch (Exception e) {
                // 派发过程中出错就把锁还回去，否则这台设备会一直挂着一个不存在的持有者
                deviceLockService.release(deviceId, task.getId());
                log.warn("[device-pool] 派活失败 task={} device={}: {}", task.getId(), deviceId, e.getMessage());
            }
        }
    }

    /**
     * 派到设备之后解析应用。
     * <p>
     * 解析不了是配置问题（字典里没配包名之类），不是排队问题——继续排下去也不会变好，
     * 所以直接判失败并说清楚，而不是让它在队里耗满 30 分钟再超时。
     *
     * @return true 表示可以继续派发
     */
    private boolean resolveApp(Task task, String deviceId) {
        try {
            JsonNode cfg = JSON.readTree(task.getConfig() == null ? "{}" : task.getConfig());
            String uiAppId = cfg.hasNonNull("uiAppId") ? cfg.get("uiAppId").asText(null) : null;
            String packageName = cfg.hasNonNull("packageName") ? cfg.get("packageName").asText(null) : null;
            Application app = taskAppResolver.resolveForTask(
                    uiAppId, deviceId, task.getPlatform(), packageName);
            task.setAppId(app != null ? app.getId() : null);
            return true;
        } catch (Exception e) {
            log.warn("[device-pool] 解析应用失败，任务判失败 task={}: {}", task.getId(), e.getMessage());
            blockTask(task, "分配到设备 " + deviceId + " 后无法确定要测的应用：" + e.getMessage());
            return false;
        }
    }

    /** 排太久还派不出去就失败，并把「为什么派不出去」写进错误信息 */
    private void expireIfWaitedTooLong(Task task, String reason) {
        LocalDateTime since = task.getCreatedAt();
        if (since == null || Duration.between(since, LocalDateTime.now()).compareTo(QUEUE_TIMEOUT) < 0) {
            return;
        }
        blockTask(task, "排队 " + QUEUE_TIMEOUT.toMinutes() + " 分钟仍未分配到设备："
                + (reason == null ? "没有可用设备" : reason));
        log.warn("[device-pool] 排队超时: task={} 原因={}", task.getId(), reason);
    }

    /**
     * 判任务失败，并把原因写进执行记录。
     * <p>
     * 光把状态改成 FAILED 是不够的：任务本身没有存错误信息的地方，
     * 界面上的失败原因、执行日志都来自 task_executions。不写一条的话，
     * 人看到的是一个红色的任务、点进去一片空白——「失败了但不知道为什么」
     * 比失败本身更耗时间。
     */
    /**
     * 排班这一层的失败一律是 <b>BLOCKED</b>，不是 FAILED。
     *
     * <p>排队超时、分配到设备后取不到应用——这两件事都发生在<b>一条断言都还没跑</b>之前。
     * 记成 FAILED 的话，设备池一紧张，通过率就会跟着掉，
     * 而看报告的人会以为是用例坏了，去查一个根本没执行过的流程。
     */
    private void blockTask(Task task, String reason) {
        LocalDateTime now = LocalDateTime.now();
        try {
            task.setStatus(Task.TaskStatus.BLOCKED);
            task.setUpdatedAt(now);
            taskMapper.update(task);
        } catch (Exception e) {
            log.warn("[device-pool] 写 BLOCKED 状态失败 task={}: {}", task.getId(), e.getMessage());
        }
        try {
            TaskExecution execution = new TaskExecution();
            execution.setId(java.util.UUID.randomUUID().toString());
            execution.setTaskId(task.getId());
            execution.setStatus(TaskExecution.ExecutionStatus.BLOCKED);
            execution.setStartTime(task.getCreatedAt() != null ? task.getCreatedAt() : now);
            execution.setEndTime(now);
            execution.setErrorMessage(reason);
            execution.setExecutionLog(JSON.writeValueAsString(
                    List.of(ExecutionLogEntryDto.fail("设备排班", reason, null))));
            executionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[device-pool] 写 BLOCKED 执行记录失败 task={}: {}", task.getId(), e.getMessage());
        }
    }

    private long waitedMinutes(Task task) {
        return task.getCreatedAt() == null
                ? 0 : Duration.between(task.getCreatedAt(), LocalDateTime.now()).toMinutes();
    }

}
