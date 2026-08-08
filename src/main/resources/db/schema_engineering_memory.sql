-- 工程记忆时间线（决策/约定/变更/坑点）
-- 用途：大工程后续调优时检索，不存完整闲聊；整段对话仍用 agent_conversations。
-- mysql ... < schema_engineering_memory.sql

CREATE TABLE IF NOT EXISTS project_engineering_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    occurred_at DATETIME NOT NULL COMMENT '事件时间（时间线排序）',
    mem_type VARCHAR(32) NOT NULL COMMENT 'DECISION|CONVENTION|CHANGE|PITFALL|CONTEXT',
    title VARCHAR(255) NOT NULL COMMENT '一句话标题',
    body MEDIUMTEXT NOT NULL COMMENT '摘要正文（Markdown 可）',
    tags VARCHAR(512) DEFAULT NULL COMMENT '逗号分隔标签，如 ui-flows,online-regression',
    source VARCHAR(64) DEFAULT 'manual' COMMENT 'cursor-chat|manual|agent|seed',
    session_ref VARCHAR(128) DEFAULT NULL COMMENT '可选：关联 Cursor/Agent 会话 id',
    created_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_occurred (occurred_at),
    INDEX idx_type (mem_type),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工程记忆时间线';
