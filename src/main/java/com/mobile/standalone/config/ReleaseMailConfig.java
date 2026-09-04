package com.mobile.standalone.config;

import com.mobile.standalone.config.ReleaseDocProperties.Mail;
import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.security.GeneralSecurityException;
import java.util.Properties;

/**
 * 公司 Exchange/OWA 常见自签或主机名不匹配证书，发信时放宽 TLS 身份校验（仅发版邮件通道）。
 */
@Configuration
public class ReleaseMailConfig {

    /**
     * 加了认证邮件通道（{@link AuthMailConfig}）之后，容器里有两个 JavaMailSender。
     * <p>
     * {@code ReleaseMailService} 是按类型注入的、没写限定符，两个候选会让它在<b>启动时</b>直接失败。
     * 标 Primary 让它继续拿到发版这个（和以前完全一样的行为）；认证那边是显式
     * {@code @Qualifier("authMailSender")}，不受影响。
     */
    @Primary
    @Bean
    public JavaMailSender releaseJavaMailSender(ReleaseDocProperties props) throws GeneralSecurityException {
        Mail m = props.getMail() != null ? props.getMail() : new Mail();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(m.getHost());
        sender.setPort(m.getPort());
        sender.setUsername(m.getUsername());
        sender.setPassword(m.getPassword());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");

        MailSSLSocketFactory sf = new MailSSLSocketFactory();
        sf.setTrustAllHosts(true);

        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(m.isSmtpAuth()));
        p.put("mail.smtp.ssl.trust", "*");
        p.put("mail.smtp.ssl.checkserveridentity", "false");
        p.put("mail.smtp.ssl.socketFactory", sf);
        // STARTTLS / SSL 共用同一信任策略
        p.put("mail.smtp.ssl.socketFactory.fallback", "false");

        if (m.isSsl()) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.socketFactory", sf);
            p.put("mail.smtp.socketFactory.class", MailSSLSocketFactory.class.getName());
            p.put("mail.smtp.socketFactory.port", String.valueOf(m.getPort()));
            p.put("mail.smtp.starttls.enable", "false");
        } else if (m.isStarttls()) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
            p.put("mail.smtp.ssl.enable", "false");
        }

        p.put("mail.smtp.connectiontimeout", "20000");
        p.put("mail.smtp.timeout", "40000");
        p.put("mail.smtp.writetimeout", "40000");
        return sender;
    }
}
