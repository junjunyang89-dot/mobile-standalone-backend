package com.mobile.standalone.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 将 URL 路径 /tmp/screenshots/** 映射到文件系统 /tmp/screenshots/，
 * 供前端直接用 reportPath 作为图片地址（如 /api/tmp/screenshots/login_xxx.png）时能正确返回图片。
 * 带路径穿越校验，仅允许访问 /tmp/screenshots 下的文件。
 */
@Configuration
public class ScreenshotResourceConfig implements WebMvcConfigurer {

    private static final Path SCREENSHOTS_ROOT = Paths.get("/tmp/screenshots").toAbsolutePath().normalize();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String[] patterns = { "/tmp/screenshots/**", "tmp/screenshots/**" };
        registry.addResourceHandler(patterns)
                .addResourceLocations("file:" + SCREENSHOTS_ROOT + "/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource resource = super.getResource(resourcePath, location);
                        if (resource == null || !resource.exists()) {
                            return null;
                        }
                        try {
                            Path canonical = resource.getFile().toPath().toRealPath();
                            if (!canonical.startsWith(SCREENSHOTS_ROOT)) {
                                return null;
                            }
                        } catch (IOException e) {
                            return null;
                        }
                        return resource;
                    }
                });
    }
}
