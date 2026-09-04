package com.mobile.standalone;

import com.mobile.standalone.config.AuthMailProperties;
import com.mobile.standalone.config.ChangeReviewProperties;
import com.mobile.standalone.config.MacResourceFileInitializer;
import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.config.WebTestProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ReleaseDocProperties.class, ChangeReviewProperties.class, WebTestProperties.class,
        AuthMailProperties.class})
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BackendApplication.class);
        // 在组件扫描之前清理 Mac 资源分叉文件
        app.addInitializers(new MacResourceFileInitializer());
        app.run(args);
    }
}
