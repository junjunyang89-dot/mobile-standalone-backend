package com.mobile.standalone.auth;

import com.mobile.standalone.config.AuthMailProperties;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 验证码发信。
 * <p>
 * <b>异步</b>：接口不能等 SMTP。等了的话，「账号存在」和「账号不存在」的响应耗时会差出几百毫秒，
 * 耗时本身就成了一个账号枚举信道——恒定文案就白做了。
 * <p>
 * <b>重试</b>：线上日志里出现过连续两次 {@code Got bad greeting ... [EOF]}（TCP 连上了服务器立刻掐断，
 * 常见于反垃圾节流）。发版邮件碰上这个手动重发就行，验证码不重试就是用户永远收不到。
 */
@Slf4j
@Component
public class PasswordResetMailer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final AuthMailProperties props;
    /** 单线程够用：验证码是人触发的，量级很小。队列积压反而说明通道出问题了，值得在日志里看见 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auth-mail");
        t.setDaemon(true);
        return t;
    });

    public PasswordResetMailer(@Qualifier("authMailSender") JavaMailSender mailSender,
                               AuthMailProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void sendResetCode(PlatformUser user, String to, String code, String requestIp) {
        String body = """
                %s 你好，

                有人正在为你的测试平台账号申请重置密码。验证码是：

                    %s

                有效期 %d 分钟，仅可使用一次。请回到测试平台客户端填写。

                申请时间：%s
                申请来源：%s

                如果不是你本人操作，请忽略这封邮件，并联系平台管理员。
                本邮件由系统自动发送，请勿直接回复。
                """.formatted(displayName(user), code, props.getCodeTtlMinutes(),
                LocalDateTime.now().format(TS), requestIp == null ? "未知" : requestIp);
        enqueue(to, "【测试平台】密码重置验证码", body, "reset");
    }

    public void sendBindCode(PlatformUser user, String to, String code) {
        String body = """
                %s 你好，

                你正在把这个邮箱绑定到测试平台账号，用于以后忘记密码时自助找回。验证码是：

                    %s

                有效期 %d 分钟，仅可使用一次。

                申请时间：%s

                如果不是你本人操作，请忽略这封邮件。
                本邮件由系统自动发送，请勿直接回复。
                """.formatted(displayName(user), code, props.getCodeTtlMinutes(),
                LocalDateTime.now().format(TS));
        enqueue(to, "【测试平台】邮箱绑定验证码", body, "bind");
    }

    private void enqueue(String to, String subject, String body, String kind) {
        if (!props.isEnabled()) {
            log.warn("[auth-mail] 通道未启用（auth-mail.enabled=false），跳过发送 kind={} to={}", kind, mask(to));
            return;
        }
        if (props.getUsername() == null || props.getUsername().isBlank()
                || props.getPassword() == null || props.getPassword().isBlank()) {
            log.error("[auth-mail] 未配置发信账号，验证码发不出去 kind={} to={}", kind, mask(to));
            return;
        }
        pool.submit(() -> sendWithRetry(to, subject, body, kind));
    }

    /** 失败重试，间隔递增。全部失败也只落日志——调用方早就返回了，这里没人可以报错给他 */
    private void sendWithRetry(String to, String subject, String body, String kind) {
        int total = Math.max(1, props.getRetry() + 1);
        long[] backoffMs = {0L, 2000L, 5000L, 15000L};
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(backoffMs[Math.min(i, backoffMs.length - 1)]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                String from = props.getFrom() == null || props.getFrom().isBlank()
                        ? props.getUsername() : props.getFrom();
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
                helper.setFrom(new InternetAddress(from, "测试平台", "UTF-8"));
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body, false);
                mailSender.send(message);
                log.info("[auth-mail] 已发送 kind={} to={} 第{}次尝试", kind, mask(to), i + 1);
                return;
            } catch (Exception e) {
                log.warn("[auth-mail] 发送失败 kind={} to={} 第{}/{}次: {}",
                        kind, mask(to), i + 1, total, e.getMessage());
            }
        }
        // 这一条要能被搜出来：用户说「没收到」时，先看有没有它，再去看垃圾箱
        log.error("[auth-mail] 重试 {} 次仍失败，验证码没有送达 kind={} to={}", total, kind, mask(to));
    }

    private static String displayName(PlatformUser user) {
        if (user == null) return "";
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName() : user.getUsername();
    }

    /** 日志里不写完整邮箱：日志会被打包、被贴到群里 */
    static String mask(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String name = email.substring(0, at);
        String kept = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return kept + "***" + email.substring(at);
    }
}
