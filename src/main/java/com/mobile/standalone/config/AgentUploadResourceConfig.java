package com.mobile.standalone.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 俊俊上传附件：/agent-uploads/** → ~/.mobile-standalone/uploads/agent-files/
 */
@Configuration
public class AgentUploadResourceConfig implements WebMvcConfigurer {

    @Value("${app.agent-upload-dir:${user.home}/.mobile-standalone/uploads/agent-files}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String location = "file:" + root + "/";
            registry.addResourceHandler("/agent-uploads/**")
                    .addResourceLocations(location)
                    .resourceChain(true)
                    .addResolver(new PathResourceResolver() {
                        @Override
                        protected Resource getResource(String resourcePath, Resource location) throws IOException {
                            Resource resource = super.getResource(resourcePath, location);
                            if (resource == null || !resource.exists()) return null;
                            Path canonical = resource.getFile().toPath().toRealPath();
                            if (!canonical.startsWith(root)) return null;
                            return resource;
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 Agent 上传目录: " + uploadDir, e);
        }
    }
}
