package com.mobile.standalone.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {
    
    private final String reportsDir = System.getProperty("user.home") + "/.mobile-standalone/reports";
    
    @GetMapping
    public ResponseEntity<List<ReportInfo>> getAllReports() {
        List<ReportInfo> reports = new ArrayList<>();
        try {
            Path reportsPath = Paths.get(reportsDir);
            if (Files.exists(reportsPath)) {
                try (Stream<Path> paths = Files.walk(reportsPath, 1)) {
                    paths.filter(Files::isDirectory)
                         .filter(path -> !path.equals(reportsPath))
                         .forEach(path -> {
                             File indexFile = path.resolve("index.html").toFile();
                             if (indexFile.exists()) {
                                 ReportInfo info = new ReportInfo();
                                 info.setId(path.getFileName().toString());
                                 info.setName(path.getFileName().toString());
                                 info.setPath(path.toString());
                                 info.setCreatedAt(indexFile.lastModified());
                                 info.setSize(getDirectorySize(path.toFile()));
                                 reports.add(info);
                             }
                         });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ResponseEntity.ok(reports);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ReportInfo> getReport(@PathVariable String id) {
        Path reportPath = Paths.get(reportsDir, id);
        File indexFile = reportPath.resolve("index.html").toFile();
        
        if (indexFile.exists()) {
            ReportInfo info = new ReportInfo();
            info.setId(id);
            info.setName(id);
            info.setPath(reportPath.toString());
            info.setCreatedAt(indexFile.lastModified());
            info.setSize(getDirectorySize(reportPath.toFile()));
            return ResponseEntity.ok(info);
        }
        return ResponseEntity.notFound().build();
    }
    
    private long getDirectorySize(File directory) {
        long size = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }
    
    public static class ReportInfo {
        private String id;
        private String name;
        private String path;
        private long createdAt;
        private long size;
        
        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
    }
}
