package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProjectCatalogTest {

    @Test
    void 没手动分类的都归电竞APP() {
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("app-seckill"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("data-center"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("comment-service"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("ios-domestic-esports"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("debug-tests"));
    }

    @Test
    void 已下线的PVP_WEB_其他不再是分类() {
        assertEquals("APP", ApiProjectCatalog.resolveCategoryKey("app-seckill", "OTHER"));
        assertEquals("APP", ApiProjectCatalog.resolveCategoryKey("steam-web", "web"));
        assertEquals("APP", ApiProjectCatalog.resolveCategoryKey("ios-domestic-esports", "PVP"));
        assertFalse(ApiProjectCatalog.isBuiltinCategory("PVP"));
        assertFalse(ApiProjectCatalog.isBuiltinCategory("OTHER"));
        assertTrue(ApiProjectCatalog.isBuiltinCategory("APP"));
    }

    @Test
    void resolveCategoryKey_manualOverride() {
        assertEquals("APP", ApiProjectCatalog.resolveCategoryKey("ios-domestic-esports", "app"));
        assertEquals("POGPOG", ApiProjectCatalog.resolveCategoryKey("pogpog-api", "POGPOG"));
        assertEquals("中间件", ApiProjectCatalog.resolveCategoryKey("app-seckill", "中间件"));
        assertEquals("中间件", ApiProjectCatalog.categoryDisplayLabel("中间件"));
        assertEquals("电竞", ApiProjectCatalog.categoryDisplayLabel("APP"));
    }

    @Test
    void projectDisplayLabel_appBackend() {
        assertEquals("APP-ACTIVITY", ApiProjectCatalog.projectDisplayLabel("app-activity"));
        assertEquals("APP-DATA-CENTER", ApiProjectCatalog.projectDisplayLabel("app-data-center"));
        // data-center 在 api_projects 里的 name 是 app-data-center（数据中心），
        // 断言成 APP-SECKILL 是笔误——那是另一个项目
        assertEquals("APP-DATA-CENTER", ApiProjectCatalog.projectDisplayLabel("data-center"));
    }
}
