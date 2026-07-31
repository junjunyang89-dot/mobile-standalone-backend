package com.mobile.standalone.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class DatabaseInitializer implements CommandLineRunner {
    
    private final JdbcTemplate jdbcTemplate;
    
    @Value("${spring.datasource.url}")
    private String dbUrl;
    
    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @Override
    public void run(String... args) throws Exception {
        // MySQL 不需要创建目录，直接检查表是否存在
        boolean tablesExist = checkTablesExist();
        
        if (!tablesExist) {
            log.info("Initializing database schema...");
            initializeSchema();
            log.info("Database schema initialized successfully");
        } else {
            log.debug("Database tables already exist");
        }
    }
    
    private boolean checkTablesExist() {
        try {
            String sql = "SELECT COUNT(*) as count FROM information_schema.tables " +
                        "WHERE table_schema = DATABASE() " +
                        "AND table_name IN ('devices', 'tasks', 'task_executions')";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count != null && count >= 3;
        } catch (Exception e) {
            log.warn("Failed to check tables existence: {}", e.getMessage());
            return false;
        }
    }
    
    private void initializeSchema() {
        try {
            ClassPathResource resource = new ClassPathResource("db/schema.sql");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                StringBuilder sql = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    // 跳过注释和空行
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    
                    sql.append(line).append(" ");
                    
                    // 执行完整的 SQL 语句（MySQL 以分号结尾）
                    if (line.endsWith(";")) {
                        String statement = sql.toString().trim();
                        if (!statement.isEmpty() && !statement.equals(";")) {
                            // 移除末尾的分号
                            if (statement.endsWith(";")) {
                                statement = statement.substring(0, statement.length() - 1);
                            }
                            try {
                                jdbcTemplate.execute(statement);
                            } catch (Exception e) {
                                // 忽略已存在的数据库/表错误
                                if (!e.getMessage().contains("already exists") && 
                                    !e.getMessage().contains("Duplicate")) {
                                    log.warn("SQL execution warning: {}", e.getMessage());
                                }
                            }
                        }
                        sql.setLength(0);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize database schema", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
}
