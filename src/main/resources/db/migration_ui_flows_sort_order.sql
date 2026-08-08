-- ui_flows 增加 sort_order：线上回归展示/执行顺序
-- 可重复执行

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ui_flows'
      AND COLUMN_NAME = 'sort_order'
);
SET @sql_col := IF(@col_exists = 0,
    'ALTER TABLE ui_flows ADD COLUMN sort_order INT NOT NULL DEFAULT 100 COMMENT ''回归/列表排序，越小越靠前'' AFTER enabled',
    'SELECT ''ui_flows.sort_order already exists'' AS migration_note'
);
PREPARE stmt_col FROM @sql_col;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;
