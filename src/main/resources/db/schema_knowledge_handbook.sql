-- 知识手册：分类短记（现象 / 原因 / 处理）+ 可选配图 + 归属项目
-- mysql ... < schema_knowledge_handbook.sql

CREATE TABLE IF NOT EXISTS knowledge_handbook (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(32) NOT NULL COMMENT '分类叶子 code：app/api/linux/troubleshoot/...',
    project VARCHAR(64) DEFAULT NULL COMMENT '归属项目，对应 api_projects.code',
    title VARCHAR(255) NOT NULL COMMENT '一句话标题',
    symptom TEXT NOT NULL COMMENT '现象：遇到什么',
    cause TEXT NOT NULL COMMENT '原因：为什么',
    fix TEXT NOT NULL COMMENT '处理：怎么解决',
    tags VARCHAR(512) DEFAULT NULL COMMENT '逗号分隔标签',
    image_url VARCHAR(512) DEFAULT NULL COMMENT '可选配图相对路径 handbook-images/xxx.png',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_project (project),
    INDEX idx_updated (updated_at),
    INDEX idx_title (title)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识手册短记';
