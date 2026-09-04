package com.mobile.standalone.config;

import org.eclipse.angus.mail.util.MailSSLSocketFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.security.GeneralSecurityException;
import java.util.Properties;

/**
 * 认证邮件的发信器，独立于发版邮件那个 bean。
 * <p>
 * TLS 放宽策略与 {@link ReleaseMailConfig} 一致：公司 Exchange 用的是自签 / 主机名不匹配的证书，
 * 不放宽根本连不上。这是内网通道，可以接受。
 */
@Configuration
public class AuthMailConfig {

    @Bean("authMailSender")
    public JavaMailSender authMailSender(AuthMailProperties props) throws GeneralSecurityException {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.getHost());
        sender.setPort(props.getPort());
        sender.setUsername(props.getUsername());
        sender.setPassword(props.getPassword());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");

        MailSSLSocketFactory sf = new MailSSLSocketFactory();
        sf.setTrustAllHosts(true);

        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(props.isSmtpAuth()));
        p.put("mail.smtp.ssl.trust", "*");
        p.put("mail.smtp.ssl.checkserveridentity", "false");
        p.put("mail.smtp.ssl.socketFactory", sf);
        p.put("mail.smtp.ssl.socketFactory.fallback", "false");

        if (props.isSsl()) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.socketFactory", sf);
            p.put("mail.smtp.socketFactory.class", MailSSLSocketFactory.class.getName());
            p.put("mail.smtp.socketFactory.port", String.valueOf(props.getPort()));
            p.put("mail.smtp.starttls.enable", "false");
        } else if (props.isStarttls()) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
            p.put("mail.smtp.ssl.enable", "false");
        }

        // 验证码是用户在等的东西，超时给得比发版邮件短：与其挂 40 秒不如早点失败进重试
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        return sender;
    }
}
