package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.entity.UiFlow;
import java.time.LocalDateTime;
import com.mobile.standalone.mapper.AiTestCaseMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 生成用例文档：落库 / 列表 / 复核状态。与自动化 TestCaseService 分离。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiTestCaseService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AUTOMATION_ADVICE = Set.of(
            "RECOMMENDED", "PARTIAL", "NOT_RECOMMENDED");
    private static final Set<String> RELEASE_STAGES = Set.of("TESTING", "ONLINE");

    private final AiTestCaseMapper aiTestCaseMapper;
    private final UiFlowService uiFlowService;
    private final com.mobile.standalone.mapper.TaskMapper taskMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchemaColumns() {
        if (jdbcTemplate == null) {
            return;
        }
        ensureColumn("automation_advice", """
                ALTER TABLE ai_test_cases
                    ADD COLUMN automation_advice VARCHAR(32) NULL
                        COMMENT '自动化建议：RECOMMENDED|PARTIAL|NOT_RECOMMENDED，空＝未评估'
                        AFTER flow_id
                """, "idx_automation_advice");
        ensureColumn("release_stage", """
                ALTER TABLE ai_test_cases
                    ADD COLUMN release_stage VARCHAR(16) NULL
                        COMMENT '发布轨：TESTING|ONLINE'
                        AFTER automation_advice
                """, "idx_release_stage");
        ensureColumn("linked_at", """
                ALTER TABLE ai_test_cases
                    ADD COLUMN linked_at DATETIME NULL
                        COMMENT '关联流程的时刻。用例内容后于它被改动 = 流程可能已经跟不上用例'
                        AFTER flow_id
                """, null);
        ensureColumn("content_updated_at", """
                ALTER TABLE ai_test_cases
                    ADD COLUMN content_updated_at DATETIME NULL
                        COMMENT '用例实质内容（步骤/预期/前置/测试点/标题）最后一次改动时刻'
                        AFTER linked_at
                """, null);
        // tasks 的两列：原来靠 config LIKE 反查 flowId，是全表扫；顺带记下这次执行属于哪条轨
        // 一台手机上装的就是某个环境的包，环境绑在设备上而不是每次执行时选
        ensureTableColumn("devices", "environment",
                "VARCHAR(16) NULL COMMENT '这台设备跑哪个环境 PROD/TEST'");
        ensureTableColumn("online_regression_accounts", "environment",
                "VARCHAR(16) NULL COMMENT '账号属于哪个环境，空=通用'");
        ensureTaskColumn("flow_id", "VARCHAR(64) NULL COMMENT '从 config 抽出，便于按流程反查'");
        ensureTaskColumn("run_track", "VARCHAR(16) NULL COMMENT 'AUTOMATION|REGRESSION|PERFORMANCE'");
        try {
            jdbcTemplate.execute("ALTER TABLE tasks ADD INDEX idx_flow_track (flow_id, run_track)");
        } catch (Exception ignored) {
            // 索引已存在
        }
        try {
            int n = jdbcTemplate.update(
                    "UPDATE ai_test_cases SET release_stage = 'ONLINE' WHERE release_stage IS NULL OR release_stage = ''");
            if (n > 0) {
                log.info("[ai-test-cases] 存量用例已标为 ONLINE：{} 条", n);
            }
        } catch (Exception e) {
            log.debug("[ai-test-cases] 存量 ONLINE 回填跳过: {}", e.getMessage());
        }
    }

    private void ensureTaskColumn(String column, String definition) {
        ensureTableColumn("tasks", column, definition);
    }

    /** 列不存在才加。靠人记着去跑 migration.sql 是不行的——漏跑一次就是启动即报「Unknown column」 */
    private void ensureTableColumn(String table, String column, String definition) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("[schema] 已补充 {}.{} 列", table, column);
            }
        } catch (Exception e) {
            log.warn("[schema] {}.{} 列检查失败: {}", table, column, e.getMessage());
        }
    }

    private void ensureColumn(String column, String alterSql, String indexName) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(1) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'ai_test_cases'
                              AND COLUMN_NAME = ?
                            """,
                    Integer.class, column);
            if (n != null && n == 0) {
                jdbcTemplate.execute(alterSql);
                if (indexName != null && !indexName.isBlank()) {
                    try {
                        jdbcTemplate.execute(
                                "ALTER TABLE ai_test_cases ADD INDEX " + indexName + " (" + column + ")");
                    } catch (Exception ignored) {
                        // 索引已存在
                    }
                }
                log.info("[ai-test-cases] 已补充 {} 列", column);
            }
        } catch (Exception e) {
            log.warn("[ai-test-cases] {} 列检查失败: {}", column, e.getMessage());
        }
    }

    public AiTestCase getById(Long id) {
        return id == null ? null : aiTestCaseMapper.findById(id);
    }

    /**
     * 手工新建一条用例。
     * <p>
     * 跟 {@link #saveBatch} 的区别只在来源：那条是生成器批量落库，这条是人一条条录。
     * 同落一张表，靠 {@code source} 区分，免得同一批用例散在两处对不上。
     */
    @Transactional
    public AiTestCase createManual(AiTestCase row) {
        if (row == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        if (isBlank(row.getTitle())) {
            throw new IllegalArgumentException("用例标题不能为空");
        }
        if (isBlank(row.getCategory())) {
            throw new IllegalArgumentException("用例分类不能为空");
        }
        normalize(row);
        row.setSource("MANUAL");
        if (isBlank(row.getStatus())) {
            row.setStatus("DRAFT");
        }
        if (isBlank(row.getReleaseStage())) {
            row.setReleaseStage("TESTING");
        }
        if (isBlank(row.getCaseId())) {
            row.setCaseId(nextCaseId(row.getCategory()));
        }
        LocalDateTime now = LocalDateTime.now();
        row.setContentUpdatedAt(now);
        if (!isBlank(row.getFlowId())) {
            validatePlatformMatch(row.getFlowId(), row.getPlatform());
            row.setLinkedAt(now);
        }
        aiTestCaseMapper.insert(row);
        return aiTestCaseMapper.findById(row.getId());
    }

    /** 更新：只覆盖请求里给了的字段，没给的保持原样，免得前端漏传一个字段就把内容清空 */
    @Transactional
    public AiTestCase updateManual(Long id, AiTestCase patch) {
        AiTestCase existing = getById(id);
        if (existing == null) {
            return null;
        }
        if (patch == null) {
            return existing;
        }
        String before = contentSignature(existing);
        if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
        if (patch.getModule() != null) existing.setModule(patch.getModule());
        if (patch.getSubModule() != null) existing.setSubModule(patch.getSubModule());
        if (patch.getTestItem() != null) existing.setTestItem(patch.getTestItem());
        if (patch.getTestPoint() != null) existing.setTestPoint(patch.getTestPoint());
        if (patch.getPageName() != null) existing.setPageName(patch.getPageName());
        if (patch.getPriority() != null) existing.setPriority(patch.getPriority());
        if (patch.getType() != null) existing.setType(patch.getType());
        if (patch.getPrecondition() != null) existing.setPrecondition(patch.getPrecondition());
        if (patch.getStepsJson() != null) existing.setStepsJson(patch.getStepsJson());
        if (patch.getExpectedResult() != null) existing.setExpectedResult(patch.getExpectedResult());
        if (patch.getRemark() != null) existing.setRemark(patch.getRemark());
        if (patch.getPlatform() != null) existing.setPlatform(patch.getPlatform());
        if (patch.getAppId() != null) existing.setAppId(patch.getAppId());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        if (patch.getScreenshotUrl() != null) existing.setScreenshotUrl(patch.getScreenshotUrl());
        // 实质内容改了才动 content_updated_at：改备注、改优先级不算。
        // 拿 updated_at 判过期会天天误报，误报几次之后这个标记就没人看了
        if (contentChanged(before, existing)) {
            existing.setContentUpdatedAt(LocalDateTime.now());
        }
        // 空串表示「解除关联」，null 表示「这次不改」——两者不能混
        if (patch.getFlowId() != null) {
            String next = patch.getFlowId().isBlank() ? null : patch.getFlowId().trim();
            if (next != null && !next.equals(existing.getFlowId())) {
                validatePlatformMatch(next, existing.getPlatform());
                // 重新关联＝确认过流程跟得上当前用例，过期标记从这一刻重新计
                existing.setLinkedAt(LocalDateTime.now());
            } else if (next == null) {
                existing.setLinkedAt(null);
            }
            existing.setFlowId(next);
        }
        // 空串＝清空为「未评估」；null＝本次不改
        if (patch.getAutomationAdvice() != null) {
            existing.setAutomationAdvice(patch.getAutomationAdvice().isBlank()
                    ? null
                    : patch.getAutomationAdvice().trim().toUpperCase());
        }
        if (patch.getReleaseStage() != null) {
            existing.setReleaseStage(normalizeStage(patch.getReleaseStage()));
        }
        normalize(existing);
        aiTestCaseMapper.update(existing);
        return aiTestCaseMapper.findById(id);
    }

    /**
     * 表格导入：逐条落库。
     * <p>
     * 单条失败不该让整批回不来——一份几十行的表里有一行标题为空，全批打回去让人自己找
     * 是最难受的。所以逐条 try，最后一起报哪几行没进来。
     */
    @Transactional
    public Map<String, Object> importRows(String category, List<AiTestCase> rows) {
        if (isBlank(category)) {
            throw new IllegalArgumentException("请指定用例分类");
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的数据");
        }
        List<AiTestCase> saved = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            AiTestCase row = rows.get(i);
            try {
                row.setCategory(category);
                // 表里给了编号就用表里的，人对得上；没给才自动编
                saved.add(createManual(row));
            } catch (Exception e) {
                failed.add("第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", saved.size());
        out.put("failed", failed);
        out.put("cases", saved.stream().map(this::toView).toList());
        return out;
    }

    private void normalize(AiTestCase row) {
        row.setTitle(trimToNull(row.getTitle()));
        row.setModule(trimToNull(row.getModule()));
        row.setSubModule(trimToNull(row.getSubModule()));
        row.setTestItem(trimToNull(row.getTestItem()));
        row.setTestPoint(trimToNull(row.getTestPoint()));
        row.setPageName(trimToNull(row.getPageName()));
        row.setFlowId(trimToNull(row.getFlowId()));
        row.setAutomationAdvice(normalizeAdvice(row.getAutomationAdvice()));
        if (!isBlank(row.getReleaseStage())) {
            row.setReleaseStage(normalizeStage(row.getReleaseStage()));
        }
        if (isBlank(row.getStepsJson())) {
            row.setStepsJson("[]");
        }
        if (isBlank(row.getPriority())) {
            row.setPriority("P1");
        }
        // page_name 在建表时是 NOT NULL，手工录入未必填页面，退回模块名兜底
        if (row.getPageName() == null) {
            row.setPageName(row.getModule() != null ? row.getModule() : "未分类");
        }
    }

    /** 同分类下顺次编号，如 TC-007；人工编号和生成器编号不该互相踩 */
    private String nextCaseId(String category) {
        int n = aiTestCaseMapper.countByFilters(category, null, null, null, null, null, null, null, null);
        return String.format("TC-%03d", n + 1);
    }

    private static String normalizeAdvice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        if (!AUTOMATION_ADVICE.contains(v)) {
            throw new IllegalArgumentException(
                    "automationAdvice 仅支持 RECOMMENDED / PARTIAL / NOT_RECOMMENDED");
        }
        return v;
    }

    private static String normalizeStage(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("releaseStage 不能为空");
        }
        String v = raw.trim().toUpperCase();
        if (!RELEASE_STAGES.contains(v)) {
            throw new IllegalArgumentException("releaseStage 仅支持 TESTING / ONLINE");
        }
        return v;
    }

    /** 单条提升为线上稳定 */
    @Transactional
    public AiTestCase promote(Long id) {
        AiTestCase row = getById(id);
        if (row == null) {
            return null;
        }
        row.setReleaseStage("ONLINE");
        aiTestCaseMapper.update(row);
        return aiTestCaseMapper.findById(id);
    }

    /** 批量提升为线上稳定 */
    @Transactional
    public Map<String, Object> promoteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            try {
                AiTestCase updated = promote(id);
                if (updated == null) {
                    failed.add(String.valueOf(id) + "：不存在");
                } else {
                    ok++;
                }
            } catch (Exception e) {
                failed.add(id + "：" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promoted", ok);
        out.put("failed", failed);
        return out;
    }

    /**
     * 这条用例关联的流程最近一次执行。
     * <p>
     * flowId 存在 tasks.config 里；单跑和批量执行都会建 Task，查这一张就覆盖两种入口。
     */
    // ---------- 从流程侧关联用例 ----------

    /** 挂在这条流程上的用例 */
    public List<Map<String, Object>> casesOfFlow(String flowId) {
        if (isBlank(flowId)) {
            return List.of();
        }
        return aiTestCaseMapper.findByFlowId(flowId).stream().map(this::toView).toList();
    }

    /** 建流程时的候选用例：同平台、且没被别的流程占用 */
    public List<Map<String, Object>> linkableCases(String flowId, String platform, String keyword) {
        return aiTestCaseMapper.findLinkable(
                        isBlank(flowId) ? "__none__" : flowId, platform, keyword)
                .stream().map(this::toView).toList();
    }

    /**
     * 把这条流程覆盖的用例设成给定的这批：不在列表里的解除关联，新加的打上关联时间。
     * <p>
     * 真相只有一处（用例上的 flow_id），这里只是换了个编辑入口——
     * 不在流程上再存一份，那样两边就能不一致。
     *
     * @return 关联数 → 解除数
     */
    @Transactional
    public Map<String, Integer> setFlowCases(String flowId, List<Long> caseIds) {
        if (isBlank(flowId)) {
            throw new IllegalArgumentException("flowId 不能为空");
        }
        List<Long> want = caseIds == null ? List.of() : caseIds;
        LocalDateTime now = LocalDateTime.now();
        int linked = 0;
        int unlinked = 0;

        for (AiTestCase c : aiTestCaseMapper.findByFlowId(flowId)) {
            if (!want.contains(c.getId())) {
                c.setFlowId(null);
                c.setLinkedAt(null);
                aiTestCaseMapper.update(c);
                unlinked++;
            }
        }
        for (Long id : want) {
            AiTestCase c = getById(id);
            if (c == null || flowId.equals(c.getFlowId())) {
                continue;
            }
            validatePlatformMatch(flowId, c.getPlatform());
            c.setFlowId(flowId);
            c.setLinkedAt(now);
            aiTestCaseMapper.update(c);
            linked++;
        }
        return Map.of("linked", linked, "unlinked", unlinked);
    }

    /**
     * 用例「实质内容」的指纹。
     * <p>
     * 只取会影响流程该怎么写的字段：标题变了、步骤变了、预期结果变了，流程就可能跟不上了；
     * 备注、优先级、负责人变了跟流程无关。
     */
    private static String contentSignature(AiTestCase c) {
        return String.join("\u0000",
                nz(c.getTitle()), nz(c.getPrecondition()), nz(c.getStepsJson()),
                nz(c.getExpectedResult()), nz(c.getTestPoint()));
    }

    private boolean contentChanged(String before, AiTestCase after) {
        return before != null && !before.equals(contentSignature(after));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 安卓用例不能挂 iOS 流程。
     * <p>
     * 两端用例是两拨人分开写的两套，一条用例只属于一端。挂错了不会报错，
     * 只会永远显示「未执行」——查起来完全没有线索。
     */
    void validatePlatformMatch(String flowId, String casePlatform) {
        if (uiFlowService == null || isBlank(flowId)) {
            return;
        }
        UiFlow flow = uiFlowService.get(flowId);
        if (flow == null) {
            return;
        }
        if (!automationCapable(flow)) {
            throw new IllegalArgumentException(
                    "流程「" + flow.getName() + "」的用途是「" + catalogLabel(flow.getCatalog())
                            + "」，不能用来覆盖用例。回归和自动化是两条独立的轨——"
                            + "回归验的是「这个包能不能用」，不对应任何一条用例。"
                            + "要让它覆盖用例，把用途改成「仅自动化编排」");
        }
        if (isBlank(casePlatform) || isBlank(flow.getPlatform())) {
            return;
        }
        if (!flow.getPlatform().equalsIgnoreCase(casePlatform.trim())) {
            throw new IllegalArgumentException(
                    "平台不一致：用例是 " + casePlatform + "，流程「" + flow.getName()
                            + "」是 " + flow.getPlatform() + "。两端用例是分开的两套，别互相关联");
        }
    }

    /**
     * 只有<b>用途＝仅自动化编排</b>的流程才算覆盖了用例。
     * <p>
     * 回归和自动化是两条互不相干的轨：回归验的是「这个包能不能用」，
     * 自动化替代的是「人工执行某条用例」。所以 SHARED（回归 + 自动化编排）也不行——
     * 它进回归批次，就属于回归那一套；其中的「自动化编排」只是能在本页单跑调试，
     * 不代表它是某条用例的自动化实现。
     * <p>
     * 挂错了不会报错，只会得到一个「已覆盖却从来没跑过」的绿标记——
     * 因为用例的执行状态只认 AUTOMATION 轨。那正是覆盖率造假最典型的形态。
     */
    static boolean automationCapable(UiFlow flow) {
        return flow != null && "AUTOMATION".equals(flow.getCatalog());
    }

    private static String catalogLabel(String c) {
        if ("AUTOMATION".equals(c)) return "仅自动化编排";
        if ("ONLINE".equals(c)) return "仅回归";
        if ("PERFORMANCE".equals(c)) return "仅性能测试";
        if ("SHARED".equals(c)) return "回归 + 自动化编排";
        return c == null ? "未设置" : c;
    }

    private Map<String, Object> lastRunOf(String flowId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastRunStatus", null);
        m.put("lastRunAt", null);
        m.put("lastRunTaskId", null);
        if (isBlank(flowId) || taskMapper == null) {
            return m;
        }
        try {
            com.mobile.standalone.entity.Task t = taskMapper.findLatestByFlowId(flowId);
            if (t == null) {
                return m;
            }
            m.put("lastRunStatus", t.getStatus() == null ? null : t.getStatus().name());
            m.put("lastRunAt", t.getUpdatedAt() != null ? t.getUpdatedAt() : t.getCreatedAt());
            m.put("lastRunTaskId", t.getId());
        } catch (Exception e) {
            // 查不到执行记录不该让整份用例列表失败
            log.debug("查最近执行失败 flowId={}: {}", flowId, e.getMessage());
        }
        return m;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    public Map<String, Object> list(String category, String pageName, String priority, String status, String platform,
                                    String batchId, String automationAdvice, String releaseStage,
                                    String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        int offset = (p - 1) * s;
        String advice = null;
        if (automationAdvice != null && !automationAdvice.isBlank()) {
            advice = normalizeAdvice(automationAdvice);
        }
        String stage = null;
        if (releaseStage != null && !releaseStage.isBlank()) {
            stage = normalizeStage(releaseStage);
        }
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<AiTestCase> content = aiTestCaseMapper.findByFilters(
                category, pageName, priority, status, platform, batchId, advice, stage, kw, offset, s);
        int total = aiTestCaseMapper.countByFilters(
                category, pageName, priority, status, platform, batchId, advice, stage, kw);
        // 两个版本页签上的计数不跟着搜索走：搜「验证码」时页签显示的应该是
        // 「线上版本一共 24 条」，而不是「符合搜索的有 3 条」——
        // 页签是导航，跟着筛选变会让人以为用例被删了
        int onlineCount = aiTestCaseMapper.countByFilters(
                category, null, null, null, null, null, null, "ONLINE", null);
        int testingCount = aiTestCaseMapper.countByFilters(
                category, null, null, null, null, null, null, "TESTING", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("totalElements", total);
        out.put("page", p);
        out.put("size", s);
        out.put("totalPages", s == 0 ? 0 : (total + s - 1) / s);
        out.put("onlineCount", onlineCount);
        out.put("testingCount", testingCount);
        return out;
    }

    /**
     * 批量保存生成器返回的用例。
     * body 可含 category、page_name、platform、screenshot_url、app_id、status、test_cases[]。
     */
    @Transactional
    public Map<String, Object> saveBatch(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Object casesObj = body.get("test_cases");
        if (casesObj == null) {
            casesObj = body.get("testCases");
        }
        if (!(casesObj instanceof List<?> cases) || cases.isEmpty()) {
            throw new IllegalArgumentException("test_cases 不能为空");
        }

        String pageName = str(body.get("page_name"));
        if (pageName == null) pageName = str(body.get("pageName"));
        if (pageName == null) pageName = str(body.get("page"));
        if (pageName == null || pageName.isBlank()) {
            pageName = "未命名页面";
        }

        String category = firstNonBlank(str(body.get("category")), str(body.get("suite")));
        String platform = str(body.get("platform"));
        String screenshotUrl = firstNonBlank(str(body.get("screenshot_url")), str(body.get("screenshotUrl")));
        String appId = firstNonBlank(str(body.get("app_id")), str(body.get("appId")));
        String status = firstNonBlank(str(body.get("status")), "DRAFT");
        String batchReleaseStage = firstNonBlank(
                str(body.get("release_stage")), str(body.get("releaseStage")), "TESTING");
        String createdBy = str(body.get("created_by"));
        // 批次级层级：一张截图基本就是一个页面/模块，这一批用例共享同一层级。
        // 生成器只产出「该测什么」（它擅长），归类由人填一次（它不擅长，而且逐条补最耗人）。
        String batchModule = firstNonBlank(str(body.get("module")), str(body.get("moduleName")));
        String batchSubModule = firstNonBlank(str(body.get("sub_module")), str(body.get("subModule")));
        String batchTestItem = firstNonBlank(str(body.get("test_item")), str(body.get("testItem")));
        String batchId = firstNonBlank(str(body.get("batch_id")), str(body.get("batchId")), UUID.randomUUID().toString().replace("-", ""));

        List<AiTestCase> saved = new ArrayList<>();
        int idx = 1;
        for (Object raw : cases) {
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            AiTestCase row = new AiTestCase();
            String caseId = firstNonBlank(str(m.get("case_id")), str(m.get("caseId")));
            if (caseId == null || caseId.isBlank()) {
                caseId = String.format("TC-%03d", idx);
            }
            String title = firstNonBlank(str(m.get("title")), str(m.get("name")));
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("用例缺少 title: " + caseId);
            }
            Object steps = m.get("steps");
            if (steps == null) {
                steps = m.get("steps_json");
            }
            if (steps == null) {
                throw new IllegalArgumentException("用例缺少 steps: " + caseId);
            }
            String stepsJson;
            try {
                stepsJson = steps instanceof String s ? s : JSON.writeValueAsString(steps);
                // 校验可解析
                JSON.readTree(stepsJson);
            } catch (Exception e) {
                throw new IllegalArgumentException("steps 不是合法 JSON: " + caseId);
            }

            row.setCaseId(caseId.trim());
            row.setPageName(firstNonBlank(str(m.get("page_name")), str(m.get("pageName")), pageName));
            row.setCategory(firstNonBlank(str(m.get("category")), category));
            row.setTitle(title.trim());
            row.setPriority(firstNonBlank(str(m.get("priority")), "P1"));
            row.setType(firstNonBlank(str(m.get("type")), "功能测试"));
            row.setPrecondition(str(m.get("precondition")));
            row.setStepsJson(stepsJson);
            // 生成器要是自己给了层级就用它的，否则落到批次级默认值
            row.setModule(firstNonBlank(str(m.get("module")), batchModule));
            row.setSubModule(firstNonBlank(str(m.get("sub_module")), str(m.get("subModule")), batchSubModule));
            row.setTestItem(firstNonBlank(str(m.get("test_item")), str(m.get("testItem")), batchTestItem));
            // 测试点用标题兜底，跟表格导入保持同一套规则
            row.setTestPoint(firstNonBlank(
                    str(m.get("test_point")), str(m.get("testPoint")), title.trim()));
            row.setExpectedResult(firstNonBlank(
                    str(m.get("expected_result")), str(m.get("expectedResult")), str(m.get("expected"))));
            row.setRemark(str(m.get("remark")));
            row.setScreenshotUrl(firstNonBlank(str(m.get("screenshot_url")), str(m.get("screenshotUrl")), screenshotUrl));
            row.setPlatform(platform);
            row.setAppId(appId);
            row.setSource("AI");
            row.setStatus(status);
            row.setReleaseStage(firstNonBlank(
                    str(m.get("release_stage")), str(m.get("releaseStage")), batchReleaseStage));
            row.setBatchId(batchId);
            row.setCreatedBy(createdBy);

            normalize(row);
            if (isBlank(row.getReleaseStage())) {
                row.setReleaseStage("TESTING");
            }

            aiTestCaseMapper.insert(row);
            saved.add(row);
            idx++;
        }

        if (saved.isEmpty()) {
            throw new IllegalArgumentException("没有可保存的用例");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("pageName", pageName);
        out.put("category", category);
        out.put("saved", saved.size());
        out.put("cases", saved);
        return out;
    }

    @Transactional
    public AiTestCase updateStatus(Long id, String status) {
        AiTestCase row = aiTestCaseMapper.findById(id);
        if (row == null) return null;
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status 不能为空");
        }
        String s = status.trim().toUpperCase();
        if (!List.of("DRAFT", "REVIEWED", "ARCHIVED").contains(s)) {
            throw new IllegalArgumentException("status 仅支持 DRAFT / REVIEWED / ARCHIVED");
        }
        row.setStatus(s);
        aiTestCaseMapper.update(row);
        return row;
    }

    @Transactional
    public boolean delete(Long id) {
        if (id == null) return false;
        return aiTestCaseMapper.deleteById(id) > 0;
    }

    /** 列表展示：把 steps_json 解析为数组，便于前端直接渲染表格 */
    public Map<String, Object> toView(AiTestCase row) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (row == null) return m;
        m.put("id", row.getId());
        m.put("caseId", row.getCaseId());
        m.put("pageName", row.getPageName());
        m.put("category", row.getCategory());
        m.put("title", row.getTitle());
        m.put("priority", row.getPriority());
        m.put("type", row.getType());
        m.put("precondition", row.getPrecondition());
        m.put("screenshotUrl", row.getScreenshotUrl());
        m.put("platform", row.getPlatform());
        m.put("appId", row.getAppId());
        m.put("source", row.getSource());
        m.put("status", row.getStatus());
        m.put("batchId", row.getBatchId());
        m.put("module", row.getModule());
        m.put("subModule", row.getSubModule());
        m.put("testItem", row.getTestItem());
        m.put("testPoint", row.getTestPoint());
        m.put("expectedResult", row.getExpectedResult());
        m.put("remark", row.getRemark());
        m.put("flowId", row.getFlowId());
        m.put("automationAdvice", row.getAutomationAdvice());
        m.put("releaseStage", row.getReleaseStage());
        // 「是否已实现自动化」是算出来的，不是存的：关联的流程被停用或删掉，这里自动变回未覆盖
        UiFlow flow = row.getFlowId() == null ? null : uiFlowService.get(row.getFlowId());
        // 仅回归 / 仅性能的流程不算覆盖：它永远不会在自动化轨上执行，
        // 挂上去会得到一个「已覆盖却从来没跑过」的绿标记。存量的错误关联也靠这一句自动纠正
        boolean automated = flow != null && !Boolean.FALSE.equals(flow.getEnabled())
                && automationCapable(flow);
        m.put("flowNotAutomation", flow != null && !automationCapable(flow));
        m.put("automated", automated);
        m.put("flowName", flow != null ? flow.getName() : null);
        m.put("flowMissing", row.getFlowId() != null && flow == null);
        // 关联之后用例内容又被改过 = 流程可能还在验旧需求。
        // 这是覆盖率造假里最隐蔽的一种：标记是绿的，覆盖的却是上一版的期望结果。
        // 算出来而不是让人维护——人手标记一定会过期，跟「已自动化」是同一个道理
        boolean stale = automated
                && row.getLinkedAt() != null
                && row.getContentUpdatedAt() != null
                && row.getContentUpdatedAt().isAfter(row.getLinkedAt());
        m.put("flowStale", stale);
        m.put("linkedAt", row.getLinkedAt());
        m.put("contentUpdatedAt", row.getContentUpdatedAt());
        // 「已自动化」只说明挂了流程；测试真正想知道的是「最近跑过没有、跑没跑过」。
        // 挂了流程但从没跑过，跟没做自动化的实际效果是一样的
        m.putAll(lastRunOf(row.getFlowId()));
        m.put("createdAt", row.getCreatedAt());
        m.put("updatedAt", row.getUpdatedAt());
        try {
            JsonNode steps = JSON.readTree(row.getStepsJson() != null ? row.getStepsJson() : "[]");
            m.put("steps", steps);
        } catch (Exception e) {
            m.put("steps", List.of());
            m.put("stepsJson", row.getStepsJson());
        }
        return m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
