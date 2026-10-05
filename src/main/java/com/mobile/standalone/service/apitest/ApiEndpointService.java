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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** 扫描时自动废弃基础接口记的操作人；人手取消废弃后下次扫描会再标上 */
    static final String INFRA_RETIRED_BY = "系统：基础接口";

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
            ensureColumn("api_endpoints", "capture_meta",
                    "MEDIUMTEXT NULL COMMENT '抓包解析：字段样本、请求预览' AFTER fields_snapshot");
            ensureColumn("api_endpoints", "doc_meta",
                    "MEDIUMTEXT NULL COMMENT '接口文档：说明、依赖、关联接口' AFTER capture_meta");
            // 接口涉及的表：对比分析顺着 Controller→Service→Mapper 算出来后存在这。
            // 存下来才能做「表结构变了→反查哪些接口→再反查哪些用例」，也让接口清单
            // 能直接展开看「这个接口动哪些表 + DDL 测试点」，不用每次重算
            ensureColumn("api_endpoints", "tables_json",
                    "VARCHAR(2048) NULL COMMENT '涉及的表（JSON 数组），来自对比分析的依赖解析' AFTER doc_meta");
            ensureColumn("api_endpoints", "auth_required",
                    "TINYINT(1) NULL COMMENT '要不要登录：源码有鉴权注解=1，空=看不出来' AFTER tables_json");
            ensureColumn("api_endpoints", "callers_json",
                    "MEDIUMTEXT NULL COMMENT '调用方位置（JSON 数组）' AFTER tables_json");
            ensureColumn("api_endpoints", "caller_status",
                    "VARCHAR(16) NULL COMMENT 'USED/DEF_ONLY/NONE，空=没搜过' AFTER callers_json");
            ensureColumn("api_endpoints", "callers_scanned_at",
                    "DATETIME NULL COMMENT '最近一次搜调用方' AFTER caller_status");
            // 人工确认的废弃单独一列，不复用 status：status 由扫描维护，接口还在源码里就会被改回 ACTIVE
            ensureColumn("api_endpoints", "retired_at",
                    "DATETIME NULL COMMENT '人工标记废弃的时间，空=没标' AFTER callers_scanned_at");
            ensureColumn("api_endpoints", "retired_by",
                    "VARCHAR(100) NULL COMMENT '谁标的废弃' AFTER retired_at");
            ensureColumn("api_endpoints", "level_manual",
                    "VARCHAR(16) NULL COMMENT '人工定的自动化级别 CORE/IMPORTANT/NORMAL/UNUSED，空=按调用方自动算' AFTER retired_by");
            ensureColumn("api_endpoints", "level_set_by",
                    "VARCHAR(100) NULL COMMENT '谁改的级别' AFTER level_manual");
            ensureColumn("api_endpoints", "code_path",
                    "VARCHAR(512) NULL COMMENT '源码 Controller 声明的路径（不带网关前缀）' AFTER path");
            ensureColumn("api_endpoints", "gone_at",
                    "DATETIME NULL COMMENT '按基线分支扫描时代码里已没有' AFTER level_set_by");
            ensureColumn("api_endpoints", "gone_ref",
                    "VARCHAR(255) NULL COMMENT '在哪个分支/提交上发现没有的' AFTER gone_at");
            endpointMapper.backfillCodePath();
            ensureChangesTable();
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
                if ("endpoint_id".equals(column)) {
                    jdbcTemplate.execute("ALTER TABLE " + table + " ADD INDEX idx_endpoint_id (" + column + ")");
                }
                if ("code_path".equals(column)) {
                    jdbcTemplate.execute("ALTER TABLE " + table
                            + " ADD INDEX idx_code_path (project, http_method, code_path(255))");
                }
                log.info("[api-endpoints] 已补充 {}.{} 列", table, column);
            }
        } catch (Exception e) {
            log.debug("[api-endpoints] {}.{} 列检查跳过: {}", table, column, e.getMessage());
        }
    }

    /**
     * 变更标记按分支存。以前标记直接写在接口上，分析 B 分支会把 A 分支的标记清掉——
     * 两个需求并行提测时，先分析的那个就看不到自己的必测接口了。
     */
    private void ensureChangesTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS api_endpoint_changes (
                    endpoint_id VARCHAR(64) NOT NULL,
                    project VARCHAR(64) NOT NULL,
                    branch VARCHAR(255) NOT NULL,
                    change_flag VARCHAR(32) DEFAULT NULL,
                    change_note VARCHAR(1024) DEFAULT NULL,
                    change_type VARCHAR(32) DEFAULT NULL,
                    analyzed_at DATETIME NOT NULL,
                    PRIMARY KEY (endpoint_id, branch(191)),
                    INDEX idx_branch (branch(191), project)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        // 分支版本存在变更行上，全量那条保持 master 的样子，上线时才写回
        ensureColumn("api_endpoint_changes", "handler", "VARCHAR(255) NULL");
        ensureColumn("api_endpoint_changes", "summary", "VARCHAR(512) NULL");
        ensureColumn("api_endpoint_changes", "source_file", "VARCHAR(512) NULL");
        ensureColumn("api_endpoint_changes", "fields_snapshot", "MEDIUMTEXT NULL");
        ensureColumn("api_endpoint_changes", "tables_json", "MEDIUMTEXT NULL");
        forgetBaselineBranches();
        if (endpointMapper.countChanges() == 0) {
            int n = endpointMapper.migrateColumnFlags();
            if (n > 0) {
                log.info("[api-endpoints] 已把 {} 条变更标记迁到按分支存", n);
            }
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS api_source_scans (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project VARCHAR(64) NOT NULL,
                    ref_name VARCHAR(255) DEFAULT NULL,
                    sha VARCHAR(64) DEFAULT NULL,
                    committed_at DATETIME DEFAULT NULL,
                    scanned_at DATETIME NOT NULL,
                    endpoints INT DEFAULT NULL,
                    summary VARCHAR(1024) DEFAULT NULL,
                    INDEX idx_project_time (project, scanned_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    /** 以前拿 master 对比过 testing 之类留下的记录：那不是需求分支，接口本来就在基线里 */
    private void forgetBaselineBranches() {
        try {
            String in = BaselineBranches.names().stream().map(n -> "'" + n + "','origin/" + n + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            List<String> projects = jdbcTemplate.queryForList(
                    "SELECT DISTINCT project FROM api_endpoint_changes WHERE LOWER(branch) IN (" + in + ")"
                            + " UNION SELECT DISTINCT project FROM api_endpoints WHERE LOWER(source_branch) IN (" + in + ")",
                    String.class);
            if (projects.isEmpty()) {
                return;
            }
            int changes = jdbcTemplate.update("DELETE FROM api_endpoint_changes WHERE LOWER(branch) IN (" + in + ")");
            int owned = jdbcTemplate.update(
                    "UPDATE api_endpoints SET source_branch = NULL WHERE LOWER(source_branch) IN (" + in + ")");
            endpointMapper.recomputeChangeFlags(projects);
            log.info("[api-endpoints] 清掉基线分支上的分支记录：变更 {} 条、接口归属 {} 条", changes, owned);
        } catch (Exception e) {
            log.warn("[api-endpoints] 清理基线分支记录失败（树上仍会过滤掉）: {}", e.getMessage());
        }
    }

    /**
     * 记一次全量扫描：页面要能回答「清单是按哪个分支、哪个提交、什么时候扫的」。
     *
     * @param ref 分支名；null＝扫的是本地目录
     */
    public void recordScan(String project, String ref, String sha, LocalDateTime committedAt,
                           int endpoints, String summary) {
        try {
            jdbcTemplate.update("INSERT INTO api_source_scans (project, ref_name, sha, committed_at, scanned_at, endpoints, summary) "
                            + "VALUES (?, ?, ?, ?, NOW(), ?, ?)",
                    project, ref, sha, committedAt, endpoints,
                    summary == null || summary.length() <= 1000 ? summary : summary.substring(0, 1000));
        } catch (Exception e) {
            log.debug("[api-endpoints] 记扫描历史失败 {}: {}", project, e.getMessage());
        }
    }

    /** 每个项目最近一次扫描；projects 为空时取全部 */
    public Map<String, Map<String, Object>> lastScans(List<String> projects) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        try {
            String sql = """
                    SELECT s.project, s.ref_name AS ref, s.sha, s.committed_at AS committedAt,
                           s.scanned_at AS scannedAt, s.endpoints, s.summary
                      FROM api_source_scans s
                      JOIN (SELECT project, MAX(id) AS id FROM api_source_scans GROUP BY project) t ON t.id = s.id
                    """;
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql)) {
                String code = String.valueOf(r.get("project"));
                if (projects == null || projects.isEmpty() || projects.contains(code)) {
                    out.put(code, r);
                }
            }
        } catch (Exception e) {
            log.debug("[api-endpoints] 查扫描历史失败: {}", e.getMessage());
        }
        return out;
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
        List<String> projectCodes = null;
        if (project != null && !project.isBlank()) {
            projectCodes = ApiAppCatalog.matchProjectCodes(project);
        }
        List<ApiEndpoint> rows = endpointMapper.search(projectCodes, keyword, changeFlag, status);
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

    /** id → 接口，不含用例统计 */
    public Map<String, ApiEndpoint> getAll(java.util.Collection<String> ids) {
        List<String> distinct = ids == null ? List.of() : ids.stream()
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, ApiEndpoint> out = new HashMap<>();
        for (int i = 0; i < distinct.size(); i += 500) {
            for (ApiEndpoint e : endpointMapper.findByIds(distinct.subList(i, Math.min(i + 500, distinct.size())))) {
                out.put(e.getId(), e);
            }
        }
        return out;
    }

    /** 解析接口 handler，供用例集合等模块展示 Controller 名称 */
    public String handlerOfEndpoint(String endpointId) {
        ApiEndpoint ep = get(endpointId);
        return ep != null ? ep.getHandler() : null;
    }

    /** 表名列表 → JSON 数组字符串；空列表存 null，别存个 "[]" 占位 */
    static String tablesJson(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tables);
        } catch (Exception e) {
            return null;
        }
    }

    public ScanSyncResult upsertFromSource(String project, List<SourceEndpointScanner.Scanned> scanned) {
        return upsertFromSource(project, scanned, null);
    }

    /**
     * 全量扫描的结果入库。
     * <p>
     * 认接口按<b>代码路径</b>，不按调用路径：抓包进来的 {@code /steamcn/a/b} 和代码里的 {@code /a/b}
     * 是同一个接口，按 path 精确比会再建一条，用例、抓包样本、变更标记就分散在两行上。
     * <ol>
     *   <li>代码路径（或还没记代码路径、path 正好相同的）对上的：补方法名 / 中文名 / 源码文件</li>
     *   <li>抓包建的、还没认过代码的：按整段后缀认（{@link SourceControllerMatcher}）</li>
     *   <li>剩下的新建，path 补上这个项目的网关前缀——不补的话生成的用例打到网关是 404</li>
     *   <li>{@code baseRef} 不为空（按基线分支扫的）时，代码里找不到的标「基线里没有」，不删</li>
     * </ol>
     * 不动变更标记：全量扫描回答「现在有哪些接口」，「这一版改了什么」是对比分析的事。
     *
     * @param baseRef 扫的是哪个基线分支 / 提交；null＝扫的是本地目录，不标「没有」——本地目录可能是任意分支
     */
    @Transactional
    public ScanSyncResult upsertFromSource(String project, List<SourceEndpointScanner.Scanned> scanned, String baseRef) {
        LocalDateTime now = LocalDateTime.now();
        List<ApiEndpoint> rows = new ArrayList<>(endpointMapper.search(List.of(project), null, null, null));
        Map<String, List<ApiEndpoint>> byCode = new HashMap<>();
        Map<String, ApiEndpoint> byPath = new HashMap<>();
        for (ApiEndpoint r : rows) {
            if (r.getCodePath() != null && !r.getCodePath().isBlank()) {
                byCode.computeIfAbsent(key(r.getHttpMethod(), r.getCodePath()), k -> new ArrayList<>()).add(r);
            }
            byPath.put(key(r.getHttpMethod(), r.getPath()), r);
        }
        Set<String> hitIds = new java.util.HashSet<>();
        List<SourceEndpointScanner.Scanned> unclaimed = new ArrayList<>();
        int updated = 0;
        int linked = 0;

        for (SourceEndpointScanner.Scanned s : scanned) {
            List<ApiEndpoint> hits = byCode.getOrDefault(key(s.httpMethod(), s.path()), List.of());
            if (hits.isEmpty()) {
                ApiEndpoint same = byPath.get(key(s.httpMethod(), s.path()));
                if (same != null && (same.getCodePath() == null || same.getCodePath().isBlank())) {
                    hits = List.of(same);
                }
            }
            if (hits.isEmpty()) {
                unclaimed.add(s);
                continue;
            }
            for (ApiEndpoint r : hits) {
                patchFromSource(r, s, now);
                hitIds.add(r.getId());
                updated++;
            }
        }

        // 抓包建的行：路径带网关前缀，按整段后缀认
        List<SourceEndpointScanner.Scanned> stillUnclaimed = new ArrayList<>(unclaimed);
        for (ApiEndpoint r : rows) {
            if (hitIds.contains(r.getId()) || r.getCodePath() != null && !r.getCodePath().isBlank()) {
                continue;
            }
            var m = SourceControllerMatcher.match(r.getHttpMethod(), r.getPath(), scanned);
            if (m.kind() != SourceControllerMatcher.Kind.EXACT && m.kind() != SourceControllerMatcher.Kind.SUFFIX) {
                continue;
            }
            if (SourceControllerMatcher.gatewayPrefix(r.getPath(), m.hit().path()) == null) {
                continue;
            }
            patchFromSource(r, m.hit(), now);
            hitIds.add(r.getId());
            stillUnclaimed.remove(m.hit());
            linked++;
        }

        String prefix = learnedPrefix(rows);
        int created = 0;
        for (SourceEndpointScanner.Scanned s : stillUnclaimed) {
            String path = joinPath(prefix, s.path());
            ApiEndpoint taken = byPath.get(key(s.httpMethod(), path));
            if (taken != null) {
                patchFromSource(taken, s, now);
                hitIds.add(taken.getId());
                updated++;
                continue;
            }
            ApiEndpoint row = new ApiEndpoint();
            row.setId("ep-" + UUID.randomUUID());
            row.setProject(project);
            row.setHttpMethod(s.httpMethod());
            row.setPath(path);
            row.setCodePath(s.path());
            row.setHandler(s.handler());
            row.setSummary(blankToNull(s.summary()));
            row.setSourceFile(s.file());
            row.setStatus("ACTIVE");
            row.setLastSyncedAt(now);
            endpointMapper.insert(row);
            endpointMapper.updateAuthRequired(row.getId(), s.auth());
            byPath.put(key(row.getHttpMethod(), path), row);
            hitIds.add(row.getId());
            created++;
        }

        // 以前扫进来、没加前缀的：没有用例、没抓过包的直接补上；有用例的不动，免得用例和接口对不上
        int reprefixed = 0;
        if (!prefix.isEmpty()) {
            for (ApiEndpoint r : rows) {
                if (isCaptured(r) || r.getCodePath() == null || !r.getCodePath().equals(r.getPath())
                        || r.getCaseCount() != null && r.getCaseCount() > 0) {
                    continue;
                }
                String path = joinPath(prefix, r.getPath());
                if (byPath.containsKey(key(r.getHttpMethod(), path))) {
                    continue;
                }
                endpointMapper.updatePath(r.getId(), path);
                byPath.put(key(r.getHttpMethod(), path), r);
                reprefixed++;
            }
        }

        // 以前扫进来 / 抓进来的 Swagger、下线、探活接口：标废弃，执行器那边也会拦
        for (ApiEndpoint r : rows) {
            if (!r.isRetired() && (InfraEndpoints.is(r.getPath()) || InfraEndpoints.is(r.getCodePath()))) {
                endpointMapper.updateRetired(r.getId(), now, INFRA_RETIRED_BY);
                r.setRetiredAt(now);
                hitIds.add(r.getId());
            }
        }

        int gone = 0;
        int revived = 0;
        if (baseRef != null && !baseRef.isBlank()) {
            for (ApiEndpoint r : rows) {
                boolean fromCode = r.getCodePath() != null && !r.getCodePath().isBlank()
                        || r.getSourceFile() != null && !r.getSourceFile().isBlank();
                if (hitIds.contains(r.getId())) {
                    if (r.getGoneAt() != null) {
                        endpointMapper.updateGone(r.getId(), null, null);
                        revived++;
                    }
                } else if (fromCode && r.getGoneAt() == null
                        && (r.getSourceBranch() == null || r.getSourceBranch().isBlank())) {
                    // 分支上新加的接口本来就不在基线里，等它上线并入后再说
                    endpointMapper.updateGone(r.getId(), now, baseRef);
                    gone++;
                }
            }
        }
        log.info("[api-endpoints] 源码全量扫描 {}（{}）：新建 {}、更新 {}、认上抓包 {}、补前缀 {}、基线里没有 {}、重新出现 {}，网关前缀「{}」",
                project, baseRef == null ? "本地目录" : baseRef, created, updated, linked, reprefixed, gone, revived, prefix);
        return new ScanSyncResult(created, updated, linked, reprefixed, gone, revived, prefix);
    }

    private void patchFromSource(ApiEndpoint r, SourceEndpointScanner.Scanned s, LocalDateTime now) {
        ApiEndpoint patch = new ApiEndpoint();
        patch.setId(r.getId());
        patch.setCodePath(s.path());
        patch.setHandler(s.handler());
        // 源码里没写 @ApiOperation 时别把人工补过的中文名抹掉
        patch.setSummary(blankToNull(s.summary()));
        patch.setSourceFile(s.file());
        patch.setStatus("ACTIVE");
        patch.setLastSyncedAt(now);
        endpointMapper.update(patch);
        endpointMapper.updateAuthRequired(r.getId(), s.auth());
        r.setCodePath(s.path());
        r.setSourceFile(s.file());
    }

    /** 已经认上代码的抓包接口里，占多数的那段网关前缀；没有就是空串 */
    static String learnedPrefix(List<ApiEndpoint> rows) {
        List<String> prefixes = new ArrayList<>();
        for (ApiEndpoint r : rows) {
            if (isCaptured(r) && r.getCodePath() != null && !r.getCodePath().isBlank()) {
                String p = SourceControllerMatcher.gatewayPrefix(r.getPath(), r.getCodePath());
                if (p != null) {
                    prefixes.add(p);
                }
            }
        }
        String p = SourceControllerMatcher.dominantPrefix(prefixes);
        return p == null ? "" : p;
    }

    static String joinPath(String prefix, String path) {
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return prefix == null || prefix.isEmpty() ? p : prefix + p;
    }

    private static String key(String method, String path) {
        return (method == null ? "" : method.trim().toUpperCase(Locale.ROOT)) + " " + (path == null ? "" : path.trim());
    }

    /**
     * 在其他仓库里搜这个项目每个接口的调用方，结果挂到接口上。
     *
     * @param roots   搜哪些目录
     * @param exclude 项目自己的源码目录，它自己的 Controller 不算调用方
     */
    public CallerSyncResult syncCallers(String project, List<java.nio.file.Path> roots, java.nio.file.Path exclude)
            throws java.io.IOException {
        List<ApiEndpoint> rows = endpointMapper.search(List.of(project), null, null, null);
        // 调用方代码里可能写全路径，也可能只写代码路径（网关前缀放在 baseUrl 里），两个都搜
        List<String> paths = rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.getPath(), r.getCodePath()))
                .filter(p -> p != null && !p.isBlank())
                .distinct().toList();
        Map<String, List<CallerRefScanner.Ref>> found = CallerRefScanner.scan(paths, roots, exclude);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        int used = 0;
        int defOnly = 0;
        int none = 0;
        for (ApiEndpoint row : rows) {
            List<CallerRefScanner.Ref> refs = new ArrayList<>(found.getOrDefault(row.getPath(), List.of()));
            if (!blank(row.getCodePath()) && !row.getCodePath().equals(row.getPath())) {
                for (CallerRefScanner.Ref r : found.getOrDefault(row.getCodePath(), List.of())) {
                    if (!refs.contains(r)) {
                        refs.add(r);
                    }
                }
            }
            String status;
            if (refs.isEmpty()) {
                status = "NONE";
                none++;
            } else if (refs.stream().allMatch(CallerRefScanner.Ref::unused)) {
                status = "DEF_ONLY";
                defOnly++;
            } else {
                status = "USED";
                used++;
            }
            endpointMapper.updateCallers(row.getId(), refs.isEmpty() ? null : json.writeValueAsString(refs), status);
        }
        log.info("[api-endpoints] 调用方 {}：有调用 {}、只定义 {}、没找到 {}", project, used, defOnly, none);
        return new CallerSyncResult(used, defOnly, none);
    }

    public record CallerSyncResult(int used, int defOnly, int none) {
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * @param linked     抓包建的接口这次认上了代码
     * @param reprefixed 以前没带网关前缀的接口补上了前缀
     * @param gone       基线里已经没有的
     * @param revived    以前标了没有、这次又出现的
     * @param prefix     这个项目的网关前缀，空串＝没有或说不准
     */
    public record ScanSyncResult(int created, int updated, int linked, int reprefixed,
                                 int gone, int revived, String prefix) {
        public ScanSyncResult(int created, int updated) {
            this(created, updated, 0, 0, 0, 0, "");
        }
    }

    /**
     * 把「接口对比分析」的结果同步进清单。
     * <p>
     * 按每条变更自己的 {@code changeType} 走，<b>不</b>拿「当前分支全部接口」去反向标废弃——
     * 报告里只有<b>变更过</b>的接口，把它当全量传进来会把整份清单标成已废弃。
     * <p>
     * 标记按分支存：只替换<b>这个分支</b>上一轮的记录，别的分支的不动——
     * 两个需求并行提测时，分析 B 不能把 A 的必测接口清掉。
     * 接口按代码路径认（报告里的 path 是代码路径），认不上才新建；
     * 只有新建的行记 {@code source_branch}，基线里本来就有的接口不归某个分支。
     * <p>
     * 已有接口只记分支版本、不改全量那条；分支删掉的接口也只记一笔，上线时才标废弃。
     *
     * @param branch 引入这次变更的分支
     * @return deprecated 是「这个分支删掉的」条数，全量里还没动
     */
    @Transactional
    public SyncResult syncFromChangeReport(String project, String branch, List<EndpointChange> changes) {
        if (project == null || project.isBlank()) {
            return new SyncResult(0, 0, 0);
        }
        String br = branch == null || branch.isBlank() ? "(未记录分支)" : branch.trim();
        endpointMapper.deleteChangesByBranch(project, br);
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        int updated = 0;
        int deprecated = 0;
        List<ApiEndpoint> rows = changes == null || changes.isEmpty()
                ? List.of() : new ArrayList<>(endpointMapper.listByProject(project, null));
        String prefix = learnedPrefix(rows);

        for (EndpointChange ch : changes == null ? List.<EndpointChange>of() : changes) {
            if (ch.httpMethod() == null || ch.path() == null || ch.path().isBlank()) {
                continue;
            }
            ApiEndpoint existing = byCodePath(rows, ch.httpMethod(), ch.path());
            if (existing == null) {
                existing = endpointMapper.findByKey(project, ch.httpMethod(), ch.path());
            }
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
                String path = joinPath(prefix, ch.path());
                row.setPath(endpointMapper.findByKey(project, ch.httpMethod(), path) == null ? path : ch.path());
                row.setCodePath(ch.path());
                row.setHandler(ch.handler());
                row.setSummary(ch.summary());
                row.setSourceFile(ch.file());
                row.setStatus("ACTIVE");
                row.setChangeFlag(ch.changeFlag());
                row.setChangeNote(ch.changeNote());
                row.setSourceBranch(br);
                row.setFieldsSnapshot(fieldsJson(ch.responseFields()));
                row.setTablesJson(tablesJson(ch.tables()));
                row.setLastSyncedAt(now);
                endpointMapper.insert(row);
                rows.add(row);
                endpointMapper.upsertChange(row.getId(), project, br, ch.changeFlag(), ch.changeNote(), ch.changeType());
                created++;
                continue;
            }

            if (existing.getCodePath() == null || existing.getCodePath().isBlank()) {
                ApiEndpoint patch = new ApiEndpoint();
                patch.setId(existing.getId());
                patch.setCodePath(ch.path());
                endpointMapper.update(patch);
                existing.setCodePath(ch.path());
            }
            // 全量那条保持 master 的样子：分支没上线前，分析 A、B 两个分支不能互相覆盖，
            // 也不能让回归跑的用例突然对着还没上线的出参断言。分支版本记在变更行上，上线时写回
            String flag = removal ? EndpointChangeFlag.REMOVED : ch.changeFlag();
            String note = removal ? "这个分支删掉了该接口，上线后才标废弃" : ch.changeNote();
            String tables = ch.tables() != null && !ch.tables().isEmpty() ? tablesJson(ch.tables()) : null;
            endpointMapper.upsertChangeWithSnapshot(existing.getId(), project, br, flag, note, ch.changeType(),
                    removal ? null : ch.handler(), removal ? null : ch.summary(), removal ? null : ch.file(),
                    removal ? null : fieldsJson(ch.responseFields()), removal ? null : tables);
            if (removal) {
                deprecated++;
            } else {
                updated++;
            }
        }
        endpointMapper.recomputeChangeFlags(List.of(project));
        log.info("[api-endpoints] 同步 {}（{}）：新建 {}、记分支改动 {}、分支删除 {}",
                project, br, created, updated, deprecated);
        return new SyncResult(created, updated, deprecated);
    }

    /**
     * 按代码路径找接口：先比 code_path；再比还没记代码路径、path 正好相同的；
     * 最后是抓包建的、去掉网关前缀后正好是这个代码路径的（只有一条时才认）。
     */
    static ApiEndpoint byCodePath(List<ApiEndpoint> rows, String method, String codePath) {
        String m = method.trim().toUpperCase(Locale.ROOT);
        String code = codePath.trim();
        List<ApiEndpoint> same = rows.stream()
                .filter(r -> r.getHttpMethod() != null && m.equalsIgnoreCase(r.getHttpMethod()))
                .toList();
        // 同一个代码路径存在重复行（抓包那条 + 源码那条，还没合并）时取抓包那条：它的 path 才是客户端真正调的
        ApiEndpoint hit = same.stream().filter(r -> code.equals(r.getCodePath()))
                .min(java.util.Comparator.comparing(r -> isCaptured(r) ? 0 : 1)).orElse(null);
        if (hit != null) {
            return hit;
        }
        hit = same.stream().filter(r -> blank(r.getCodePath()) && code.equals(r.getPath())).findFirst().orElse(null);
        if (hit != null) {
            return hit;
        }
        List<ApiEndpoint> alias = same.stream()
                .filter(r -> blank(r.getCodePath()) && isCaptured(r)
                        && SourceControllerMatcher.gatewayPrefix(r.getPath(), code) != null)
                .toList();
        return alias.size() == 1 ? alias.get(0) : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 按分支汇总：对比分析出来的接口挂在分支上，那是测试环境树的另一个根。
     * <p>
     * 分支和单号是两个入口，不是两级：一个分支上合了好几个单，
     * 一个单也可能跨分支。硬做成父子，总有一边对不上。
     */
    public List<java.util.Map<String, Object>> summaryByBranch() {
        try {
            return endpointMapper.summaryChangesByBranch();
        } catch (Exception e) {
            log.debug("[api-endpoints] 按分支汇总失败: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** 这个分支对比分析出来的接口 id */
    public Set<String> endpointIdsOfBranch(String project, String branch) {
        if (project == null || branch == null) {
            return Set.of();
        }
        try {
            return new java.util.HashSet<>(endpointMapper.endpointIdsOfBranch(project, branch.trim()));
        } catch (Exception e) {
            log.debug("[api-endpoints] 查分支接口失败: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 接口 id → 在哪些分支上有变更（最近分析的在前） */
    public Map<String, List<Map<String, Object>>> branchesOf(List<String> projectCodes) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        try {
            for (Map<String, Object> r : endpointMapper.listChanges(projectCodes)) {
                Object id = r.get("endpointId");
                if (id == null) {
                    continue;
                }
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("branch", r.get("branch"));
                b.put("changeFlag", r.get("changeFlag"));
                b.put("changeNote", r.get("changeNote"));
                b.put("analyzedAt", r.get("analyzedAt"));
                out.computeIfAbsent(String.valueOf(id), k -> new ArrayList<>()).add(b);
            }
        } catch (Exception e) {
            log.debug("[api-endpoints] 查分支变更失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 分支合上线：这一批接口并进 master。
     * 这个分支的变更记录删掉，不再算「本分支必测」；别的分支的标记不受影响。
     */
    @Transactional
    public int promoteBranchToMaster(String branch) {
        return promoteBranchToMaster(null, branch).total();
    }

    /**
     * 分支上线：老接口换成分支版本，分支删掉的标废弃，新增的去掉分支归属进全量。
     *
     * @param project 空＝这个分支在所有项目下的记录
     */
    @Transactional
    public PromoteResult promoteBranchToMaster(String project, String branch) {
        if (branch == null || branch.isBlank()) {
            return new PromoteResult(0, 0, 0);
        }
        String br = branch.trim();
        String pj = project == null || project.isBlank() ? null : project.trim();
        List<String> projects = pj == null ? endpointMapper.projectsOfBranch(br) : List.of(pj);
        int updated = 0;
        int deprecated = 0;
        for (Map<String, Object> s : endpointMapper.listBranchSnapshots(pj, br)) {
            String id = s.get("endpointId") == null ? null : String.valueOf(s.get("endpointId"));
            if (id == null || EndpointChangeFlag.NEW.equals(s.get("changeFlag"))) {
                continue;
            }
            if (EndpointChangeFlag.REMOVED.equals(s.get("changeFlag"))
                    || EndpointChangeFlag.isRemoval(str(s.get("changeType")))) {
                endpointMapper.markDeprecated(id);
                deprecated++;
                continue;
            }
            ApiEndpoint patch = new ApiEndpoint();
            patch.setId(id);
            patch.setHandler(str(s.get("handler")));
            patch.setSummary(str(s.get("summary")));
            patch.setSourceFile(str(s.get("sourceFile")));
            patch.setFieldsSnapshot(str(s.get("fieldsSnapshot")));
            patch.setTablesJson(str(s.get("tablesJson")));
            patch.setStatus("ACTIVE");
            patch.setLastSyncedAt(LocalDateTime.now());
            endpointMapper.update(patch);
            updated++;
        }
        int added = endpointMapper.clearSourceBranchOf(pj, br);
        endpointMapper.deleteChangesByBranch(pj, br);
        if (!projects.isEmpty()) {
            endpointMapper.recomputeChangeFlags(projects);
        }
        log.info("[api-endpoints] 分支 {}{} 已并入 master：更新 {}、新增 {}、标废弃 {}",
                br, pj == null ? "" : "（" + pj + "）", updated, added, deprecated);
        return new PromoteResult(updated, added, deprecated);
    }

    public record PromoteResult(int updated, int added, int deprecated) {
        public int total() {
            return updated + added + deprecated;
        }
    }

    public List<String> projectsOfBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return List.of();
        }
        try {
            return endpointMapper.projectsOfBranch(branch.trim());
        } catch (Exception e) {
            log.debug("[api-endpoints] 查分支所在项目失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 同 {@link #branchesOf}，按接口 id 查；全量页只关心当前这一屏的接口 */
    public Map<String, List<Map<String, Object>>> branchesOfEndpoints(java.util.Collection<String> endpointIds) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        List<String> ids = endpointIds == null ? List.of()
                : endpointIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        try {
            for (Map<String, Object> r : endpointMapper.listChangesOf(ids)) {
                Map<String, Object> b = new java.util.LinkedHashMap<>();
                b.put("branch", r.get("branch"));
                b.put("changeFlag", r.get("changeFlag"));
                b.put("changeNote", r.get("changeNote"));
                b.put("analyzedAt", r.get("analyzedAt"));
                out.computeIfAbsent(String.valueOf(r.get("endpointId")), k -> new ArrayList<>()).add(b);
            }
        } catch (Exception e) {
            log.debug("[api-endpoints] 查分支变更失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 同项目下、路径尾部相同、但<b>还没有文档</b>的接口。
     * <p>
     * 同一个契约常常有两条路径：GLD 直连的 {@code /gld/deadlock/friend/list}
     * 和 DC 编排透传的 {@code /appdatacenter/deadlock/data/friend/list}
     * （文档原话「data-center 编排并透传」）。文档只在其中一条下面放参数表，
     * 而人抓到的往往恰恰是没写的那条——结果就是那条接口一条边界用例都生成不出来。
     * <p>
     * 用<b>最后两段</b>匹配，不是最后一段：只比一段的话 {@code /a/list} 和
     * {@code /b/list} 会被认成同一个，那种串味比不匹配糟得多。
     * <p>
     * 只套给<b>还没有文档</b>的：已经有文档的说明文档里单独写了它，
     * 拿别人的约束盖掉是在用推断覆盖事实。
     */
    public List<String> sameTailPaths(String project, String httpMethod, String path) {
        String tail = tailOf(path);
        if (tail == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            for (ApiEndpoint e : endpointMapper.search(List.of(project), null, null, null)) {
                if (e.getPath() == null || e.getPath().equals(path)) {
                    continue;
                }
                if (!httpMethod.equalsIgnoreCase(e.getHttpMethod())) {
                    continue;
                }
                if (e.getDocMeta() != null && !e.getDocMeta().isBlank()) {
                    continue;
                }
                if (tail.equals(tailOf(e.getPath()))) {
                    out.add(e.getPath());
                }
            }
        } catch (Exception ex) {
            log.debug("[api-endpoints] 找同名路径失败 {}: {}", path, ex.getMessage());
        }
        return out;
    }

    /** 路径最后两段，如 {@code friend/list} */
    static String tailOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.trim().replaceAll("^/+|/+$", "").split("/");
        if (parts.length < 2) {
            return null;
        }
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    /** 浏览器跨域前自动发的 OPTIONS：没有业务响应，源码里也没有对应方法，不进接口清单 */
    public static boolean isPreflight(String httpMethod) {
        return httpMethod != null && "OPTIONS".equalsIgnoreCase(httpMethod.trim());
    }

    /**
     * 找到（或建出）这条 项目+方法+路径 对应的接口，返回它的 id。
     * <p>
     * 用例保存、抓包入库时调用，让「清单」和「用例」自动保持一致。找不到就建一条：
     * 手写了一条用例、清单里却没有这个接口，说明清单漏了它——
     * 这种时候补进去比丢掉强，一条路径写错的脏数据在清单上是看得见、能删的，
     * 而「用例存在但清单不知道」是看不见的。
     * <p>
     * 路径对不上时先按代码路径认：抓包的 {@code /steamcn/a/b} 落到源码扫出来的 {@code /a/b} 上，
     * 那一行的 path 改成抓包路径（客户端真正调的），不另建一条。
     *
     * @return 接口 id；项目或路径为空时返回 null（这种用例本来也挂不上）
     */
    public String ensureEndpointFor(String project, String httpMethod, String path,
                                    String handler, String summary) {
        if (project == null || project.isBlank() || path == null || path.isBlank()
                || httpMethod == null || httpMethod.isBlank() || isPreflight(httpMethod)) {
            return null;
        }
        try {
            ApiEndpoint existing = endpointMapper.findByKey(project, httpMethod, path);
            if (existing != null) {
                return existing.getId();
            }
            ApiEndpoint alias = aliasTarget(endpointMapper.listByProject(project, httpMethod), path);
            if (alias != null) {
                if (!isCaptured(alias) && alias.getPath() != null && alias.getPath().equals(codeOf(alias))
                        && SourceControllerMatcher.gatewayPrefix(path, alias.getPath()) != null) {
                    endpointMapper.updatePath(alias.getId(), path);
                    if (blank(alias.getCodePath())) {
                        ApiEndpoint patch = new ApiEndpoint();
                        patch.setId(alias.getId());
                        patch.setCodePath(alias.getPath());
                        endpointMapper.update(patch);
                    }
                    log.info("[api-endpoints] {} {} 认到源码接口 {}，路径改成实际调用的", httpMethod, path, alias.getPath());
                }
                return alias.getId();
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

    /** 行的代码路径：记过的用记的；源码扫进来、没抓过包的老数据 path 就是代码路径 */
    static String codeOf(ApiEndpoint r) {
        if (!blank(r.getCodePath())) {
            return r.getCodePath();
        }
        return !isCaptured(r) && !blank(r.getSourceFile()) ? r.getPath() : null;
    }

    /**
     * 调用路径 → 声明它的那一行（同项目同方法）。代码路径最长的赢；一样长的有两条就不认，
     * 宁可多建一条让人合并，也别把抓包样本挂到别的接口上。
     */
    static ApiEndpoint aliasTarget(List<ApiEndpoint> sameMethod, String calledPath) {
        ApiEndpoint best = null;
        int bestLen = -1;
        boolean tie = false;
        for (ApiEndpoint r : sameMethod) {
            String code = codeOf(r);
            if (code == null || SourceControllerMatcher.gatewayPrefix(calledPath, code) == null) {
                continue;
            }
            int len = code.length();
            if (len > bestLen) {
                best = r;
                bestLen = len;
                tie = false;
            } else if (len == bestLen && !r.getId().equals(best.getId())) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /**
     * 一对重复：{@code keep} 是抓包建的（path 带网关前缀），{@code drop} 是源码扫出来的（path 是代码路径）。
     */
    public record DuplicatePair(String project, String httpMethod,
                                String keepId, String keepPath, int keepCases,
                                String dropId, String dropPath, int dropCases,
                                String handler, String summary) {
    }

    /** 一条源码接口对上了好几条抓包接口（或反过来），自动合并会合错，列出来给人看 */
    public record DuplicateAmbiguous(String project, String httpMethod, String codePath, List<String> paths) {
    }

    public record DuplicateReport(List<DuplicatePair> pairs, List<DuplicateAmbiguous> ambiguous) {
    }

    /**
     * 找出「同一个接口、两行记录」：抓包那行的路径 = 网关前缀 + 源码那行的路径。
     * 这是改成按代码路径认接口之前留下的存量。
     */
    static DuplicateReport findDuplicates(List<ApiEndpoint> rows) {
        Map<String, List<ApiEndpoint>> groups = new java.util.LinkedHashMap<>();
        for (ApiEndpoint r : rows) {
            if (r.getPath() != null && r.getHttpMethod() != null) {
                groups.computeIfAbsent(r.getProject() + "\u0000" + r.getHttpMethod().toUpperCase(Locale.ROOT),
                        k -> new ArrayList<>()).add(r);
            }
        }
        List<DuplicatePair> pairs = new ArrayList<>();
        List<DuplicateAmbiguous> ambiguous = new ArrayList<>();
        for (List<ApiEndpoint> g : groups.values()) {
            List<ApiEndpoint> captured = g.stream().filter(ApiEndpointService::isCaptured).toList();
            // 源码那行：没抓过包、path 就是代码路径
            List<ApiEndpoint> code = g.stream()
                    .filter(r -> !isCaptured(r) && r.getPath().equals(codeOf(r)))
                    .toList();
            Map<String, List<ApiEndpoint>> keepFor = new java.util.LinkedHashMap<>();
            Map<String, ApiEndpoint> bestCodeOfKeep = new HashMap<>();
            for (ApiEndpoint a : captured) {
                List<ApiEndpoint> fits = code.stream()
                        .filter(b -> !b.getId().equals(a.getId()))
                        .filter(b -> blank(a.getCodePath()) || a.getCodePath().equals(b.getPath()))
                        .filter(b -> {
                            String p = SourceControllerMatcher.gatewayPrefix(a.getPath(), b.getPath());
                            return p != null && !p.isEmpty();
                        })
                        .toList();
                if (fits.isEmpty()) {
                    continue;
                }
                int longest = fits.stream().mapToInt(b -> b.getPath().length()).max().orElse(0);
                List<ApiEndpoint> top = fits.stream().filter(b -> b.getPath().length() == longest).toList();
                if (top.size() > 1) {
                    ambiguous.add(new DuplicateAmbiguous(a.getProject(), a.getHttpMethod(), a.getPath(),
                            top.stream().map(ApiEndpoint::getPath).toList()));
                    continue;
                }
                bestCodeOfKeep.put(a.getId(), top.get(0));
                keepFor.computeIfAbsent(top.get(0).getId(), k -> new ArrayList<>()).add(a);
            }
            for (ApiEndpoint b : code) {
                List<ApiEndpoint> keeps = keepFor.getOrDefault(b.getId(), List.of());
                if (keeps.size() > 1) {
                    ambiguous.add(new DuplicateAmbiguous(b.getProject(), b.getHttpMethod(), b.getPath(),
                            keeps.stream().map(ApiEndpoint::getPath).toList()));
                } else if (keeps.size() == 1) {
                    ApiEndpoint a = keeps.get(0);
                    pairs.add(new DuplicatePair(a.getProject(), a.getHttpMethod(),
                            a.getId(), a.getPath(), a.getCaseCount() == null ? 0 : a.getCaseCount(),
                            b.getId(), b.getPath(), b.getCaseCount() == null ? 0 : b.getCaseCount(),
                            b.getHandler() != null ? b.getHandler() : a.getHandler(),
                            !blank(a.getSummary()) ? a.getSummary() : b.getSummary()));
                }
            }
        }
        return new DuplicateReport(pairs, ambiguous);
    }

    public DuplicateReport duplicates(String project) {
        List<String> codes = project == null || project.isBlank() ? null : ApiAppCatalog.matchProjectCodes(project);
        return findDuplicates(endpointMapper.search(codes, null, null, null));
    }

    /**
     * 合并重复：保留抓包那行，源码那行的方法名、源码文件、用例、需求关联、分支变更记录都挪过来，然后删掉源码那行。
     * 只合并当前仍然成对的（页面上的清单可能过时了），所以传的是要删掉的那行的 id。
     */
    @Transactional
    public int mergeDuplicates(String project, List<String> dropIds) {
        if (dropIds == null || dropIds.isEmpty()) {
            return 0;
        }
        Set<String> wanted = new java.util.HashSet<>(dropIds);
        Map<String, ApiEndpoint> byId = new HashMap<>();
        List<String> codes = project == null || project.isBlank() ? null : ApiAppCatalog.matchProjectCodes(project);
        List<ApiEndpoint> rows = endpointMapper.search(codes, null, null, null);
        rows.forEach(r -> byId.put(r.getId(), r));
        int merged = 0;
        for (DuplicatePair p : findDuplicates(rows).pairs()) {
            if (!wanted.contains(p.dropId())) {
                continue;
            }
            mergeInto(byId.get(p.keepId()), byId.get(p.dropId()));
            merged++;
        }
        log.info("[api-endpoints] 合并重复接口 {} 对", merged);
        return merged;
    }

    private void mergeInto(ApiEndpoint keep, ApiEndpoint drop) {
        ApiEndpoint patch = new ApiEndpoint();
        patch.setId(keep.getId());
        patch.setCodePath(drop.getPath());
        if (!blank(drop.getHandler())) {
            patch.setHandler(drop.getHandler());
        }
        if (!blank(drop.getSourceFile())) {
            patch.setSourceFile(drop.getSourceFile());
        }
        if (blank(keep.getSummary()) && !blank(drop.getSummary())) {
            patch.setSummary(drop.getSummary());
        }
        if (blank(keep.getFieldsSnapshot()) && !blank(drop.getFieldsSnapshot())) {
            patch.setFieldsSnapshot(drop.getFieldsSnapshot());
        }
        if (blank(keep.getDocMeta()) && !blank(drop.getDocMeta())) {
            patch.setDocMeta(drop.getDocMeta());
        }
        if (blank(keep.getTablesJson()) && !blank(drop.getTablesJson())) {
            patch.setTablesJson(drop.getTablesJson());
        }
        if ("ACTIVE".equals(drop.getStatus())) {
            patch.setStatus("ACTIVE");
        }
        endpointMapper.update(patch);
        if (drop.getAuthRequired() != null) {
            endpointMapper.updateAuthRequired(keep.getId(), drop.getAuthRequired());
        }
        if (blank(keep.getCallerStatus()) && !blank(drop.getCallerStatus())) {
            endpointMapper.updateCallers(keep.getId(), drop.getCallersJson(), drop.getCallerStatus());
        }
        if (blank(keep.getLevelManual()) && !blank(drop.getLevelManual())) {
            endpointMapper.updateLevel(keep.getId(), drop.getLevelManual(), drop.getLevelSetBy());
        }
        endpointMapper.moveCases(drop.getId(), keep.getId(), drop.getPath(), keep.getPath());
        endpointMapper.moveRequirementLinks(drop.getId(), keep.getId());
        endpointMapper.moveChanges(drop.getId(), keep.getId());
        endpointMapper.deleteChangesOf(drop.getId());
        endpointMapper.deleteById(drop.getId());
        endpointMapper.recomputeChangeFlags(List.of(keep.getProject()));
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

    public Map<String, Object> captureMetaOf(ApiEndpoint e) {
        return EndpointAssertionContext.readMeta(e == null ? null : e.getCaptureMeta());
    }

    public Map<String, Object> docMetaOf(ApiEndpoint e) {
        return EndpointAssertionContext.readMeta(e == null ? null : e.getDocMeta());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> captureSamplesOf(ApiEndpoint e) {
        Map<String, Object> meta = captureMetaOf(e);
        Object s = meta.get("samples");
        if (!(s instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }

    /**
     * 把抓包时的真实 query 参数存下来。
     * <p>
     * 边界用例要「只改一个参数，其余保持真实」——没有真实值的话，
     * 请求本身就是错的，跑出来的失败说明不了任何问题。
     * 而真实值只有抓包拿得到：文档只给默认值，必填参数（steamId 这种）根本没有默认值。
     */
    @Transactional
    public void applyCaptureQuery(String project, String httpMethod, String path, String query) {
        applyCaptureRequest(project, httpMethod, path, query, null);
    }

    /**
     * 存下抓包时的<b>完整请求</b>：query + 请求头。
     * <p>
     * 生成用例时只带一个 {@code Accept} 是跑不起来的——网关要 token、要 appId、
     * 要 steamId，缺一个就直接回 {@code Service is busy}。而这些值只有抓包拿得到。
     * <p>
     * 鉴权头<b>不存</b>：token 会过期，写死进用例的话，过期那天所有用例集体变红，
     * 而红的原因和接口本身毫无关系。token 归环境（{@code api_test_envs.default_token}），
     * 一处改、全部生效。
     */
    @Transactional
    public void applyCaptureRequest(String project, String httpMethod, String path,
                                    String query, Map<String, String> reqHeaders) {
        if (project == null || path == null) {
            return;
        }
        Map<String, String> parsed = query == null || query.isBlank()
                ? Map.of() : parseQuery(query);
        Map<String, String> headers = usefulHeaders(reqHeaders);
        if (parsed.isEmpty() && headers.isEmpty()) {
            return;
        }
        try {
            ApiEndpoint row = endpointMapper.findByKey(project, httpMethod, path);
            if (row == null) {
                return;
            }
            Map<String, Object> meta = new java.util.LinkedHashMap<>(captureMetaOf(row));
            if (!parsed.isEmpty()) {
                meta.put("queryParams", parsed);
            }
            if (!headers.isEmpty()) {
                meta.put("reqHeaders", headers);
            }
            meta.putIfAbsent("capturedAt", java.time.LocalDateTime.now().toString());
            row.setCaptureMeta(EndpointAssertionContext.metaJson(meta));
            endpointMapper.update(row);
        } catch (Exception e) {
            // 存不下来只影响生成的请求完整度，不该让抓包收尾失败
            log.debug("[api-endpoints] 存抓包请求失败 {} {}: {}", httpMethod, path, e.getMessage());
        }
    }

    /** 传输层的头和鉴权头都不留：前者换个客户端就变，后者会过期 */
    private static final Set<String> DROP_HEADERS = Set.of(
            "host", "content-length", "accept-encoding", "connection", "transfer-encoding",
            "token", "authorization", "cookie", "x-access-token", "x-auth-token");

    static Map<String, String> usefulHeaders(Map<String, String> raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        raw.forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }
            if (!DROP_HEADERS.contains(k.trim().toLowerCase(Locale.ROOT))) {
                out.put(k.trim(), v);
            }
        });
        return out;
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int i = pair.indexOf('=');
            // 没有等号的（?flag 这种）当成空值参数，不是丢掉：
            // 它照样是请求的一部分，边界用例重放时要带上
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            if (!k.isBlank()) {
                out.put(java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * 抓包挂接口时：把响应体解析进 fields_snapshot + capture_meta。
     */
    @Transactional
    public void applyCaptureDetail(String endpointId, String reqBody, String respBody) {
        if (endpointId == null || endpointId.isBlank()) {
            return;
        }
        ApiEndpoint row = endpointMapper.findById(endpointId);
        if (row == null) {
            return;
        }
        CaptureJsonParser.Sample sample = CaptureJsonParser.parseBodies(reqBody, respBody);
        String fullReq = reqBody == null || reqBody.isBlank() || reqBody.length() > 16384 ? null : reqBody.trim();
        String respSample = CaptureJsonParser.compactJson(respBody, 8000);
        if (sample.fieldNames().isEmpty() && sample.topLevelSamples().isEmpty() && fullReq == null
                && respSample == null) {
            return;
        }

        List<String> merged = mergeFieldNames(responseFieldsOf(row), sample.fieldNames());
        if (!merged.isEmpty()) {
            row.setFieldsSnapshot(fieldsJson(merged));
        }

        // 合并而不是覆盖：applyCaptureRequest 存的 queryParams / reqHeaders 不能被这里冲掉
        Map<String, Object> capMeta = new java.util.LinkedHashMap<>(captureMetaOf(row));
        capMeta.put("capturedAt", java.time.LocalDateTime.now().toString());
        if (!sample.fieldNames().isEmpty()) {
            capMeta.put("fields", sample.fieldNames());
            capMeta.put("samples", sample.topLevelSamples());
        }
        if (sample.reqPreview() != null) {
            capMeta.put("reqPreview", sample.reqPreview());
        }
        // POST 用例要原样请求体才能跑，reqPreview 是截断过的
        if (fullReq != null) {
            capMeta.put("reqBody", fullReq);
        }
        // AI 按它写断言：只有字段名的话，模型不知道业务码是几、数据在哪一层
        if (respSample != null) {
            capMeta.put("respSample", respSample);
        }
        row.setCaptureMeta(EndpointAssertionContext.metaJson(capMeta));
        endpointMapper.update(row);
        log.info("[api-endpoints] {} 已写入抓包样本 {} 个字段", endpointId, sample.fieldNames().size());
    }

    /**
     * 接口文档导入：说明、依赖写入 doc_meta（<b>仅参考</b>，不合并进 fields_snapshot）。
     * <p>
     * 各开发文档格式不一，字段也常与线上一致性无保证——不能拿来生成断言。
     */
    @Transactional
    public ApiEndpoint applyDocMeta(String project, String httpMethod, String path, Map<String, Object> docMeta) {
        if (project == null || path == null || httpMethod == null || docMeta == null || docMeta.isEmpty()) {
            return null;
        }
        String epId = ensureEndpointFor(project, httpMethod, path,
                str(docMeta.get("operationId")), str(docMeta.get("summary")));
        if (epId == null) {
            return null;
        }
        ApiEndpoint row = endpointMapper.findById(epId);
        if (row == null) {
            return null;
        }

        String summary = str(docMeta.get("summary"));
        if ((row.getSummary() == null || row.getSummary().isBlank()) && summary != null) {
            row.setSummary(summary);
        }

        docMeta.putIfAbsent("importedAt", java.time.LocalDateTime.now().toString());
        docMeta.putIfAbsent("referenceOnly", true);
        row.setDocMeta(EndpointAssertionContext.metaJson(docMeta));
        endpointMapper.update(row);
        return endpointMapper.findById(epId);
    }

    private static List<String> mergeFieldNames(List<String> existing, List<String> extra) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (existing != null) {
            existing.forEach(f -> {
                if (f != null && !f.isBlank()) {
                    set.add(f.trim());
                }
            });
        }
        if (extra != null) {
            extra.forEach(f -> {
                if (f != null && !f.isBlank()) {
                    set.add(f.trim());
                }
            });
        }
        return new ArrayList<>(set);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
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
        if (e.isRetired()) {
            return ApiEndpointStatus.RETIRED;
        }
        int cases = e.getCaseCount() == null ? 0 : e.getCaseCount();
        return ApiEndpointStatus.of(e.getStatus(), e.getChangeFlag(), cases,
                e.getStrongCaseCount() == null ? 0 : e.getStrongCaseCount(),
                e.getEnabledCaseCount() == null ? cases : e.getEnabledCaseCount(),
                projectHasBizCode);
    }

    /**
     * 人工标记 / 取消废弃。只动 retired_* 两列，扫描和对比分析都不碰它们。
     *
     * @return 实际改到的条数
     */
    @Transactional
    public int setRetired(List<String> ids, boolean retired, String operator) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        LocalDateTime at = retired ? LocalDateTime.now() : null;
        String by = retired ? operator : null;
        int n = 0;
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                n += endpointMapper.updateRetired(id.trim(), at, by);
            }
        }
        return n;
    }

    /** level 为 null 时清掉人工级别，回到按调用方自动算 */
    public int setLevel(List<String> ids, EndpointLevel level, String operator) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String value = level == null ? null : level.name();
        String by = level == null ? null : operator;
        int n = 0;
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                n += endpointMapper.updateLevel(id.trim(), value, by);
            }
        }
        return n;
    }

    /** 人工定过的优先，其次抓包抓到过，再其次按调用方算 */
    public static EndpointLevel levelOf(ApiEndpoint e) {
        if (e == null) {
            return null;
        }
        EndpointLevel manual = EndpointLevel.parse(e.getLevelManual());
        return manual != null ? manual
                : EndpointLevel.of(e.getCallerStatus(), e.getCallersJson(), isCaptured(e)).level();
    }

    /** capture_meta 只有抓包会写 */
    public static boolean isCaptured(ApiEndpoint e) {
        return e != null && e.getCaptureMeta() != null && !e.getCaptureMeta().isBlank();
    }

    /** 抓包抓到过就记一笔：没参数、没请求体、响应也解析不出字段的接口，同样是客户端在调 */
    public void markCaptured(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            return;
        }
        try {
            ApiEndpoint row = endpointMapper.findById(endpointId);
            if (row == null) {
                return;
            }
            Map<String, Object> meta = new java.util.LinkedHashMap<>(captureMetaOf(row));
            if (meta.containsKey("capturedAt")) {
                return;
            }
            meta.put("capturedAt", java.time.LocalDateTime.now().toString());
            row.setCaptureMeta(EndpointAssertionContext.metaJson(meta));
            endpointMapper.update(row);
        } catch (Exception e) {
            log.debug("[api-endpoints] 记抓包标记失败 {}: {}", endpointId, e.getMessage());
        }
    }

    /**
     * 记下这份抓包样本是哪个环境的（TEST / PROD）。
     * <p>
     * 每次挂样本都要写，不能只在 PROD 时写：样本会被后一次抓包覆盖，
     * 先导入过生产、后来又在测试环境抓了一次，标记还停在 PROD 就会生成错环境的用例。
     */
    public void markCaptureEnv(String endpointId, String env) {
        if (endpointId == null || endpointId.isBlank()) {
            return;
        }
        try {
            ApiEndpoint row = endpointMapper.findById(endpointId);
            if (row == null) {
                return;
            }
            Map<String, Object> meta = new java.util.LinkedHashMap<>(captureMetaOf(row));
            String value = env == null || env.isBlank() ? "TEST" : env.trim().toUpperCase(Locale.ROOT);
            if (value.equals(meta.get("captureEnv"))) {
                return;
            }
            meta.put("captureEnv", value);
            row.setCaptureMeta(EndpointAssertionContext.metaJson(meta));
            endpointMapper.update(row);
        } catch (Exception e) {
            log.debug("[api-endpoints] 记抓包环境失败 {}: {}", endpointId, e.getMessage());
        }
    }

    /** 同步进来的一条接口变化 */
    public record EndpointChange(String httpMethod, String path, String handler, String summary,
                                 String file, String changeType, String changeFlag, String changeNote,
                                 List<String> responseFields, List<String> tables) {
    }

    public record SyncResult(int created, int updated, int deprecated) {
    }
}
