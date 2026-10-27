package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发版看板：合并 master 后自动触发的 Jenkins 大包任务。
 * 账号密码放本机 application.yml，勿提交到公开仓库。
 */
@Data
@ConfigurationProperties(prefix = "release-jenkins")
public class ReleaseJenkinsProperties {
    private boolean enabled = true;
    private String baseUrl = "http://jenkins.shdev.net";
    private String username = "";
    private String password = "";
    /** 项目 code → Jenkins job 名，后续可继续追加 */
    private Map<String, String> jobs = new LinkedHashMap<>();
}
