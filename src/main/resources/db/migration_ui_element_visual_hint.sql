-- 元素库：视觉说明，便于人眼把截图上的控件对上元素行；检索也会扫此字段与 locator
ALTER TABLE ui_elements
    ADD COLUMN visual_hint VARCHAR(120) NULL COMMENT '视觉说明，如「左上角三横线汉堡」' AFTER description;
