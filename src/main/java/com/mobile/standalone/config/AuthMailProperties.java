package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证邮件（验证码）通道。
 * <p>
 * 和发版邮件 {@code release-doc.mail} <b>刻意分成两段配置</b>：发版邮件发失败了可以手动重发，
 * 验证码发失败时用户正在盯着屏幕等。两者的发信账号、重试策略将来一定会分叉，
 * 混在一段里改一个就会碰坏另一个。
 */
@Data
@ConfigurationProperties(prefix = "auth-mail")
public class AuthMailProperties {

    /** 关掉时「忘记密码」接口照常返回恒定文案，只是不发信——不能因为没配邮件就暴露账号是否存在 */
    private boolean enabled = false;

    private String host = "mail.mx.wanmei.net";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String from = "";
    private boolean smtpAuth = true;
    private boolean starttls = true;
    private boolean ssl = false;

    /** 验证码有效期 */
    private int codeTtlMinutes = 10;

    /**
     * 发送失败的重试次数（不含首次）。
     * <p>
     * 线上日志里出现过连续两次 {@code Got bad greeting ... [EOF]}——TCP 连上了服务器立刻掐断，
     * 常见于反垃圾节流。发版邮件遇上这个手动重发就行，验证码不重试就是用户永远收不到。
     */
    private int retry = 2;
}
