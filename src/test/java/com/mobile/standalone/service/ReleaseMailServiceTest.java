package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import com.mobile.standalone.dto.ReleaseDocDtos.MailDraftRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.SendMailRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReleaseMailServiceTest {

    private ReleaseMailService svcWithWiki(List<IssueRow> wikiRows) {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.getMail().setDefaultTo("yangjunjun@pwrd.com");
        ReleaseDocService doc = mock(ReleaseDocService.class);
        when(doc.loadIssueRowsFromWikiUrl(anyString())).thenReturn(wikiRows);
        return new ReleaseMailService(props, mock(JavaMailSender.class), doc);
    }

    /** Wiki 不可用时回退前端 rows（兼容旧单测路径） */
    private ReleaseMailService svc() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.getMail().setDefaultTo("yangjunjun@pwrd.com");
        ReleaseDocService doc = mock(ReleaseDocService.class);
        when(doc.loadIssueRowsFromWikiUrl(anyString()))
                .thenThrow(new IllegalStateException("wiki down"));
        return new ReleaseMailService(props, mock(JavaMailSender.class), doc);
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
    void draftPreferWikiOverStaleFrontendRows() {
        IssueRow stale = new IssueRow();
        stale.setSummary("旧任务");
        stale.setDbChanges("无");
        stale.setBranches("无");
        stale.setConfigChanges("无");
        stale.setRemarks("无");

        IssueRow fresh = new IssueRow();
        fresh.setSummary("Wiki最新任务");
        fresh.setDbChanges("steamcn_comment库：\nALTER TABLE t ADD c INT;");
        fresh.setBranches("app-admin: agent/x");
        fresh.setConfigChanges("无");
        fresh.setRemarks("无");

        MailDraftRequest req = new MailDraftRequest();
        req.setTitle("10月8日 测试版本");
        req.setWikiPageUrl("https://wiki.shdev.net/pages/viewpage.action?pageId=99");
        req.setRows(List.of(stale));

        var draft = svcWithWiki(List.of(fresh)).draft(req);
        assertTrue(draft.getHtmlBody().contains("Wiki最新任务"), draft.getHtmlBody());
        assertFalse(draft.getHtmlBody().contains("旧任务"), draft.getHtmlBody());
        assertTrue(draft.getMessage().contains("Wiki"), draft.getMessage());
    }

    @Test
    void draftGroupsTablesBySectionWhenWikiHasSeveral() {
        IssueRow client = new IssueRow();
        client.setSummary("侧边栏UI升级");
        client.setDbChanges("无");
        client.setSection("10月15日 电竞4.1.9客户端版本");
        IssueRow harmony = new IssueRow();
        harmony.setSummary("【鸿蒙】【V224】完美时刻v4");
        harmony.setDbChanges("无");
        harmony.setSection("10月15日 鸿蒙224单子");

        MailDraftRequest req = new MailDraftRequest();
        req.setTitle("10月15日 电竞4.1.9客户端版本");
        req.setWikiPageUrl("https://wiki.shdev.net/pages/viewpage.action?pageId=119732327");

        String html = svcWithWiki(List.of(client, harmony)).draft(req).getHtmlBody();
        assertTrue(html.contains("<b>10月15日 鸿蒙224单子</b>"), html);
        assertTrue(html.indexOf("侧边栏UI升级") < html.indexOf("鸿蒙224单子"), html);
        assertTrue(html.indexOf("鸿蒙224单子") < html.indexOf("完美时刻v4"), html);
        assertEquals(2, html.split("<table").length - 1, html);
    }

    @Test
    void draftSingleTableHasNoSectionHeading() {
        IssueRow only = new IssueRow();
        only.setSummary("任务C");
        only.setDbChanges("无");
        only.setSection("10月15日 电竞4.1.9客户端版本");

        MailDraftRequest req = new MailDraftRequest();
        req.setTitle("10月15日 电竞4.1.9客户端版本");
        req.setWikiPageUrl("https://wiki.shdev.net/pages/viewpage.action?pageId=1");

        String html = svcWithWiki(List.of(only)).draft(req).getHtmlBody();
        assertFalse(html.contains("<b>10月15日 电竞4.1.9客户端版本</b>"), html);
        assertEquals(1, html.split("<table").length - 1, html);
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

    @Test
    void sendWithoutWikiFallsBackToHtmlBody() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.getMail().setUsername("u");
        props.getMail().setPassword("p");
        props.getMail().setFrom("from@pwrd.com");
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));

        ReleaseMailService mail = new ReleaseMailService(props, sender, mock(ReleaseDocService.class));
        SendMailRequest req = new SendMailRequest();
        req.setTo("to@pwrd.com");
        req.setSubject("主题");
        req.setHtmlBody("<p>手改正文</p>");

        var r = mail.send(req);
        assertTrue(r.isOk());
        assertEquals("<p>手改正文</p>", r.getHtmlBody());
        assertTrue(r.getMessage().contains("已发送"));
        verify(sender).send(any(jakarta.mail.internet.MimeMessage.class));
    }
}
