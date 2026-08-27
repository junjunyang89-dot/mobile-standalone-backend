package com.mobile.standalone.service.apitest;

import com.mobile.standalone.config.NodeIdentity;
import com.mobile.standalone.dto.ApiTestDtos.RunRequest;
import com.mobile.standalone.dto.ApiTestDtos.RunResult;
import com.mobile.standalone.entity.ApiTestSchedule;
import com.mobile.standalone.mapper.ApiTestScheduleMapper;
import com.mobile.standalone.service.ApiTestRunnerService;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 定时执行接口用例。
 * <p>
 * 一分钟一跳，把到点的任务捞出来跑。没用 Spring 动态注册 {@code TaskScheduler}：
 * 那样每次增删改都要同步内存里的任务表，多一份状态就多一处能不一致的地方；
 * 而这里所有状态都在库里，重启即恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTestScheduleService {

    private final ApiTestScheduleMapper mapper;
    private final ApiTestRunnerService runnerService;
    private final JdbcTemplate jdbcTemplate;
    private final NodeIdentity nodeIdentity;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_test_schedules (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        cron VARCHAR(64) NOT NULL,
                        collection_id VARCHAR(64) NULL,
                        project VARCHAR(64) NULL,
                        environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
                        enabled TINYINT(1) NOT NULL DEFAULT 1,
                        next_run_at DATETIME NULL,
                        last_run_at DATETIME NULL,
                        last_run_id VARCHAR(64) NULL,
                        last_status VARCHAR(16) NULL,
                        last_message VARCHAR(512) NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_due (enabled, next_run_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[api-test-schedule] 表已就绪");
            seedDebugSmokeScheduleIfAbsent();
        } catch (Exception e) {
            log.warn("[api-test-schedule] 建表失败: {}", e.getMessage());
        }
    }

    /** debug-smoke 用例定时冒烟（默认关闭；跑 0825 分支后端时再启用） */
    private void seedDebugSmokeScheduleIfAbsent() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM api_test_schedules WHERE name = ?",
                    Integer.class, "debug-smoke 每日冒烟");
            if (n != null && n > 0) {
                return;
            }
            ApiTestSchedule s = new ApiTestSchedule();
            s.setId("atsch-debug-smoke");
            s.setName("debug-smoke 每日冒烟");
            s.setCron("0 0 9 * * ?");
            s.setProject("debug-smoke");
            s.setEnvironment("TEST");
            s.setEnabled(false);
            s.setCollectionId(null);
            LocalDateTime now = LocalDateTime.now();
            s.setNextRunAt(nextAfter(s.getCron(), now));
            s.setCreatedAt(now);
            s.setUpdatedAt(now);
            mapper.insert(s);
            log.info("[api-test-schedule] 已种子 debug-smoke 定时任务（默认关闭）");
        } catch (Exception e) {
            log.debug("[api-test-schedule] debug-smoke 定时种子跳过: {}", e.getMessage());
        }
    }

    public List<ApiTestSchedule> list() {
        return mapper.findAll();
    }

    /**
     * 校验 cron 并算出下次触发时间。
     * <p>
     * 在保存时就算，而不是到点再算：cron 写错的话，此刻就能告诉人「这个表达式不合法」，
     * 而不是安安静静存下来、然后永远不触发——那种「配了但没跑」最难被发现。
     */
    static LocalDateTime nextAfter(String cron, LocalDateTime from) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空");
        }
        CronExpression expr;
        try {
            expr = CronExpression.parse(cron.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "cron 表达式不合法: " + cron + "。用 6 段格式（秒 分 时 日 月 周），"
                            + "如 0 0 8 * * ? 表示每天 8 点");
        }
        LocalDateTime next = expr.next(from);
        if (next == null) {
            throw new IllegalArgumentException("cron 表达式永远不会触发: " + cron);
        }
        return next;
    }

    public ApiTestSchedule save(ApiTestSchedule body) {
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("名称不能为空");
        }
        body.setName(body.getName().trim());
        body.setEnvironment(TestEnvironment.normalize(body.getEnvironment()));
        if (body.getEnabled() == null) {
            body.setEnabled(true);
        }
        if (body.getCollectionId() != null && body.getCollectionId().isBlank()) {
            body.setCollectionId(null);
        }
        LocalDateTime now = LocalDateTime.now();
        body.setNextRunAt(nextAfter(body.getCron(), now));
        body.setUpdatedAt(now);

        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("atsch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            body.setCreatedAt(now);
            mapper.insert(body);
        } else {
            mapper.update(body);
        }
        return mapper.findById(body.getId());
    }

    public boolean delete(String id) {
        return mapper.deleteById(id) > 0;
    }

    public ApiTestSchedule setEnabled(String id, boolean enabled) {
        ApiTestSchedule s = mapper.findById(id);
        if (s == null) {
            throw new IllegalArgumentException("定时任务不存在: " + id);
        }
        s.setEnabled(enabled);
        return save(s);
    }

    /** 立即跑一次，不影响 cron 的下次触发时间 */
    public RunResult runNow(String id) {
        ApiTestSchedule s = mapper.findById(id);
        if (s == null) {
            throw new IllegalArgumentException("定时任务不存在: " + id);
        }
        return execute(s, false);
    }

    // ---------- 调度 ----------

    /**
     * 一分钟一跳。
     * <p>
     * 精度到分钟就够了：接口回归是「每天早上跑一轮」这个量级的事，
     * 为了秒级精度去做动态注册，多出来的复杂度换不回任何东西。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void tick() {
        // 执行节点连的是同一份库，不能再扫一遍，否则同一条任务会跑两次
        if (NodeIdentity.WORKER.equals(nodeIdentity.role())) {
            return;
        }
        List<ApiTestSchedule> due;
        try {
            due = mapper.findDue(LocalDateTime.now());
        } catch (Exception e) {
            log.debug("[api-test-schedule] 查询待执行失败（表可能还没建好）: {}", e.getMessage());
            return;
        }
        for (ApiTestSchedule s : due) {
            try {
                execute(s, true);
            } catch (Exception e) {
                log.warn("[api-test-schedule] 执行 {} 失败: {}", s.getName(), e.getMessage());
            }
        }
    }

    /**
     * @param advance 是否推进 next_run_at。手动「立即执行」不推进，否则会把当天的定时跑给顶掉
     */
    private RunResult execute(ApiTestSchedule s, boolean advance) {
        LocalDateTime now = LocalDateTime.now();
        RunResult result = null;
        String status;
        String message;
        try {
            RunRequest req = new RunRequest();
            req.setEnvironment(s.getEnvironment());
            // 定时任务本身就是那次确认——人是特意建了这条任务的。
            // 再要一次交互式确认，等于这个功能没法用
            req.setConfirmProd(true);
            if (s.getCollectionId() != null) {
                req.setCollectionId(s.getCollectionId());
            } else {
                req.setCaseIds(allEnabledCaseIds(s.getProject()));
            }
            result = runnerService.run(req);
            status = result.isOk() ? "PASSED" : "FAILED";
            message = result.getMessage();
        } catch (Exception e) {
            // 跑挂了要留痕。静默失败的定时任务比没有定时任务更糟：
            // 人以为它在跑，实际上已经连着几周什么都没做
            status = "ERROR";
            message = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("[api-test-schedule] {} 执行异常: {}", s.getName(), message);
        }

        ApiTestSchedule patch = new ApiTestSchedule();
        patch.setId(s.getId());
        patch.setLastRunAt(now);
        patch.setLastRunId(result == null ? null : result.getRunId());
        patch.setLastStatus(status);
        patch.setLastMessage(message == null ? null : message.substring(0, Math.min(message.length(), 512)));
        // 即使这次挂了也要推进下次时间，否则会卡在同一个时间点每分钟重试一次
        patch.setNextRunAt(advance ? safeNext(s.getCron(), now) : s.getNextRunAt());
        mapper.updateAfterRun(patch);
        if (result == null) {
            result = new RunResult();
            result.setOk(false);
            result.setMessage(message);
        }
        return result;
    }

    /** cron 被人改坏时不能让整个 tick 抛出去——那会连带别的任务一起不跑 */
    private LocalDateTime safeNext(String cron, LocalDateTime from) {
        try {
            return nextAfter(cron, from);
        } catch (Exception e) {
            log.warn("[api-test-schedule] cron 无效，该任务将不再触发: {}", cron);
            return null;
        }
    }

    private List<String> allEnabledCaseIds(String project) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM api_test_cases WHERE enabled = 1"
                        + (project == null || project.isBlank() ? "" : " AND project = ?")
                        + " ORDER BY sort_order ASC, name ASC",
                String.class,
                project == null || project.isBlank() ? new Object[0] : new Object[]{project});
    }
}
