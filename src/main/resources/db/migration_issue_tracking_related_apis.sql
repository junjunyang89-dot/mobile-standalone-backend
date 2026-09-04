-- 线上问题跟踪：涉及的接口
ALTER TABLE issue_tracking
    ADD COLUMN related_apis TEXT DEFAULT NULL COMMENT '涉及的接口 JSON 数组';
