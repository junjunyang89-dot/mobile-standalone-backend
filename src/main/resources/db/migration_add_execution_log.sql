-- task_executions 增加 execution_log（与 TaskExecutionMapper 一致）
-- 可重复执行

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'task_executions'
      AND COLUMN_NAME = 'execution_log'
);
SET @sql_col := IF(@col_exists = 0,
    'ALTER TABLE task_executions ADD COLUMN execution_log TEXT NULL COMMENT ''执行步骤日志 JSON 数组''',
    'SELECT ''task_executions.execution_log already exists'' AS migration_note'
);
PREPARE stmt_col FROM @sql_col;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
