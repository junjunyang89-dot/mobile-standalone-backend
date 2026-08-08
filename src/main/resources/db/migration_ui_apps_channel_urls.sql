-- 应用字典：各厂商渠道详情页 URL（JSON：{"XIAOMI":"https://...","HUAWEI":"..."}）
ALTER TABLE ui_apps
    ADD COLUMN channel_urls TEXT NULL COMMENT '渠道页 URL JSON，key=AndroidStoreOem 名' AFTER ios_bundle_id;
