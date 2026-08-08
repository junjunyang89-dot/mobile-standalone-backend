package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.AiTestCase;
import com.mobile.standalone.entity.UiFlow;
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

    private final AiTestCaseMapper aiTestCaseMapper;
    private final UiFlowService uiFlowService;
    private final com.mobile.standalone.mapper.TaskMapper taskMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureAutomationAdviceColumn() {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(1) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'ai_test_cases'
                              AND COLUMN_NAME = 'automation_advice'
                            """,
                    Integer.class);
            if (n != null && n == 0) {
                jdbcTemplate.execute("""
                        ALTER TABLE ai_test_cases
                            ADD COLUMN automation_advice VARCHAR(32) NULL
                                COMMENT '自动化建议：RECOMMENDED|PARTIAL|NOT_RECOMMENDED，空＝未评估'
                                AFTER flow_id
                        """);
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE ai_test_cases ADD INDEX idx_automation_advice (automation_advice)");
                } catch (Exception ignored) {
                    // 索引已存在则忽略
                }
                log.info("[ai-test-cases] 已补充 automation_advice 列");
            }
        } catch (Exception e) {
            log.warn("[ai-test-cases] automation_advice 列检查失败: {}", e.getMessage());
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
        if (isBlank(row.getCaseId())) {
            row.setCaseId(nextCaseId(row.getCategory()));
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
        // 空串表示「解除关联」，null 表示「这次不改」——两者不能混
        if (patch.getFlowId() != null) {
            existing.setFlowId(patch.getFlowId().isBlank() ? null : patch.getFlowId().trim());
        }
        // 空串＝清空为「未评估」；null＝本次不改
        if (patch.getAutomationAdvice() != null) {
            existing.setAutomationAdvice(patch.getAutomationAdvice().isBlank()
                    ? null
                    : patch.getAutomationAdvice().trim().toUpperCase());
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
        int n = aiTestCaseMapper.countByFilters(category, null, null, null, null, null, null);
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

    /**
     * 这条用例关联的流程最近一次执行。
     * <p>
     * flowId 存在 tasks.config 里；单跑和批量执行都会建 Task，查这一张就覆盖两种入口。
     */
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
                                    String batchId, String automationAdvice, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        int offset = (p - 1) * s;
        String advice = null;
        if (automationAdvice != null && !automationAdvice.isBlank()) {
            advice = normalizeAdvice(automationAdvice);
        }
        List<AiTestCase> content = aiTestCaseMapper.findByFilters(
                category, pageName, priority, status, platform, batchId, advice, offset, s);
        int total = aiTestCaseMapper.countByFilters(
                category, pageName, priority, status, platform, batchId, advice);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("totalElements", total);
        out.put("page", p);
        out.put("size", s);
        out.put("totalPages", s == 0 ? 0 : (total + s - 1) / s);
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
            row.setBatchId(batchId);
            row.setCreatedBy(createdBy);

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
        // 「是否已实现自动化」是算出来的，不是存的：关联的流程被停用或删掉，这里自动变回未覆盖
        UiFlow flow = row.getFlowId() == null ? null : uiFlowService.get(row.getFlowId());
        boolean automated = flow != null && !Boolean.FALSE.equals(flow.getEnabled());
        m.put("automated", automated);
        m.put("flowName", flow != null ? flow.getName() : null);
        m.put("flowMissing", row.getFlowId() != null && flow == null);
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
