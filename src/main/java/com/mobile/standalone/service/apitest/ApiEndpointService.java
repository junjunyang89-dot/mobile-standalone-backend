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

    /** 解析接口 handler，供用例集合等模块展示 Controller 名称 */
    public String handlerOfEndpoint(String endpointId) {
        ApiEndpoint ep = get(endpointId);
        return ep != null ? ep.getHandler() : null;
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
    /**
     * 按分支汇总：对比分析出来的接口挂在分支上，那是测试环境树的另一个根。
     * <p>
     * 分支和单号是两个入口，不是两级：一个分支上合了好几个单，
     * 一个单也可能跨分支。硬做成父子，总有一边对不上。
     */
    public List<java.util.Map<String, Object>> summaryByBranch() {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT source_branch AS branch, project,
                           COUNT(*) AS total,
                           SUM(CASE WHEN change_flag IS NOT NULL AND change_flag <> '' THEN 1 ELSE 0 END) AS changed
                      FROM api_endpoints
                     WHERE source_branch IS NOT NULL AND source_branch <> ''
                     GROUP BY source_branch, project
                     ORDER BY total DESC
                    """);
        } catch (Exception e) {
            log.debug("[api-endpoints] 按分支汇总失败: {}", e.getMessage());
            return java.util.List.of();
        }
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
        if (sample.fieldNames().isEmpty() && sample.topLevelSamples().isEmpty()) {
            return;
        }

        List<String> merged = mergeFieldNames(responseFieldsOf(row), sample.fieldNames());
        if (!merged.isEmpty()) {
            row.setFieldsSnapshot(fieldsJson(merged));
        }

        Map<String, Object> capMeta = new java.util.LinkedHashMap<>();
        capMeta.put("capturedAt", java.time.LocalDateTime.now().toString());
        capMeta.put("fields", sample.fieldNames());
        capMeta.put("samples", sample.topLevelSamples());
        if (sample.reqPreview() != null) {
            capMeta.put("reqPreview", sample.reqPreview());
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
