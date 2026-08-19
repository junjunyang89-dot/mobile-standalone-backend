package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发版文档「任务」列去掉人名/提测/上线后缀。
 * 样例对照 pageId=119719953（7月27日 PogPog1.2.9.40）以及清理指南。
 */
class ReleaseDocTaskTextCleanupTest {

    @Test
    void stripsGuideExamples() {
        assertEquals(
                "steam游戏数据验收反馈 - 5. 游戏评分帖先不要推到社区广场列表",
                ReleaseDocService.cleanTaskText(
                        "steam游戏数据验收反馈 - 5. 游戏评分帖先不要推到社区广场列表 - 成伟 - 8.18提测，8.19上线"));
        assertEquals(
                "8月优化 - 9. 机器人管理-新增确认结果并更新功能",
                ReleaseDocService.cleanTaskText(
                        "8月优化 - 9. 机器人管理-新增确认结果并更新功能 - 翔翔/曹键 - 8.14提测，8.19上线"));
        assertEquals(
                "【国内】steam战力排行榜功能 - steam游戏卡片弹窗后续开放",
                ReleaseDocService.cleanTaskText(
                        "【国内】steam战力排行榜功能 - steam游戏卡片弹窗后续开放 - 君楠/文欢 - 8.19上线"));
        assertEquals(
                "【鸿蒙】滚动问题修复",
                ReleaseDocService.cleanTaskText("【鸿蒙】滚动问题修复 - 曹键，不跟版本"));
        assertEquals(
                "前缀",
                ReleaseDocService.cleanTaskText(
                        "前缀 - 才华/赵琦/靖怡/曹键/业斌/中华 - 8.13提测，8.19上线，8.27开放入口"));
    }

    @Test
    void stripsPage119719953Examples() {
        assertEquals(
                "【Pog】Deadlock战绩1.5 - 依赖demo解析部分",
                ReleaseDocService.cleanTaskText(
                        "【Pog】Deadlock战绩1.5 - 依赖demo解析部分 才华/新纪/靖怡，暂定7.23提测，7.27上线"));
        assertEquals(
                "【Pog】Deadlock 7.16优化 - 5.周报数据计算优化",
                ReleaseDocService.cleanTaskText(
                        "【Pog】Deadlock 7.16优化 - 5.周报数据计算优化 - 赵琦 - 7.21提测，不跟版本"));
        assertEquals(
                "【Pog】DL周报截图分享时偶发英雄图片加载不全的问题修复",
                ReleaseDocService.cleanTaskText(
                        "【Pog】DL周报截图分享时偶发英雄图片加载不全的问题修复 - 中华 - 7.22提测，7.27上线"));
        assertEquals(
                "【Pog】Deadlock物品 - 增加upgrade信息",
                ReleaseDocService.cleanTaskText(
                        "【Pog】Deadlock物品 - 增加upgrade信息 - 靖怡 - 7.20提测，7.27上线"));
        assertEquals(
                "【Pog】Deadlock 7.16优化 - 2.图标显示偏小问题修复",
                ReleaseDocService.cleanTaskText(
                        "【Pog】Deadlock 7.16优化 - 2.图标显示偏小问题修复 - 靖怡 - 7.20提测，7.27上线"));
    }

    @Test
    void doesNotStripTaskBody() {
        String body = "8月优化 - 9. 机器人管理-新增确认结果并更新功能";
        assertEquals(body, ReleaseDocService.cleanTaskText(body));
        String longer = "xxx-二次确认交互更新功能";
        assertEquals(longer, ReleaseDocService.cleanTaskText(longer));
    }

    @Test
    void keepsJiraHrefWhenBuildingWikiHtml() {
        IssueRow row = new IssueRow();
        row.setIssueUrl("https://jira.shdev.net/browse/APP-8991");
        row.setSummary("【Pog】Deadlock物品 - 增加upgrade信息 - 靖怡 - 7.20提测，7.27上线");
        String html = new ReleaseDocService(new ReleaseDocProperties()).buildStorageHtml("t", List.of(row));
        assertTrue(html.contains("href=\"https://jira.shdev.net/browse/APP-8991\""));
        assertTrue(html.contains("【Pog】Deadlock物品 - 增加upgrade信息"));
        assertFalse(html.contains("靖怡"));
        assertFalse(html.contains("7.20提测"));
    }
}
