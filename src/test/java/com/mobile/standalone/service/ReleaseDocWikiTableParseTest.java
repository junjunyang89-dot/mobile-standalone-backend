package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 发版邮件 / 上线看板从人工改过的 Wiki 页读表格 */
class ReleaseDocWikiTableParseTest {

    private static final String HEAD =
            "<tr><th>任务</th><th>上线分支</th><th>DB变更</th><th>配置变更</th><th>QA</th><th>备注</th></tr>";

    private static String row(String task, String branch, String qa) {
        return "<tr><td>" + task + "</td><td>" + branch + "</td><td>无</td><td>无</td><td>" + qa + "</td><td>无</td></tr>";
    }

    private ReleaseDocService svc() {
        return new ReleaseDocService(new ReleaseDocProperties());
    }

    @Test
    void readsEveryReleaseTableWithItsCaption() {
        String html = "<p>10月15日 电竞4.1.9客户端版本</p>"
                + "<table><tbody>" + HEAD
                + row("<a href=\"https://jira.shdev.net/browse/APP-1\">侧边栏UI升级</a>", "app-ios: dev", "秦熙贵")
                + "</tbody></table>"
                + "<p>10月15日 鸿蒙224单子</p>"
                + "<table><tbody>" + HEAD
                + row("<a href=\"https://jira.shdev.net/browse/APP-2\">【鸿蒙】【V224】CS组队开黑功能</a>", "dev224", "张全超")
                + row("<a href=\"https://jira.shdev.net/browse/APP-3\">【鸿蒙】【V224】完美时刻v4</a>", "dev224", "张全超")
                + "</tbody></table>";

        List<IssueRow> rows = svc().parseReleaseTable(html);

        assertEquals(3, rows.size());
        assertEquals("10月15日 电竞4.1.9客户端版本", rows.get(0).getSection());
        assertEquals("10月15日 鸿蒙224单子", rows.get(1).getSection());
        assertEquals("10月15日 鸿蒙224单子", rows.get(2).getSection());
        assertEquals("dev224", rows.get(2).getBranches());
    }

    @Test
    void manuallyAddedRowKeepsDescriptionWhenOnlyKeyIsLinked() {
        String html = "<table><tbody>" + HEAD
                + row("用户token日志处理-<a href=\"https://jira.shdev.net/browse/APP-9270\">APP-9270</a>",
                "activity-center", "杨俊俊")
                + "</tbody></table>";

        IssueRow r = svc().parseReleaseTable(html).get(0);

        assertEquals("用户token日志处理-APP-9270", r.getSummary());
        assertEquals("APP-9270", r.getIssueKey());
        assertEquals("https://jira.shdev.net/browse/APP-9270", r.getIssueUrl());
    }

    @Test
    void ignoresNonReleaseTables() {
        String html = "<table><tr><th>说明</th><th>值</th></tr><tr><td>a</td><td>b</td></tr></table>"
                + "<table><tbody>" + HEAD + row("任务A", "无", "张三") + "</tbody></table>";

        List<IssueRow> rows = svc().parseReleaseTable(html);

        assertEquals(1, rows.size());
        assertEquals("任务A", rows.get(0).getSummary());
        assertEquals("", rows.get(0).getSection());
    }
}
