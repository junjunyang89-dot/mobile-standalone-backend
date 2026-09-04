package com.mobile.standalone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 线上问题跟踪截图的静态映射。和 Bug 截图同一套做法 */
@Slf4j
@Configuration
public class IssueImageResourceConfig implements WebMvcConfigurer {

    @Value("${app.issue-image-dir:${user.home}/.mobile-standalone/uploads/issues}")
    private String imageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path root = Paths.get(imageDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            registry.addResourceHandler("/issue-images/**")
                    .addResourceLocations("file:" + root + "/")
                    .resourceChain(true);
            log.info("[issue-tracking] 截图目录 {}", root);
        } catch (Exception e) {
            log.warn("[issue-tracking] 截图目录初始化失败: {}", e.getMessage());
        }
    }
}
