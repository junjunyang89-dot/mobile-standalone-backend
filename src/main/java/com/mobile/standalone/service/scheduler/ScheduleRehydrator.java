package com.mobile.standalone.service.scheduler;

import com.mobile.standalone.entity.TestPlan;
import com.mobile.standalone.service.TestPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把库里启用的定时计划重新注册进 Quartz。
 *
 * <p><b>不加这个会怎样</b>：Quartz 默认用 RAMJobStore（进程内存），而
 * {@code schedulePlan} 只在「建计划」和「改计划」时被调用过。于是每次重启之后：
 * <ul>
 *   <li>{@code test_plans} 表里计划还是 {@code enabled = true}</li>
 *   <li>界面上照样显示 cron 和「下次执行时间」——因为那个时间是建计划时算好写进库的</li>
 *   <li>但 Quartz 里<b>一个 trigger 都没有</b>，定时回归再也不会跑</li>
 * </ul>
 * 这是最难发现的一类失效：<b>没有任何报错，界面看起来完全正常</b>，
 * 而「每周都有版本迭代」意味着重启很频繁。等有人想起来问「上周的定时回归结果呢」，
 * 已经空跑了很多天。
 *
 * <p>放在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 * 后者在 Bean 初始化阶段就跑，那时 Quartz 的 Scheduler 可能还没启动，
 * 注册会抛 {@code SchedulerException} 或者注册了却不生效。
 *
 * <p><b>一条计划失败不能连累其它计划</b>：cron 表达式是人填的，库里存着一条非法的
 * 完全可能。逐条 try/catch，坏的那条记下来，其余照常注册——
 * 否则一条脏数据会让全部定时任务都不跑，而症状和这个 bug 本身一模一样。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRehydrator {

    private final TestPlanService testPlanService;
    private final QuartzSchedulerService quartzSchedulerService;

    @EventListener(ApplicationReadyEvent.class)
    public void rehydrate() {
        List<TestPlan> plans;
        try {
            plans = testPlanService.getEnabledPlans();
        } catch (Exception e) {
            // 查不到就明确报出来。静默跳过的话，症状和「压根没这段代码」完全一样
            log.error("[schedule] 启动重注册失败：读不到启用的测试计划，"
                    + "本次启动后定时计划不会执行，请检查数据库", e);
            return;
        }
        if (plans == null || plans.isEmpty()) {
            log.info("[schedule] 没有启用的测试计划，无需重注册");
            return;
        }

        int ok = 0;
        int failed = 0;
        for (TestPlan plan : plans) {
            try {
                quartzSchedulerService.schedulePlan(plan);
                ok++;
            } catch (Exception e) {
                failed++;
                log.error("[schedule] 计划重注册失败，这一条不会执行: id={} name={} cron={} 原因={}",
                        plan.getId(), plan.getName(), plan.getCronExpression(), e.getMessage());
            }
        }
        if (failed > 0) {
            // 用 warn 而不是 info：有计划没注册上，是需要人去看一眼的状态
            log.warn("[schedule] 启动重注册完成：成功 {} 条，失败 {} 条（失败的计划本次不会执行）", ok, failed);
        } else {
            log.info("[schedule] 启动重注册完成：{} 条定时计划已恢复", ok);
        }
    }
}
