-- 数据库迁移脚本：为 apps 表添加缺失的字段（简化版）
-- 执行时间：2024-01-15
-- 说明：如果字段已存在会报错，可以忽略错误继续执行

-- 添加 install_path 字段
ALTER TABLE apps 
ADD COLUMN IF NOT EXISTS install_path VARCHAR(500) COMMENT '应用安装路径';

-- 添加 config 字段
ALTER TABLE apps 
ADD COLUMN IF NOT EXISTS config TEXT COMMENT '应用配置信息（JSON格式）';
