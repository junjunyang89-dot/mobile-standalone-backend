package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiProject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProjectKindTest {

    @Test
    void 网关算后端但不扫接口() {
        ApiProject gw = new ApiProject();
        gw.setCode("app-gateway");
        gw.setKind(ApiProject.KIND_BACKEND);
        assertTrue(ApiProjectKind.isBackend(gw.getKind()));
        assertFalse(ApiProjectKind.scansEndpoints(gw));
        assertTrue(ApiProjectKind.skipScan("gateway"));
    }

    @Test
    void 前端和客户端不扫() {
        ApiProject fe = new ApiProject();
        fe.setCode("app-xiaomai");
        fe.setKind(ApiProject.KIND_FRONTEND);
        ApiProject cl = new ApiProject();
        cl.setCode("android-domestic-esports");
        cl.setKind(ApiProject.KIND_CLIENT);
        assertFalse(ApiProjectKind.scansEndpoints(fe));
        assertFalse(ApiProjectKind.scansEndpoints(cl));
    }

    @Test
    void 业务后端要扫() {
        ApiProject p = new ApiProject();
        p.setCode("app-activity");
        p.setKind(null);
        assertTrue(ApiProjectKind.scansEndpoints(p));
        assertEquals("后端", ApiProjectKind.label("BACKEND"));
        assertEquals("前端", ApiProjectKind.label("H5"));
        assertEquals("客户端", ApiProjectKind.label("CLIENT"));
    }

    @Test
    void 电竞H5要登记进项目管理且不扫接口() {
        assertEquals(2, ApiProjectKind.FRONTEND_CATALOG.size());
        for (ApiProjectKind.FrontendCatalog e : ApiProjectKind.FRONTEND_CATALOG) {
            assertTrue(ApiProjectKind.FRONTEND_CODES.contains(e.code()), e.code());
            assertFalse(ApiProjectKind.scansEndpoints("FRONTEND", e.code()));
            assertTrue(e.gitUrl().contains(e.code()));
        }
        assertEquals("app-swift", ApiProjectKind.FRONTEND_CATALOG.get(0).code());
        assertEquals("app-swift-v3", ApiProjectKind.FRONTEND_CATALOG.get(1).code());
    }
}
