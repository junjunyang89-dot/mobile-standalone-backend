package com.mobile.standalone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.AgentConversation;
import com.mobile.standalone.entity.AgentKnowledge;
import com.mobile.standalone.mapper.AgentConversationMapper;
import com.mobile.standalone.mapper.AgentKnowledgeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台智能存储：知识 / 会话落在本地 MySQL。
 * <p>
 * 架构：前端俊俊页 → 本后端 → CodeWords Agent（理解意图/调工具）
 * Agent 若配置了 STORAGE_API_URL=http://本机:3000，会回调本服务的 /api/knowledge、/api/conversation。
 * 契约与云端文档一致（POST 返回 status=stored；GET 返回 results/snippet），但实现与数据都在本地。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentStorageService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentKnowledgeMapper knowledgeMapper;
    private final AgentConversationMapper conversationMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTables() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge (
                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        topic VARCHAR(255) NOT NULL,
                        content MEDIUMTEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS agent_conversations (
                        session_id VARCHAR(128) PRIMARY KEY,
                        messages MEDIUMTEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[agent-storage] 表已就绪 knowledge / agent_conversations");
        } catch (Exception e) {
            log.warn("[agent-storage] 自动建表失败（可手动执行 schema_agent_storage.sql）: {}", e.getMessage());
        }
    }

    /** POST → {"status":"stored"} （与 Python 示例一致） */
    public Map<String, Object> saveKnowledge(String topic, String content) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        AgentKnowledge row = new AgentKnowledge();
        row.setTopic(topic.trim());
        row.setContent(content.trim());
        knowledgeMapper.insert(row);
        return Map.of("status", "stored");
    }

    /**
     * GET → {"results":[{id,topic,snippet,created_at}], "count":N}
     * 默认 limit=5，与 Python 示例一致。
     */
    public Map<String, Object> searchKnowledge(String query, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        String q = query == null ? "" : query.trim();
        List<AgentKnowledge> list = knowledgeMapper.search(q, lim);
        List<Map<String, Object>> results = new ArrayList<>();
        for (AgentKnowledge k : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", k.getId());
            item.put("topic", k.getTopic());
            item.put("snippet", k.getSnippet() != null ? k.getSnippet() : "");
            if (k.getCreatedAt() != null) {
                item.put("created_at", k.getCreatedAt().toString());
            }
            results.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("count", results.size());
        return out;
    }

    public int knowledgeCount() {
        try {
            return knowledgeMapper.countAll();
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> saveConversation(String sessionId, Object messages) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session_id 不能为空");
        }
        if (messages == null) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        String json;
        try {
            json = JSON.writeValueAsString(messages);
        } catch (Exception e) {
            throw new IllegalArgumentException("messages 无法序列化为 JSON: " + e.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        AgentConversation row = new AgentConversation();
        row.setSessionId(sessionId.trim());
        row.setMessages(json);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        conversationMapper.upsert(row);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("session_id", row.getSessionId());
        return out;
    }

    public Map<String, Object> loadConversation(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session_id 不能为空");
        }
        AgentConversation row = conversationMapper.findBySessionId(sessionId.trim());
        List<Object> messages = List.of();
        if (row != null && row.getMessages() != null && !row.getMessages().isBlank()) {
            try {
                messages = JSON.readValue(row.getMessages(), new TypeReference<>() {});
            } catch (Exception e) {
                throw new IllegalStateException("会话消息 JSON 解析失败: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", messages);
        out.put("session_id", sessionId.trim());
        return out;
    }
}
