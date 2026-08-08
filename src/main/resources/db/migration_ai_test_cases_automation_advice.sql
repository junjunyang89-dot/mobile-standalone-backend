-- 自动化建议：与「是否已挂流程」正交。
-- RECOMMENDED / PARTIAL / NOT_RECOMMENDED；空＝尚未评估。
ALTER TABLE ai_test_cases
    ADD COLUMN automation_advice VARCHAR(32) NULL
        COMMENT '自动化建议：RECOMMENDED|PARTIAL|NOT_RECOMMENDED，空＝未评估'
        AFTER flow_id,
    ADD INDEX idx_automation_advice (automation_advice);
