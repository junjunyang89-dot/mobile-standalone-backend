package com.mobile.standalone.service;

import com.mobile.standalone.entity.EngineeringMemory;
import com.mobile.standalone.mapper.EngineeringMemoryMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工程记忆时间线：存决策/约定摘要，供后续 AI 调优检索。
 * 完整对话请用 {@link AgentStorageService} 的 agent_conversations。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineeringMemoryService {

    private static final Set<String> TYPES = Set.of(
            "DECISION", "CONVENTION", "CHANGE", "PITFALL", "CONTEXT"
    );

    private final EngineeringMemoryMapper memoryMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS project_engineering_memory (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        occurred_at DATETIME NOT NULL,
                        mem_type VARCHAR(32) NOT NULL,
                        title VARCHAR(255) NOT NULL,
                        body MEDIUMTEXT NOT NULL,
                        tags VARCHAR(512) DEFAULT NULL,
                        source VARCHAR(64) DEFAULT 'manual',
                        session_ref VARCHAR(128) DEFAULT NULL,
                        created_by VARCHAR(100) DEFAULT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_occurred (occurred_at),
                        INDEX idx_type (mem_type),
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[engineering-memory] 表已就绪 project_engineering_memory");
            seedIfEmpty();
        } catch (Exception e) {
            log.warn("[engineering-memory] 建表/种子失败（可手动执行 schema_engineering_memory.sql）: {}", e.getMessage());
        }
    }

    private void seedIfEmpty() {
        if (memoryMapper.countAll() > 0) return;
        LocalDateTime t = LocalDateTime.of(2026, 8, 2, 6, 0);
        seed(t, "DECISION", "线上回归统一到流程编排",
                "APP UI 自动化唯一真相为 ui_flows。回归页只做勾选与调度，不再维护功能1～7 硬编码。"
                        + "启动请求使用 flowIds；执行走 UiFlowExecutor / UI_FLOW_TEST。",
                "ui-flows,online-regression", "seed");
        seed(t.plusMinutes(5), "CONVENTION", "catalog / 组件 / 启用约定",
                "ONLINE 或 SHARED 且 enabled=true 且 is_component=false 才出现在线上回归（UI 功能）。"
                        + "AUTOMATION 仅 UI 测试编排；PERFORMANCE 仅 App 性能测试，与功能流程隔离。"
                        + "安卓性能测试下拉只列出 catalog=PERFORMANCE。"
                        + "冷启动 COLD_START / 热启动 HOT_START 为性能流程关键字。"
                        + "sort_order 控制展示与默认执行顺序。",
                "ui-flows,online-regression,catalog,performance,cold-start,hot-start", "seed");
        seed(t.plusMinutes(10), "CONVENTION", "流程宜短：一流程一验证点",
                "验证型自动化流程保持短小专一；前置条件外置（账号/CALL_COMPONENT）。"
                        + "装包卸包用关键字 UNINSTALL_APP / INSTALL_FROM_STORE / LAUNCH_APP 编排进流程，不在回归页硬编码。",
                "ui-flows,automation-style", "seed");
        seed(t.plusMinutes(15), "CHANGE", "装包关键字与种子流程",
                "新增 UNINSTALL_APP、INSTALL_FROM_STORE。"
                        + "种子：flow-login-logout-android、flow-install-verify-android（启用）；"
                        + "浏览类 flow-browse-* 默认禁用。详见 docs/线上回归与流程编排.md。",
                "ui-flows,install,seed", "seed");
        seed(t.plusMinutes(20), "CONTEXT", "工程记忆分层存储策略",
                "1) project_engineering_memory：时间线摘要（本表）。"
                        + "2) agent_conversations：需要时存整段对话 JSON。"
                        + "3) docs/：原则与设计长文。调优时优先检索本表 + 文档，勿依赖完整闲聊。",
                "engineering-memory,architecture", "seed");
        log.info("[engineering-memory] 已写入种子记忆");
    }

    private void seed(LocalDateTime when, String type, String title, String body, String tags, String source) {
        EngineeringMemory m = new EngineeringMemory();
        m.setOccurredAt(when);
        m.setMemType(type);
        m.setTitle(title);
        m.setBody(body);
        m.setTags(tags);
        m.setSource(source);
        m.setCreatedBy("system");
        m.setCreatedAt(LocalDateTime.now());
        memoryMapper.insert(m);
    }

    public List<EngineeringMemory> list(String memType, String tag, String q, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        String type = blankToNull(memType);
        if (type != null) type = type.trim().toUpperCase(Locale.ROOT);
        return memoryMapper.search(type, blankToNull(tag), blankToNull(q), lim);
    }

    public EngineeringMemory get(Long id) {
        return memoryMapper.findById(id);
    }

    @Transactional
    public EngineeringMemory create(EngineeringMemory req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (req.getBody() == null || req.getBody().isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        String type = req.getMemType() == null ? "CONTEXT" : req.getMemType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("memType 须为 DECISION|CONVENTION|CHANGE|PITFALL|CONTEXT");
        }
        EngineeringMemory row = new EngineeringMemory();
        row.setOccurredAt(req.getOccurredAt() != null ? req.getOccurredAt() : LocalDateTime.now());
        row.setMemType(type);
        row.setTitle(req.getTitle().trim());
        row.setBody(req.getBody().trim());
        row.setTags(blankToNull(req.getTags()));
        row.setSource(blankToNull(req.getSource()) != null ? req.getSource().trim() : "manual");
        row.setSessionRef(blankToNull(req.getSessionRef()));
        row.setCreatedBy(blankToNull(req.getCreatedBy()));
        row.setCreatedAt(LocalDateTime.now());
        memoryMapper.insert(row);
        return row;
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", memoryMapper.countAll());
        m.put("types", TYPES);
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
