-- 版本需求归档（Wiki 上线后面板消失后，本地可回查键值+摘要并跳 JIRA）
CREATE TABLE IF NOT EXISTS release_version_archives (
    id VARCHAR(64) PRIMARY KEY,
    app_version VARCHAR(64) NOT NULL COMMENT 'APP 版本如 4.1.4',
    release_label VARCHAR(128) NOT NULL COMMENT 'Wiki 标题如 8.6 国内客户端',
    product_line VARCHAR(64) DEFAULT NULL,
    release_date DATE DEFAULT NULL,
    issue_count INT DEFAULT 0,
    closed_count INT DEFAULT 0,
    last_synced_at DATETIME DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_label (app_version, release_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_version_issues (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    archive_id VARCHAR(64) NOT NULL,
    issue_key VARCHAR(32) NOT NULL,
    summary VARCHAR(512) DEFAULT NULL,
    issue_url VARCHAR(512) DEFAULT NULL,
    status_name VARCHAR(64) DEFAULT NULL,
    assignee VARCHAR(128) DEFAULT NULL,
    qa_assignee VARCHAR(128) DEFAULT NULL,
    closed_at DATETIME DEFAULT NULL,
    close_error VARCHAR(512) DEFAULT NULL,
    UNIQUE KEY uk_arch_key (archive_id, issue_key),
    KEY idx_archive (archive_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
