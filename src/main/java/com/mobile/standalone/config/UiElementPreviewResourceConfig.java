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
 * 元素预览图：URL /ui-element-previews/** → 本地 uploads/ui-elements/
 */
@Configuration
public class UiElementPreviewResourceConfig implements WebMvcConfigurer {

    @Value("${app.ui-element-preview-dir:${user.home}/.mobile-standalone/uploads/ui-elements}")
    private String previewDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        try {
            Path root = Paths.get(previewDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String location = "file:" + root + "/";
            registry.addResourceHandler("/ui-element-previews/**")
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
            throw new IllegalStateException("无法创建元素预览图目录: " + previewDir, e);
        }
    }
}
