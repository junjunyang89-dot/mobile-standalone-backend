package com.mobile.standalone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 测试知识点截图。和 Bug / 线上问题同一套静态映射 */
@Slf4j
@Configuration
public class KnowledgeNoteImageResourceConfig implements WebMvcConfigurer {

    @Value("${app.knowledge-note-image-dir:${user.home}/.mobile-standalone/uploads/knowledge-notes}")
    private String imageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path root = Paths.get(imageDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            registry.addResourceHandler("/knowledge-note-images/**")
                    .addResourceLocations("file:" + root + "/")
                    .resourceChain(true);
            log.info("[knowledge-notes] 截图目录 {}", root);
        } catch (Exception e) {
            log.warn("[knowledge-notes] 截图目录初始化失败: {}", e.getMessage());
        }
    }
}
