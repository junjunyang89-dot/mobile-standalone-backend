-- AI 用例库分类（性能测试 / 接口 / 各端功能用例等）
-- 执行：mysql ... < migration_ai_test_cases_category.sql

ALTER TABLE ai_test_cases
    ADD COLUMN category VARCHAR(64) NULL COMMENT '用例库分类 key，如 performance / wmpvp-android' AFTER page_name,
    ADD INDEX idx_category (category);
