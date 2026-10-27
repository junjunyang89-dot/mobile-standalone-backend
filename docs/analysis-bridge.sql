CREATE TABLE IF NOT EXISTS analysis_bridge_jobs (
            id VARCHAR(64) PRIMARY KEY, client VARCHAR(100) NOT NULL, external_id VARCHAR(160) NOT NULL,
            requirement_key VARCHAR(80) NOT NULL, request_hash CHAR(64) NOT NULL, request_json LONGTEXT NOT NULL,
            status VARCHAR(24) NOT NULL, error_code VARCHAR(80) DEFAULT NULL, attempt_id VARCHAR(64) DEFAULT NULL,
            heartbeat_at DATETIME DEFAULT NULL, result_json LONGTEXT, result_hash CHAR(64) DEFAULT NULL,
            result_source VARCHAR(24) DEFAULT NULL, callback_event_id VARCHAR(64) DEFAULT NULL,
            callback_state VARCHAR(24) NOT NULL DEFAULT 'NONE', callback_attempts INT NOT NULL DEFAULT 0,
            callback_next_at DATETIME DEFAULT NULL, callback_http_status INT DEFAULT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            finished_at DATETIME DEFAULT NULL, UNIQUE KEY uk_client_external(client,external_id),
            INDEX idx_status(status,created_at), INDEX idx_callback(callback_state,callback_next_at)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
