-- 接口对比分析调试表。启动时 DebugApiTestService 也会 CREATE IF NOT EXISTS。
-- 改字段 / 增删列后，用接口对比看表变更是否被识别。

CREATE TABLE IF NOT EXISTS debug_api_tests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL COMMENT '标题',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    score INT DEFAULT 0 COMMENT '打分，便于调试字段类型变更',
    remark VARCHAR(512) DEFAULT NULL COMMENT '备注',
    extra VARCHAR(1024) DEFAULT NULL COMMENT '扩展 JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口对比分析调试用';
