package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cron 解析与下次触发时间。
 * <p>
 * 重点是<b>写错的表达式必须在保存时就报出来</b>。存下来再永远不触发，是「配了但没跑」——
 * 这类故障没有任何现象，人以为它每天在跑，实际上从来没跑过，
 * 等到出事时才发现，那份「一直没报警」的安心感反而误导了判断。
 */
class ApiTestScheduleCronTest {

    private static final LocalDateTime MON_10AM = LocalDateTime.of(2026, 8, 10, 10, 0, 0);

    @Test
    void 每天八点() {
        assertEquals(LocalDateTime.of(2026, 8, 11, 8, 0, 0),
                ApiTestScheduleService.nextAfter("0 0 8 * * ?", MON_10AM),
                "当天 8 点已经过了，下次该是明天");
    }

    @Test
    void 每半小时() {
        assertEquals(LocalDateTime.of(2026, 8, 10, 10, 30, 0),
                ApiTestScheduleService.nextAfter("0 0/30 * * * ?", MON_10AM));
    }

    @Test
    void 工作日夜里() {
        LocalDateTime fri = LocalDateTime.of(2026, 8, 14, 23, 30, 0);

        LocalDateTime next = ApiTestScheduleService.nextAfter("0 0 23 ? * MON-FRI", fri);

        assertEquals(LocalDateTime.of(2026, 8, 17, 23, 0, 0), next, "周五 23:30 之后应跳到下周一");
    }

    @Test
    void 表达式不合法时报出怎么写() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ApiTestScheduleService.nextAfter("每天八点", MON_10AM));

        assertTrue(e.getMessage().contains("不合法"), e.getMessage());
        assertTrue(e.getMessage().contains("0 0 8 * * ?"), "光说不合法没用，要给个能照抄的例子：" + e.getMessage());
    }

    /** Spring 是 6 段，Linux crontab 是 5 段，很容易照着 crontab 抄错 */
    @Test
    void 五段的crontab写法要报错() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiTestScheduleService.nextAfter("0 8 * * *", MON_10AM));
    }

    @Test
    void 空表达式报错() {
        assertThrows(IllegalArgumentException.class, () -> ApiTestScheduleService.nextAfter(null, MON_10AM));
        assertThrows(IllegalArgumentException.class, () -> ApiTestScheduleService.nextAfter("  ", MON_10AM));
    }

    /** 2 月 30 号这种永远不会到的日期，别静默存下来 */
    @Test
    void 永远不触发的表达式要报错() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiTestScheduleService.nextAfter("0 0 0 30 2 ?", MON_10AM));
    }
}
