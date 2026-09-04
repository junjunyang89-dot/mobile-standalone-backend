-- 线上问题跟踪：归属项目 + 截图
ALTER TABLE issue_tracking
    ADD COLUMN project_type VARCHAR(16) NOT NULL DEFAULT 'APP' COMMENT 'APP/PVP/WEB',
    ADD COLUMN images TEXT DEFAULT NULL COMMENT '截图 URL JSON 数组';

ALTER TABLE issue_tracking ADD INDEX idx_project (project_type);
