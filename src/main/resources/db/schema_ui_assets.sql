-- 低代码资产层：应用字典 / 元素库 / 关键字库 / 流程
-- 元素默认 SHARED：测试与线上共用同一条定位，改一处两边生效

CREATE TABLE IF NOT EXISTS ui_apps (
    id VARCHAR(64) PRIMARY KEY COMMENT '应用编码，如 DOMESTIC_ESPORTS',
    name_zh VARCHAR(128) NOT NULL COMMENT '显示名称',
    description VARCHAR(512) COMMENT '说明',
    android_package VARCHAR(255) COMMENT 'Android 包名',
    ios_bundle_id VARCHAR(255) COMMENT 'iOS Bundle ID',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务应用字典';

CREATE TABLE IF NOT EXISTS ui_elements (
    id VARCHAR(64) PRIMARY KEY COMMENT '元素ID',
    app_id VARCHAR(64) NOT NULL COMMENT '应用ID（对应 ui_apps.id）',
    platform VARCHAR(20) NOT NULL COMMENT 'ANDROID / IOS / HARMONYOS / POG_ANDROID / POG_IOS',
    catalog VARCHAR(32) NOT NULL DEFAULT 'SHARED' COMMENT 'AUTOMATION / ONLINE / SHARED',
    name_zh VARCHAR(128) NOT NULL COMMENT '中文名称，拖拽展示用',
    name_en VARCHAR(128) NOT NULL COMMENT '英文 key，流程里引用',
    description VARCHAR(512) COMMENT '元素描述',
    module_tag VARCHAR(64) COMMENT '可选模块标签，如 登录页/侧边栏，仅分组不嵌套',
    locator_type VARCHAR(32) NOT NULL COMMENT 'id / xpath / text / accessibilityId / className / other',
    locator_value VARCHAR(2000) NOT NULL COMMENT '定位值',
    preview_image VARCHAR(500) COMMENT '预览图相对路径，可选',
    default_timeout_sec INT DEFAULT 10 COMMENT '默认等待秒数',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 0,
    created_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_plat_en (app_id, platform, name_en),
    INDEX idx_catalog (catalog),
    INDEX idx_module (app_id, platform, module_tag),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='UI元素库（行级，可CRUD）';

CREATE TABLE IF NOT EXISTS ui_keywords (
    id VARCHAR(64) PRIMARY KEY COMMENT '关键字ID',
    code VARCHAR(64) NOT NULL COMMENT '引擎分支码：CLICK / INPUT / WAIT ...',
    name_zh VARCHAR(128) NOT NULL COMMENT '中文名，拖拽面板展示',
    description VARCHAR(512) COMMENT '说明',
    category VARCHAR(32) NOT NULL DEFAULT 'ACTION' COMMENT 'ACTION / WAIT / ASSERT / DEVICE / FLOW',
    needs_element TINYINT(1) DEFAULT 0 COMMENT '是否必须绑定元素',
    param_schema TEXT COMMENT '参数说明 JSON',
    builtin TINYINT(1) DEFAULT 0 COMMENT '内置不可删',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code),
    INDEX idx_category (category),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键字/动作库（含延迟等，拖拽用）';

CREATE TABLE IF NOT EXISTS ui_flows (
    id VARCHAR(64) PRIMARY KEY COMMENT '流程ID',
    name VARCHAR(255) NOT NULL COMMENT '流程名称，如 登录',
    description TEXT,
    app_id VARCHAR(64) COMMENT '应用ID，空表示通用',
    platform VARCHAR(20) COMMENT '平台，空表示通用',
    catalog VARCHAR(32) NOT NULL DEFAULT 'SHARED' COMMENT 'AUTOMATION / ONLINE / SHARED',
    is_component TINYINT(1) DEFAULT 0 COMMENT '1=可被其它流程引用的组件',
    steps TEXT NOT NULL COMMENT '步骤 JSON：[{keywordCode, elementKey, params}]',
    enabled TINYINT(1) DEFAULT 1,
    created_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_app_plat (app_id, platform),
    INDEX idx_catalog (catalog),
    INDEX idx_component (is_component),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可拖拽流程/组件';
