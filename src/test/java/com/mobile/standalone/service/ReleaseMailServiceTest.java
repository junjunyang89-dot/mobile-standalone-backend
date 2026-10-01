package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import com.mobile.standalone.dto.ReleaseDocDtos.MailDraftRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReleaseMailServiceTest {

    private ReleaseMailService svc() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.getMail().setDefaultTo("yangjunjun@pwrd.com");
        return new ReleaseMailService(props, mock(JavaMailSender.class), mock(ReleaseDocService.class));
    }

    @Test
    void datePhraseToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String title = today.getMonthValue() + "月" + today.getDayOfMonth() + "日 PogPog 版本";
        String phrase = svc().buildDatePhrase(title);
        assertTrue(phrase.contains("今天"), phrase);
    }

    @Test
    void draftIncludesDbSectionWhenPresent() {
        IssueRow row = new IssueRow();
        row.setSummary("任务A");
        row.setIssueUrl("https://jira.shdev.net/browse/APP-1");
        row.setBranches("无");
        row.setDbChanges("app_admin库下执行：\nCREATE TABLE t (id INT);");
        row.setConfigChanges("无");
        row.setQaAssignee("张三");
        row.setRemarks("无");

        MailDraftRequest req = new MailDraftRequest();
        req.setTitle("7月20日 PogPog H5&服务端版本");
        req.setWikiPageUrl("https://wiki.shdev.net/pages/viewpage.action?pageId=119718840");
        req.setRows(List.of(row));

        var draft = svc().draft(req);
        assertTrue(draft.isHasDb());
        assertTrue(draft.getHtmlBody().contains("Hi all"));
        assertTrue(draft.getHtmlBody().contains("PogPog H5&amp;服务端版本于"));
        assertFalse(draft.getHtmlBody().contains(">7月20日 PogPog"));
        assertTrue(draft.getHtmlBody().contains("相关发布及DB变更内容已准备就绪"));
        assertTrue(draft.getHtmlBody().contains("二、DB变更执行（@何政）"));
        assertTrue(draft.getHtmlBody().contains("以下为本版本全部DB变更汇总（与上文各需求项 SQL 一致，便于一次性执行），"));
        assertTrue(draft.getHtmlBody().contains("麻烦何政于7月19日（发版前一日）执行完毕"));
        assertTrue(draft.getHtmlBody().contains("CREATE TABLE t"));
        assertTrue(draft.getHtmlBody().contains("谢谢"));
        assertTrue(draft.getHtmlBody().contains("杨俊俊"));
        assertFalse(draft.getHtmlBody().contains("完结"));
        assertFalse(draft.getHtmlBody().contains("祝各位"));
        assertEquals("yangjunjun@pwrd.com", draft.getDefaultTo());
        assertEquals("【发版通知】7月20日 PogPog H5&服务端版本", draft.getSubject());
    }

    @Test
    void sqlDeadlineIsDayBeforeRelease() {
        assertEquals("麻烦何政于9月16日（发版前一日）执行完毕",
                svc().buildSqlDeadlinePhrase("9月17日 PogPog H5&服务端版本"));
        assertEquals("麻烦何政于发版前一日执行完毕",
                svc().buildSqlDeadlinePhrase("无日期标题"));
    }

    @Test
    void stripLeadingDate() {
        assertEquals("PogPog H5&服务端版本", ReleaseMailService.stripLeadingTitleDate("7月20日 PogPog H5&服务端版本"));
        assertEquals("国内客户端版本", ReleaseMailService.stripLeadingTitleDate("国内客户端版本"));
    }

    @Test
    void draftOmitsDbSectionWhenNone() {
        IssueRow row = new IssueRow();
        row.setSummary("任务B");
        row.setDbChanges("无");
        row.setBranches("无");
        row.setConfigChanges("无");
        row.setRemarks("无");

        MailDraftRequest req = new MailDraftRequest();
        req.setTitle("7月20日 测试版本");
        req.setWikiPageUrl("https://wiki.shdev.net/pages/viewpage.action?pageId=1");
        req.setRows(List.of(row));

        var draft = svc().draft(req);
        assertFalse(draft.isHasDb());
        assertTrue(draft.getHtmlBody().contains("相关发布内容已准备就绪"));
        assertFalse(draft.getHtmlBody().contains("二、DB变更执行"));
    }
}
