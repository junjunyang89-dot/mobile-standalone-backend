package com.mobile.standalone.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "web-test")
public class WebTestProperties {
    private boolean enabled = true;
    /** Playwright 工程目录（相对工作目录或绝对路径） */
    private String projectDir = "scripts/h5-playwright";
    private String npxCommand = "npx";
    private int timeoutSec = 180;
}
