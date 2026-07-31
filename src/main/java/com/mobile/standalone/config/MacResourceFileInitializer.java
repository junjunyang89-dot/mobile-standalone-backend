package com.mobile.standalone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 在 Spring Boot 组件扫描之前清理 Mac 系统生成的 ._ 资源分叉文件
 * 这个初始化器会在 ApplicationContext 创建之前执行
 */
@Slf4j
public class MacResourceFileInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        try {
            // 获取项目根目录
            String userDir = System.getProperty("user.dir");
            Path targetClassesPath = Paths.get(userDir, "target", "classes");
            
            if (Files.exists(targetClassesPath)) {
                cleanDirectory(targetClassesPath.toFile());
                log.debug("Cleaned Mac resource fork files from target/classes before component scanning");
            }
        } catch (Exception e) {
            log.warn("Failed to clean Mac resource fork files: {}", e.getMessage());
        }
    }
    
    private void cleanDirectory(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                cleanDirectory(file);
            } else if (file.getName().startsWith("._")) {
                try {
                    Files.delete(file.toPath());
                    log.debug("Deleted Mac resource fork file: {}", file.getAbsolutePath());
                } catch (Exception e) {
                    // 忽略删除失败的文件
                }
            }
        }
    }
}
