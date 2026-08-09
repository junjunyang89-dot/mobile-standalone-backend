-- 发布轨：与复核 status 正交。TESTING=测试待测，ONLINE=线上稳定。
ALTER TABLE ai_test_cases
    ADD COLUMN release_stage VARCHAR(16) NULL
        COMMENT '发布轨：TESTING|ONLINE'
        AFTER automation_advice,
    ADD INDEX idx_release_stage (release_stage);
