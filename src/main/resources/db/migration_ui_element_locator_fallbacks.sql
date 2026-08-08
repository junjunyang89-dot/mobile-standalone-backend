-- 元素库：主定位之外的备用定位方案（JSON 数组）
-- 例：[{"locatorType":"iosPredicate","locatorValue":"name == \"同意\""},{"locatorType":"xpath","locatorValue":"//XCUIElementTypeButton[@name=\"同意\"]"}]
ALTER TABLE ui_elements
    ADD COLUMN locator_fallbacks TEXT NULL COMMENT '备用定位 JSON 数组 [{locatorType,locatorValue},...]' AFTER locator_value;
