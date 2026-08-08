-- 「测试项」补回来。
--
-- 当初建议并进「测试点」，理由是四级层级容易填不满、变噪音。
-- 但用例改成以 Excel 导入为主路径之后，那一列在源表里是有数据的——
-- 并列等于导入时丢数据，而平台表单也就不再是 Excel 模板的镜像。
-- 填不填得满是使用者自己能管的事，导入丢数据不是。
ALTER TABLE ai_test_cases
    ADD COLUMN test_item VARCHAR(255) NULL COMMENT '测试项（三级），测试点是四级' AFTER sub_module;
