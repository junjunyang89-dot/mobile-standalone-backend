-- 任务表增加「测试场景键」字段（与 TaskMapper / Task 实体一致）
-- 可重复执行：已存在列/索引时跳过
-- 执行：mysql -u... -p... your_db < src/main/resources/db/migration_add_task_scenario_key.sql

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tasks'
      AND COLUMN_NAME = 'scenario_key'
);
SET @sql_col := IF(@col_exists = 0,
    'ALTER TABLE tasks ADD COLUMN scenario_key VARCHAR(64) NULL DEFAULT NULL COMMENT ''测试场景键：launch_app、login、search、launch_pogpog_android 等''',
    'SELECT ''tasks.scenario_key already exists'' AS migration_note'
);
PREPARE stmt_col FROM @sql_col;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tasks'
      AND INDEX_NAME = 'idx_tasks_scenario_key'
);
SET @sql_idx := IF(@idx_exists = 0,
    'CREATE INDEX idx_tasks_scenario_key ON tasks (scenario_key)',
    'SELECT ''idx_tasks_scenario_key already exists'' AS migration_note'
);
PREPARE stmt_idx FROM @sql_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
