package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiProjectCatalogTest {

    @Test
    void category_appBackends() {
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("app-seckill"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("app-data-center"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("data-center"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("app_esports_api"));
    }

    @Test
    void category_pvpClients() {
        assertEquals("PVP", ApiProjectCatalog.categoryKeyOf("android-domestic-esports"));
        assertEquals("PVP", ApiProjectCatalog.categoryKeyOf("ios-domestic-esports"));
        assertEquals("PVP", ApiProjectCatalog.categoryKeyOf("harmonyos-domestic-esports"));
    }

    @Test
    void category_web() {
        assertEquals("WEB", ApiProjectCatalog.categoryKeyOf("app-steam-web"));
        assertEquals("WEB", ApiProjectCatalog.categoryKeyOf("steam-web"));
    }

    @Test
    void category_other() {
        // 平台自己的初始化服务和对比分析的演练项目，不该混进「APP 后端」业务树
        assertEquals("OTHER", ApiProjectCatalog.categoryKeyOf("auth-bootstrap"));
        assertEquals("OTHER", ApiProjectCatalog.categoryKeyOf("debug-tests"));
        assertEquals("OTHER", ApiProjectCatalog.categoryKeyOf("debug-compat"));
    }

    @Test
    void 别名项目也认得出是APP后端() {
        // comment-service 在 api_projects 里叫 app_comment-service，是货真价实的 APP 后端。
        // 早先这条被断言成 OTHER，是把「编码没带 app- 前缀」当成了「不是 APP」
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("comment-service"));
        assertEquals("APP", ApiProjectCatalog.categoryKeyOf("data-center"));
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
