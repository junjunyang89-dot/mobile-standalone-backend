package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 接口清单。
 * <p>
 * 和用例是两回事，这一点之前混了：早先扫代码扫出来的 259 条直接落进了用例表，
 * 每条只有一个 {@code status=200} 断言。那不是用例，是接口目录——
 * 一个接口天然可以有多条用例（正常 / 异常 / 边界），1:1 之后就没地方放；
 * 而且「跑通」只说明接口活着，不说明它对。
 * <p>
 * 拆开之后：清单回答「有哪些接口、覆盖到没有」，用例回答「这个接口对不对」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("apiTestCaseService")
public class ApiEndpointService {

    private final ApiEndpointMapper endpointMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_endpoints (
                        id VARCHAR(64) PRIMARY KEY,
                        project VARCHAR(64) NOT NULL,
                        http_method VARCHAR(16) NOT NULL,
                        path VARCHAR(512) NOT NULL,
                        handler VARCHAR(255) DEFAULT NULL,
                        source_file VARCHAR(512) DEFAULT NULL,
                        summary VARCHAR(512) DEFAULT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                        change_flag VARCHAR(32) DEFAULT NULL,
                        source_branch VARCHAR(255) DEFAULT NULL,
                        change_note VARCHAR(1024) DEFAULT NULL,
                        fields_snapshot MEDIUMTEXT NULL,
                        last_synced_at DATETIME DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_endpoint (project, http_method, path(300)),
                        INDEX idx_project_status (project, status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("api_test_cases", "endpoint_id",
                    "VARCHAR(64) NULL COMMENT '归属接口（api_endpoints.id）' AFTER project");
            migrateExistingCases();
            log.info("[api-endpoints] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-endpoints] 建表失败: {}", e.getMessage());
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD INDEX idx_endpoint_id (" + column + ")");
                log.info("[api-endpoints] 已补充 {}.{} 列", table, column);
            }
        } catch (Exception e) {
            log.debug("[api-endpoints] {}.{} 列检查跳过: {}", table, column, e.getMessage());
        }
    }

    /**
     * 从现有用例反推出接口清单，并把用例挂上去。
     * <p>
     * <b>只增不改不删</b>：现有的 259 条用例一条都不动，只是多了一个 endpoint_id。
     * 那批用例断言弱（多数只有 status=200），但弱不等于错——删掉的话，
     * 「这个接口至少还活着」这个信息也一起没了，而重新扫一遍代码要花的时间比它值钱。
     * <p>
     * 幂等：只处理 {@code endpoint_id} 还是空的用例，所以重启多少次都不会重复建。
     */
    @Transactional
    public int migrateExistingCases() {
        int unlinked;
        try {
            unlinked = endpointMapper.countUnlinkedCases();
        } catch (Exception e) {
            log.debug("[api-endpoints] 迁移检查跳过: {}", e.getMessage());
            return 0;
        }
        if (unlinked == 0) {
            return 0;
        }
        List<ApiEndpoint> drafts = endpointMapper.distinctEndpointsFromCases();
        int created = 0;
        for (ApiEndpoint draft : drafts) {
            ApiEndpoint existing = endpointMapper.findByKey(
                    draft.getProject(), draft.getHttpMethod(), draft.getPath());
            String endpointId;
            if (existing != null) {
                endpointId = existing.getId();
            } else {
                draft.setId("ep-" + UUID.randomUUID());
                draft.setStatus("ACTIVE");
                endpointMapper.insert(draft);
                endpointId = draft.getId();
                created++;
            }
            endpointMapper.linkCasesToEndpoint(
                    endpointId, draft.getProject(), draft.getHttpMethod(), draft.getPath());
        }
        log.info("[api-endpoints] 迁移完成：{} 条用例挂到 {} 个接口上（新建 {} 个）",
                unlinked, drafts.size(), created);
        return created;
    }

    public List<ApiEndpoint> search(String project, String keyword, String changeFlag, String status) {
        List<ApiEndpoint> rows = endpointMapper.search(project, keyword, changeFlag, status);
        for (ApiEndpoint e : rows) {
            e.setCaseCount(e.getCaseCount() == null ? 0 : e.getCaseCount());
            e.setStrongCaseCount(e.getStrongCaseCount() == null ? 0 : e.getStrongCaseCount());
            e.setEnabledCaseCount(e.getEnabledCaseCount() == null ? 0 : e.getEnabledCaseCount());
        }
        return rows;
    }

    public ApiEndpoint get(String id) {
        return id == null ? null : endpointMapper.findById(id);
    }

    /**
     * 把「接口对比分析」的结果同步进清单。
     * <p>
     * 按每条变更自己的 {@code changeType} 走，<b>不</b>拿「当前分支全部接口」去反向标废弃——
     * 报告里只有<b>变更过</b>的接口，把它当全量传进来会把整份清单标成已废弃。
     * 接口没了这件事由 {@code REMOVED} 这条变更自己说。
     * <p>
     * 每次同步先清掉上一轮的变更标记：不清的话「上次新增」会一直挂着，
     * 几个版本之后整份清单全是高亮，等于没有高亮。
     *
     * @param project 项目
     * @param branch  引入这次变更的分支
     * @param changes 报告里的接口变更
     */
    @Transactional
    public SyncResult syncFromChangeReport(String project, String branch, List<EndpointChange> changes) {
        if (project == null || project.isBlank()) {
            return new SyncResult(0, 0, 0);
        }
        endpointMapper.clearChangeFlags(project);
        if (changes == null || changes.isEmpty()) {
            return new SyncResult(0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        int updated = 0;
        int deprecated = 0;

        for (EndpointChange ch : changes) {
            if (ch.httpMethod() == null || ch.path() == null || ch.path().isBlank()) {
                continue;
            }
            ApiEndpoint existing = endpointMapper.findByKey(project, ch.httpMethod(), ch.path());
            boolean removal = EndpointChangeFlag.isRemoval(ch.changeType());

            if (existing == null) {
                if (removal) {
                    // 清单里本来就没有、这次又说它被删了：没什么可记的
                    continue;
                }
                ApiEndpoint row = new ApiEndpoint();
                row.setId("ep-" + UUID.randomUUID());
                row.setProject(project);
                row.setHttpMethod(ch.httpMethod());
                row.setPath(ch.path());
                row.setHandler(ch.handler());
                row.setSummary(ch.summary());
                row.setSourceFile(ch.file());
                row.setStatus("ACTIVE");
                row.setChangeFlag(ch.changeFlag());
                row.setChangeNote(ch.changeNote());
                row.setSourceBranch(branch);
                row.setFieldsSnapshot(fieldsJson(ch.responseFields()));
                row.setLastSyncedAt(now);
                endpointMapper.insert(row);
                created++;
                continue;
            }

            ApiEndpoint patch = new ApiEndpoint();
            patch.setId(existing.getId());
            patch.setHandler(ch.handler());
            patch.setSummary(ch.summary());
            patch.setSourceFile(ch.file());
            patch.setChangeFlag(ch.changeFlag());
            patch.setChangeNote(ch.changeNote());
            patch.setSourceBranch(branch);
            patch.setFieldsSnapshot(fieldsJson(ch.responseFields()));
            patch.setLastSyncedAt(now);
            // 不删，只标废弃：删掉的话挂在它下面的用例会变成孤儿，
            // 而「这个接口被删了」本身就是要确认的事——可能是有意下线，也可能是分支合错了
            if (removal) {
                patch.setStatus("DEPRECATED");
                deprecated++;
            } else {
                // 上次标了废弃、这次又出现了：多半是分支切换或合错导致的误判，恢复成活的
                patch.setStatus("ACTIVE");
                updated++;
            }
            endpointMapper.update(patch);
        }
        log.info("[api-endpoints] 同步 {}（{}）：新建 {}、更新 {}、标废弃 {}",
                project, branch, created, updated, deprecated);
        return new SyncResult(created, updated, deprecated);
    }

    /**
     * 找到（或建出）这条 项目+方法+路径 对应的接口，返回它的 id。
     * <p>
     * 用例保存时调用，让「清单」和「用例」自动保持一致。找不到就建一条：
     * 手写了一条用例、清单里却没有这个接口，说明清单漏了它——
     * 这种时候补进去比丢掉强，一条路径写错的脏数据在清单上是看得见、能删的，
     * 而「用例存在但清单不知道」是看不见的。
     *
     * @return 接口 id；项目或路径为空时返回 null（这种用例本来也挂不上）
     */
    @Transactional
    public String ensureEndpointFor(String project, String httpMethod, String path,
                                    String handler, String summary) {
        if (project == null || project.isBlank() || path == null || path.isBlank()
                || httpMethod == null || httpMethod.isBlank()) {
            return null;
        }
        try {
            ApiEndpoint existing = endpointMapper.findByKey(project, httpMethod, path);
            if (existing != null) {
                return existing.getId();
            }
            ApiEndpoint row = new ApiEndpoint();
            row.setId("ep-" + UUID.randomUUID());
            row.setProject(project);
            row.setHttpMethod(httpMethod);
            row.setPath(path);
            row.setHandler(handler);
            row.setSummary(summary);
            row.setStatus("ACTIVE");
            endpointMapper.insert(row);
            return row.getId();
        } catch (Exception e) {
            // 挂不上不该让保存用例失败——用例是主产物，清单是派生的
            log.warn("[api-endpoints] 用例挂接口失败 {} {} {}: {}", project, httpMethod, path, e.getMessage());
            return null;
        }
    }

    /**
     * 返回体顶层字段名存成 JSON 数组，「生成用例」按它出断言。
     * <p>
     * 只存字段名不存整棵字段树：清单要的是「能不能出一条像样的断言」，
     * 完整字段树在对比分析那边看。存全的话这一列会大到把清单查询拖慢。
     */
    private String fieldsJson(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String f : fields) {
            if (f == null || f.isBlank()) continue;
            if (!first) sb.append(',');
            sb.append('"').append(f.trim().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            first = false;
        }
        return first ? null : sb.append(']').toString();
    }

    /** 读回字段名列表；存的不是合法 JSON 时当作没有，不要让生成用例挂掉 */
    public List<String> responseFieldsOf(ApiEndpoint e) {
        String raw = e == null ? null : e.getFieldsSnapshot();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return com.mobile.standalone.util.ExecutionLogJson.mapper()
                    .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 清单上的状态是算出来的，不存库——存一列人手维护的状态，改了没人回来更新 */
    public ApiEndpointStatus statusOf(ApiEndpoint e) {
        return statusOf(e, false);
    }

    /**
     * @param projectHasBizCode 项目配了业务成功码——配了的话执行时每条用例都会自动带上
     *                          {@code code == 0}，不能再算「仅探活」
     */
    public ApiEndpointStatus statusOf(ApiEndpoint e, boolean projectHasBizCode) {
        int cases = e.getCaseCount() == null ? 0 : e.getCaseCount();
        return ApiEndpointStatus.of(e.getStatus(), e.getChangeFlag(), cases,
                e.getStrongCaseCount() == null ? 0 : e.getStrongCaseCount(),
                e.getEnabledCaseCount() == null ? cases : e.getEnabledCaseCount(),
                projectHasBizCode);
    }

    /** 同步进来的一条接口变化 */
    public record EndpointChange(String httpMethod, String path, String handler, String summary,
                                 String file, String changeType, String changeFlag, String changeNote,
                                 List<String> responseFields) {
    }

    public record SyncResult(int created, int updated, int deprecated) {
    }
}
