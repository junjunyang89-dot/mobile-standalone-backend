-- ui_keywords 增加 custom_ui：标记该关键字前端用专用编辑器渲染，其余按 param_schema 通用渲染
SET @exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ui_keywords'
      AND COLUMN_NAME = 'custom_ui'
);

SET @sql := IF(@exists = 0,
    'ALTER TABLE ui_keywords ADD COLUMN custom_ui TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''前端是否用专用编辑器渲染（嵌套分支、多方式安装等）'' AFTER param_schema',
    'SELECT ''ui_keywords.custom_ui already exists'' AS migration_note'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
