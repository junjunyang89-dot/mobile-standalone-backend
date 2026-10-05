package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfraEndpointsTest {

    @Test
    void swaggerInAnyForm() {
        for (String p : new String[]{"/swagger-ui.html", "${disable.swagger.path:/swagger-ui.html}",
                "/acty/swagger-resources/configuration/ui", "/v2/api-docs", "/v3/api-docs/default",
                "/doc.html", "/avo/doc.html", "/webjars/springfox/x.js",
                "https://appengine-testing.wmpvp.com/Swagger-UI.html?x=1"}) {
            assertTrue(InfraEndpoints.reason(p).startsWith("Swagger"), p);
        }
    }

    @Test
    void offlineAndHealth() {
        assertTrue(InfraEndpoints.reason("/basic/offline").startsWith("Nacos"));
        assertTrue(InfraEndpoints.reason("https://gwapi.pwesports.cn/acty/basic/offline/?secret=x").startsWith("Nacos"));
        assertTrue(InfraEndpoints.reason("basic/healthCheck").startsWith("健康检查"));
        assertTrue(InfraEndpoints.reason("/appdatacenter/basic/healthCheck").startsWith("健康检查"));
    }

    @Test
    void businessPathsUntouched() {
        assertNull(InfraEndpoints.reason("/loadMajorHotComments"));
        assertNull(InfraEndpoints.reason("/acty/basic/info"));
        assertNull(InfraEndpoints.reason("/steamcn/xbasic/offline"));
        assertNull(InfraEndpoints.reason("https://appengine.wmpvp.com/steamcn/community/talk/getTalkList"));
        assertNull(InfraEndpoints.reason(null));
        assertFalse(InfraEndpoints.is(""));
    }
}
