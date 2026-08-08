package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiProjectService {

    private static final Pattern CODE_OK = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}$");

    private final ApiProjectMapper projectMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_projects (
                        code VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            seedIfEmpty();
            log.info("[api-projects] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-projects] 建表失败: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (projectMapper.countAll() > 0) return;
        LocalDateTime now = LocalDateTime.now();
        ApiProject p = new ApiProject();
        p.setCode(ApiTestCaseService.PROJECT_DATA_CENTER);
        p.setName("data-center（数据中心）");
        p.setDescription("Deadlock / 生涯等后端数据服务；接口用例与 URL 归属此项目");
        p.setEnabled(true);
        p.setSortOrder(10);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        projectMapper.insert(p);
        log.info("[api-projects] 已种子项目 data-center");
    }

    public List<ApiProject> list(Boolean enabledOnly) {
        return projectMapper.findAll(enabledOnly);
    }

    public ApiProject get(String code) {
        return projectMapper.findByCode(code);
    }

    @Transactional
    public ApiProject create(ApiProject body) {
        validate(body, true);
        if (projectMapper.findByCode(body.getCode()) != null) {
            throw new IllegalArgumentException("项目编码已存在: " + body.getCode());
        }
        defaults(body);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        projectMapper.insert(body);
        return projectMapper.findByCode(body.getCode());
    }

    @Transactional
    public ApiProject update(String code, ApiProject body) {
        ApiProject existing = projectMapper.findByCode(code);
        if (existing == null) return null;
        body.setCode(code);
        validate(body, false);
        defaults(body);
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(LocalDateTime.now());
        projectMapper.update(body);
        return projectMapper.findByCode(code);
    }

    @Transactional
    public boolean delete(String code) {
        return projectMapper.deleteByCode(code) > 0;
    }

    /** 确保编码存在（种子/导入时可调用） */
    public void ensureExists(String code, String nameIfCreate) {
        if (code == null || code.isBlank()) return;
        if (projectMapper.findByCode(code) != null) return;
        ApiProject p = new ApiProject();
        p.setCode(code.trim());
        p.setName(nameIfCreate != null && !nameIfCreate.isBlank() ? nameIfCreate : code);
        p.setEnabled(true);
        p.setSortOrder(100);
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        try {
            projectMapper.insert(p);
        } catch (Exception e) {
            log.debug("[api-projects] ensureExists 跳过: {}", e.getMessage());
        }
    }

    private void validate(ApiProject body, boolean creating) {
        if (creating) {
            if (body.getCode() == null || body.getCode().isBlank()) {
                throw new IllegalArgumentException("项目编码 code 必填");
            }
            String code = body.getCode().trim();
            if (!CODE_OK.matcher(code).matches()) {
                throw new IllegalArgumentException("编码仅支持字母数字及 ._- ，且不超过 63 字符");
            }
            body.setCode(code);
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("项目名称必填");
        }
    }

    private void defaults(ApiProject body) {
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
        if (body.getCode() != null) {
            body.setCode(body.getCode().trim());
        }
    }
}
