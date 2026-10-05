package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointLevelTest {

    private static String callers(String... repos) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < repos.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"repo\":\"").append(repos[i]).append("\",\"unused\":false}");
        }
        return sb.append(']').toString();
    }

    @Test
    void twoNativePlatformsIsCore() {
        var v = EndpointLevel.of("USED", callers("app_esports_android", "esportapp-ios", "app-swift"));
        assertEquals(EndpointLevel.CORE, v.level());
        assertTrue(v.reason().contains("安卓"));
        assertTrue(v.reason().contains("iOS"));
    }

    @Test
    void singleNativeOrH5IsImportant() {
        assertEquals(EndpointLevel.IMPORTANT,
                EndpointLevel.of("USED", callers("app_esports_harmonyos")).level());
        assertEquals(EndpointLevel.IMPORTANT,
                EndpointLevel.of("USED", callers("app-swift-v3", "swift-sphere")).level());
        assertEquals(EndpointLevel.IMPORTANT,
                EndpointLevel.of("USED", callers("global-data")).level());
    }

    @Test
    void adminOnlyOrDefOnlyIsNormal() {
        assertEquals(EndpointLevel.NORMAL, EndpointLevel.of("USED", callers("app-admin")).level());
        assertEquals(EndpointLevel.NORMAL, EndpointLevel.of("DEF_ONLY",
                "[{\"repo\":\"app_esports_android\",\"unused\":true}]").level());
    }

    @Test
    void unusedCallerEntriesDoNotCount() {
        String json = "[{\"repo\":\"app_esports_android\",\"unused\":false},"
                + "{\"repo\":\"esportapp-ios\",\"unused\":true}]";
        assertEquals(EndpointLevel.IMPORTANT, EndpointLevel.of("USED", json).level());
    }

    @Test
    void noneIsUnusedAndUnscannedIsUnknown() {
        assertEquals(EndpointLevel.UNUSED, EndpointLevel.of("NONE", null).level());
        assertNull(EndpointLevel.of(null, null).level());
    }

    @Test
    void manualLevelWins() {
        var ep = new com.mobile.standalone.entity.ApiEndpoint();
        ep.setCallerStatus("NONE");
        ep.setLevelManual("CORE");
        assertEquals(EndpointLevel.CORE, ApiEndpointService.levelOf(ep));
        ep.setLevelManual(null);
        assertEquals(EndpointLevel.UNUSED, ApiEndpointService.levelOf(ep));
    }

    @Test
    void capturedIsCoreEvenWhenScanMissesItButManualStillWins() {
        var ep = new com.mobile.standalone.entity.ApiEndpoint();
        ep.setCaptureMeta("{\"capturedAt\":\"2026-10-02T12:00\"}");
        assertEquals(EndpointLevel.CORE, ApiEndpointService.levelOf(ep));
        ep.setCallerStatus("NONE");
        assertEquals(EndpointLevel.CORE, ApiEndpointService.levelOf(ep));
        ep.setLevelManual("NORMAL");
        assertEquals(EndpointLevel.NORMAL, ApiEndpointService.levelOf(ep));

        var v = EndpointLevel.of("NONE", null, true);
        assertTrue(v.reason().startsWith("抓包抓到客户端在调"), v.reason());
        assertTrue(v.reason().contains("搜不到调用"), v.reason());
        assertEquals("抓包抓到客户端在调", EndpointLevel.of(null, null, true).reason());
        assertEquals(EndpointLevel.IMPORTANT,
                EndpointLevel.of("USED", callers("app_esports_harmonyos"), false).level());
    }

    @Test
    void markCapturedAddsTimestampOnceAndKeepsExistingMeta() {
        var mapper = org.mockito.Mockito.mock(com.mobile.standalone.mapper.ApiEndpointMapper.class);
        var ep = new com.mobile.standalone.entity.ApiEndpoint();
        ep.setId("e1");
        ep.setCaptureMeta("{\"queryParams\":{\"a\":\"1\"}}");
        org.mockito.Mockito.when(mapper.findById("e1")).thenReturn(ep);
        var svc = new ApiEndpointService(mapper, null);

        svc.markCaptured("e1");
        assertTrue(ep.getCaptureMeta().contains("capturedAt"), ep.getCaptureMeta());
        assertTrue(ep.getCaptureMeta().contains("queryParams"), ep.getCaptureMeta());
        org.mockito.Mockito.verify(mapper).update(ep);

        svc.markCaptured("e1");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(1)).update(ep);
    }
}
