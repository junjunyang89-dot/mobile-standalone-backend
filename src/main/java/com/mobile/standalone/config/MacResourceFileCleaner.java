package com.mobile.standalone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 清理 Mac 系统生成的 ._ 资源分叉文件
 * 在应用启动时自动清理这些文件，避免 Spring Boot 组件扫描时出错
 */
@Component
@Order(1) // 确保在其他组件之前执行
@Slf4j
public class MacResourceFileCleaner implements CommandLineRunner {
    
    @Override
    public void run(String... args) {
        try {
            // 获取 target/classes 目录
            String userDir = System.getProperty("user.dir");
            Path targetClassesPath = Paths.get(userDir, "target", "classes");
            
            if (Files.exists(targetClassesPath)) {
                cleanDirectory(targetClassesPath.toFile());
                log.debug("Cleaned Mac resource fork files from target/classes");
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
                    log.warn("Failed to delete file {}: {}", file.getAbsolutePath(), e.getMessage());
                }
            }
        }
    }
}
