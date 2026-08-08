-- AI 生成的用例文档表（与自动化 test_cases 分离，勿互相覆盖）
-- 执行：mysql ... < migration_ai_test_cases.sql

CREATE TABLE IF NOT EXISTS ai_test_cases (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id         VARCHAR(32)  NOT NULL COMMENT '业务编号，如 TC-001',
    page_name       VARCHAR(100) NOT NULL COMMENT '页面名称，如 登录页',
    title           VARCHAR(200) NOT NULL COMMENT '用例标题',
    priority        VARCHAR(8)   DEFAULT 'P1' COMMENT 'P0/P1/P2',
    type            VARCHAR(40)  DEFAULT '功能测试' COMMENT '功能测试/异常测试等',
    precondition    TEXT         NULL COMMENT '前置条件',
    steps_json      JSON         NOT NULL COMMENT '步骤 [{step,action,target,expected}]',
    screenshot_url  VARCHAR(500) NULL COMMENT '关联截图 URL',
    platform        VARCHAR(20)  NULL COMMENT 'ANDROID/IOS/...',
    app_id          VARCHAR(64)  NULL COMMENT '应用字典或安装应用 ID',
    source          VARCHAR(32)  DEFAULT 'AI' COMMENT '来源：AI/MANUAL',
    status          VARCHAR(20)  DEFAULT 'DRAFT' COMMENT 'DRAFT/REVIEWED/ARCHIVED',
    batch_id        VARCHAR(64)  NULL COMMENT '同一次生成批次',
    created_by      VARCHAR(100) NULL,
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_page (page_name),
    INDEX idx_priority (priority),
    INDEX idx_status (status),
    INDEX idx_batch (batch_id),
    INDEX idx_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI生成测试用例文档';
