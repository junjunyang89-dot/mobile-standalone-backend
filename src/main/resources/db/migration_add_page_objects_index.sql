-- 为 page_objects 增加复合索引，加速按 (platform, app_id, page_path) 查询
-- 若表已存在且无此索引，可执行本脚本

USE mobile_standalone;

-- 若索引已存在会报错，可忽略或先 DROP INDEX
ALTER TABLE page_objects
ADD INDEX idx_platform_app_page (platform, app_id, page_path(100)) COMMENT '按平台+应用+页面查 POM 时加速';
