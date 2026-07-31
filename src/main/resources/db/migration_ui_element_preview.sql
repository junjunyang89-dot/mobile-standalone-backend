-- 元素可选预览图（方便辨认，非必传）
ALTER TABLE ui_elements
    ADD COLUMN preview_image VARCHAR(500) NULL COMMENT '预览图相对路径，如 ui-element-previews/xxx.png' AFTER locator_value;
