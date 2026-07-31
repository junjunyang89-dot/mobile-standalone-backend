package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import com.mobile.standalone.dto.ReleaseDocDtos.MailDraftRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.MailDraftResult;
import com.mobile.standalone.dto.ReleaseDocDtos.SendMailRequest;
import com.mobile.standalone.dto.ReleaseDocDtos.SendMailResult;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发版邮件：按模版组草稿，经 Exchange SMTP 发送。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReleaseMailService {

    private static final Pattern TITLE_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");
    private static final String ISSUE_LIST_URL =
            "https://wiki.shdev.net/pages/viewpage.action?pageId=68892294";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReleaseDocProperties props;
    private final JavaMailSender mailSender;
    private final ReleaseDocService releaseDocService;

    public MailDraftResult draft(MailDraftRequest req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        String title = trim(req.getTitle());
        if (title.isEmpty()) throw new IllegalArgumentException("请填写发版文档标题");
        String wikiUrl = trim(req.getWikiPageUrl());
        if (wikiUrl.isEmpty()) throw new IllegalArgumentException("请填写新建 Wiki 页面地址");

        List<IssueRow> rows = req.getRows() != null ? new ArrayList<>(req.getRows()) : new ArrayList<>();
        if (rows.isEmpty()) {
            log.info("[release-mail] 前端未带表格，从 Wiki 拉取: {}", wikiUrl);
            rows.addAll(releaseDocService.loadIssueRowsFromWikiUrl(wikiUrl));
        }

        boolean hasDb = hasDbChanges(rows);
        String datePhrase = buildDatePhrase(title);
        String readyPhrase = hasDb
                ? "相关发布及DB变更内容已准备就绪"
                : "相关发布内容已准备就绪";

        String html = buildHtmlBody(title, datePhrase, readyPhrase, wikiUrl, rows, hasDb);

        MailDraftResult out = new MailDraftResult();
        out.setSubject("【发版通知】" + title);
        out.setHtmlBody(html);
        out.setHasDb(hasDb);
        out.setDatePhrase(datePhrase);
        out.setReadyPhrase(readyPhrase);
        out.setWikiPageUrl(wikiUrl);
        String defaultTo = props.getMail() != null ? trim(props.getMail().getDefaultTo()) : "";
        if (defaultTo.isEmpty() && props.getMail() != null) {
            defaultTo = trim(props.getMail().getFrom());
        }
        out.setDefaultTo(defaultTo.isEmpty() ? "yangjunjun@pwrd.com" : defaultTo);
        out.setMessage("草稿已生成（" + rows.size() + " 条），可编辑后发送");
        return out;
    }

    public SendMailResult send(SendMailRequest req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        String to = trim(req.getTo());
        String subject = trim(req.getSubject());
        String html = req.getHtmlBody() != null ? req.getHtmlBody() : "";
        if (to.isEmpty()) throw new IllegalArgumentException("请填写收件人");
        if (subject.isEmpty()) throw new IllegalArgumentException("请填写邮件主题");
        if (html.isBlank()) throw new IllegalArgumentException("邮件正文为空");

        ReleaseDocProperties.Mail mail = props.getMail();
        if (mail == null || trim(mail.getUsername()).isEmpty() || trim(mail.getPassword()).isEmpty()) {
            throw new IllegalStateException("未配置发版邮件账号，请在 application.yml 的 release-doc.mail 填写后重启");
        }
        String from = trim(mail.getFrom());
        if (from.isEmpty()) from = trim(mail.getUsername());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(new InternetAddress(from));
            helper.setTo(InternetAddress.parse(to, false));
            String cc = trim(req.getCc());
            if (!cc.isEmpty()) {
                helper.setCc(InternetAddress.parse(cc, false));
            }
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[release-mail] 已发送 to={} subject={}", to, subject);
            SendMailResult r = new SendMailResult();
            r.setOk(true);
            r.setMessage("邮件已发送至 " + to);
            return r;
        } catch (Exception e) {
            log.error("[release-mail] 发送失败: {}", e.getMessage(), e);
            throw new IllegalStateException("邮件发送失败: " + e.getMessage()
                    + "（可检查 release-doc.mail.host/port，或改用 ssl=true + 465）", e);
        }
    }

    String buildDatePhrase(String title) {
        Matcher m = TITLE_DATE.matcher(title);
        if (!m.find()) {
            return "近期";
        }
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        LocalDate today = LocalDate.now(ZONE);
        int year = today.getYear();
        LocalDate target;
        try {
            target = LocalDate.of(year, month, day);
        } catch (Exception e) {
            return month + "月" + day + "日";
        }
        // 标题跨年：若日期已过很久且距今超过半年，视为下一年
        if (target.isBefore(today.minusMonths(6))) {
            target = LocalDate.of(year + 1, month, day);
        }
        String base = month + "月" + day + "日";
        if (target.equals(today)) return base + "（今天）";
        if (target.equals(today.plusDays(1))) return base + "（明天）";
        return base;
    }

    /** 正文产品名：去掉标题开头的「M月D日」，避免与「于M月D日更新」重复 */
    static String stripLeadingTitleDate(String title) {
        if (title == null || title.isBlank()) return "";
        String t = title.trim().replaceFirst("^\\d{1,2}月\\d{1,2}日\\s*", "");
        return t.isBlank() ? title.trim() : t;
    }

    static boolean hasDbChanges(List<IssueRow> rows) {
        if (rows == null) return false;
        for (IssueRow r : rows) {
            if (isMeaningfulDb(r.getDbChanges())) return true;
        }
        return false;
    }

    private static boolean isMeaningfulDb(String db) {
        if (db == null) return false;
        String t = db.trim();
        if (t.isEmpty() || "无".equals(t) || "-".equals(t) || "/".equals(t)) return false;
        return true;
    }

    String buildHtmlBody(String title, String datePhrase, String readyPhrase,
                         String wikiUrl, List<IssueRow> rows, boolean hasDb) {
        // Outlook 常压缩默认 p margin，用显式间距
        final String p0 = "margin:0;padding:0;";
        final String gap = "<p style=\"margin:0;padding:0;line-height:16px;height:16px;font-size:16px;\">&nbsp;</p>";

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:Microsoft YaHei,SimSun,sans-serif;font-size:14px;line-height:1.6;color:#000;\">");
        sb.append("<p style=\"").append(p0).append("\">Hi all</p>");
        sb.append(gap);
        sb.append("<p style=\"").append(p0).append("\">")
                .append(esc(stripLeadingTitleDate(title)))
                .append("于")
                .append(esc(datePhrase))
                .append("更新，")
                .append(esc(readyPhrase))
                .append("，请知悉。</p>");
        sb.append(gap);

        sb.append("<p style=\"").append(p0).append("\"><b>一、上线需求及清单</b></p>");
        sb.append(gap);
        sb.append("<p style=\"").append(p0).append("\">发版需求列表：")
                .append("<a href=\"").append(escAttr(ISSUE_LIST_URL)).append("\">")
                .append(esc(ISSUE_LIST_URL)).append("</a></p>");
        sb.append("<p style=\"margin:4px 0 0 0;padding:0;\">发版内容清单：")
                .append("<a href=\"").append(escAttr(wikiUrl)).append("\">")
                .append(esc(wikiUrl)).append("</a></p>");
        sb.append(gap);

        sb.append(buildTableHtml(rows));

        if (hasDb) {
            sb.append(gap);
            sb.append("<p style=\"").append(p0).append("\"><b>二、DB变更执行（@何政）</b></p>");
            sb.append("<p style=\"margin:8px 0 8px 0;padding:0;\">以下为本版本全部DB变更汇总（与上文中各需求项中 SQL 一致，便于一次性执行）：</p>");
            for (IssueRow r : rows) {
                if (!isMeaningfulDb(r.getDbChanges())) continue;
                sb.append("<pre style=\"margin:0 0 12px 0;white-space:pre-wrap;font-family:Consolas,monospace;font-size:12px;")
                        .append("background:#f5f5f5;padding:8px;border:1px solid #ddd;\">")
                        .append(esc(r.getDbChanges().trim()))
                        .append("</pre>");
            }
        }

        sb.append(gap);
        sb.append("<p style=\"").append(p0).append("\">谢谢</p>");
        sb.append("<p style=\"margin:4px 0 0 0;padding:0;\">杨俊俊</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private String buildTableHtml(List<IssueRow> rows) {
        String th = "border:1px solid #999;padding:6px 8px;background:#f0f0f0;text-align:left;";
        String td = "border:1px solid #999;padding:6px 8px;vertical-align:top;";
        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;max-width:960px;\">");
        sb.append("<thead><tr>")
                .append("<th style=\"").append(th).append("\">任务</th>")
                .append("<th style=\"").append(th).append("\">上线分支</th>")
                .append("<th style=\"").append(th).append("\">DB变更</th>")
                .append("<th style=\"").append(th).append("\">配置变更</th>")
                .append("<th style=\"").append(th).append("\">QA</th>")
                .append("<th style=\"").append(th).append("\">备注</th>")
                .append("</tr></thead><tbody>");
        for (IssueRow r : rows) {
            sb.append("<tr>");
            sb.append("<td style=\"").append(td).append("\">");
            if (r.getIssueUrl() != null && !r.getIssueUrl().isBlank()) {
                sb.append("<a href=\"").append(escAttr(r.getIssueUrl())).append("\">")
                        .append(esc(nullToEmpty(r.getSummary()))).append("</a>");
            } else {
                sb.append(esc(nullToEmpty(r.getSummary())));
            }
            sb.append("</td>");
            sb.append("<td style=\"").append(td).append("\">").append(nlToBr(r.getBranches())).append("</td>");
            sb.append("<td style=\"").append(td).append("\">").append(dbCell(r.getDbChanges())).append("</td>");
            sb.append("<td style=\"").append(td).append("\">").append(nlToBr(r.getConfigChanges())).append("</td>");
            sb.append("<td style=\"").append(td).append("\">").append(esc(nullToEmpty(r.getQaAssignee()))).append("</td>");
            sb.append("<td style=\"").append(td).append("\">").append(nlToBr(r.getRemarks())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String dbCell(String s) {
        if (!isMeaningfulDb(s)) return "无";
        return "<pre style=\"margin:0;white-space:pre-wrap;font-family:Consolas,monospace;font-size:12px;\">"
                + esc(s.trim()) + "</pre>";
    }

    private static String nlToBr(String s) {
        if (s == null || s.isBlank() || "无".equals(s.trim())) return "无";
        String[] parts = s.split("\\R");
        List<String> out = new ArrayList<>();
        for (String p : parts) out.add(esc(p));
        return String.join("<br/>", out);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }
}
