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
            // 建表语句只有最初那几列，后加的列靠这里补齐。
            // 不这么做的话，每加一列就得让人记着去跑一份 migration.sql，漏跑一次就是启动即报错，
            // 而且报出来的是「Unknown column」，看不出该跑哪个文件
            ensureColumn("repo_path", "VARCHAR(512) NULL COMMENT '本地仓库路径'");
            ensureColumn("base_branch", "VARCHAR(128) NULL COMMENT '对比基线分支，默认 master'");
            ensureColumn("success_code_path", "VARCHAR(128) NULL COMMENT '业务成功码路径，如 $.code'");
            ensureColumn("success_code_value", "VARCHAR(64) NULL COMMENT '业务成功码的值，如 0'");
            seedIfEmpty();
            log.info("[api-projects] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-projects] 建表失败: {}", e.getMessage());
        }
    }

    /** 列不存在才加。用查 information_schema 而不是 catch 异常：后者会在日志里刷一堆吓人的堆栈 */
    private void ensureColumn(String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_projects' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE api_projects ADD COLUMN " + column + " " + definition);
            log.info("[api-projects] 补列 {}", column);
        } catch (Exception e) {
            log.warn("[api-projects] 补列 {} 失败: {}", column, e.getMessage());
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
        // 请求里没给的字段保留原值。update 是全列覆盖，前端那个编辑表单又只有编码/名称/说明/启用/排序——
        // 不兜这一下，改一次项目名就把 repoPath 清成 null，接口对比分析直接报「未配仓库」，
        // 而且现场看不出是刚才那次编辑干的
        keepIfNull(body::getRepoPath, body::setRepoPath, existing.getRepoPath());
        keepIfNull(body::getBaseBranch, body::setBaseBranch, existing.getBaseBranch());
        keepIfNull(body::getSuccessCodePath, body::setSuccessCodePath, existing.getSuccessCodePath());
        keepIfNull(body::getSuccessCodeValue, body::setSuccessCodeValue, existing.getSuccessCodeValue());
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(LocalDateTime.now());
        projectMapper.update(body);
        return projectMapper.findByCode(code);
    }

    /**
     * null＝这次不改，保留原值；空串＝明确要清空。
     * <p>
     * 两者必须分开，否则「清掉仓库路径」这个操作就做不到了——传空串会被当成没传。
     */
    private static void keepIfNull(java.util.function.Supplier<String> get,
                                   java.util.function.Consumer<String> set,
                                   String existing) {
        if (get.get() == null) {
            set.accept(existing);
        }
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
