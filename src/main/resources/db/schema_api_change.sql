-- 接口变更分析的两张辅助表。
--
-- 都很小，但都解决了实际卡点：
--   api_table_schemas：项目里一个 .sql 文件都没有（实测 data-center 为 0），
--                      建表语句只能靠人从数据库导出来贴进来。存下来之后，
--                      每次分析都能自动给出该表的测试注意事项。
--   api_code_dirs：远程办公拿不到内网，切不了分支，只能一个分支拷一份目录。
--                  路径手打一次两次还行，天天用受不了。

CREATE TABLE IF NOT EXISTS api_table_schemas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project VARCHAR(64) NULL COMMENT '所属接口项目 code；留空表示全局通用',
    table_name VARCHAR(128) NOT NULL COMMENT '表名，小写',
    ddl TEXT NOT NULL COMMENT '建表语句，从数据库导出后原样粘贴',
    remark TEXT NULL COMMENT '人工补充的测试注意事项，会和自动推导的一起展示',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY udx_project_table (project, table_name),
    INDEX idx_table (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口变更分析：表结构与测试注意事项';

CREATE TABLE IF NOT EXISTS api_code_dirs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project VARCHAR(64) NULL COMMENT '所属接口项目 code；留空表示不限项目',
    label VARCHAR(128) NOT NULL COMMENT '别名，如 master / APP-8928',
    dir_path VARCHAR(512) NOT NULL COMMENT '本地代码目录绝对路径',
    is_baseline TINYINT(1) DEFAULT 0 COMMENT '1=默认作为基线（master 那份）',
    remark VARCHAR(512) NULL,
    sort_order INT DEFAULT 100,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY udx_project_path (project, dir_path),
    INDEX idx_project (project)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口变更分析：本地代码目录';

CREATE TABLE IF NOT EXISTS api_change_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project VARCHAR(64) NULL COMMENT '项目 code',
    base_ref VARCHAR(255) NULL COMMENT '基线分支或目录标签',
    head_ref VARCHAR(255) NULL COMMENT '待测分支或目录标签',
    summary VARCHAR(512) NULL COMMENT '列表短摘要',
    report_json LONGTEXT NOT NULL COMMENT '完整分析报告 JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_project_created (project, created_at),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口变更分析报告快照';

CREATE TABLE IF NOT EXISTS api_field_marks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mark_key CHAR(40) NOT NULL COMMENT 'sha1(kind|project|method|path|side|field|change_sig)',
    kind VARCHAR(16) NOT NULL COMMENT 'TAG 业务标签 / CONFIRM 已确认',
    project VARCHAR(64) NOT NULL DEFAULT '' COMMENT '项目 code',
    http_method VARCHAR(16) NOT NULL,
    path VARCHAR(512) NOT NULL,
    side VARCHAR(16) NOT NULL COMMENT 'REQUEST / RESPONSE',
    field VARCHAR(255) NOT NULL,
    change_sig VARCHAR(512) NULL COMMENT '仅 CONFIRM：base..head|变更类型|旧类型|新类型',
    biz_tag VARCHAR(128) NULL COMMENT '仅 TAG：关联的前端页面/模块',
    updated_by VARCHAR(64) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY udx_mark_key (mark_key),
    INDEX idx_project_kind (project, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接口对比：字段业务标签与确认记录';
