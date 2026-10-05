package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCaseLevelScopeTest {

    private static ApiEndpoint ep(String path, String callers) {
        ApiEndpoint e = new ApiEndpoint();
        e.setPath(path);
        e.setHttpMethod("GET");
        e.setCallerStatus(callers == null ? "NONE" : "USED");
        e.setCallersJson(callers);
        return e;
    }

    private static final String TWO_NATIVE =
            "[{\"repo\":\"app_esports_android\",\"unused\":false},{\"repo\":\"esportapp-ios\",\"unused\":false}]";
    private static final String H5 = "[{\"repo\":\"app-swift-v3\",\"unused\":false}]";
    private static final String ADMIN = "[{\"repo\":\"app-admin\",\"unused\":false}]";

    private static final Set<EndpointLevel> CORE_IMPORTANT = Set.of(EndpointLevel.CORE, EndpointLevel.IMPORTANT);

    @Test
    void onlyChosenLevels() {
        assertTrue(AiCaseGenerator.selectable(ep("/a", TWO_NATIVE), CORE_IMPORTANT));
        assertTrue(AiCaseGenerator.selectable(ep("/b", H5), CORE_IMPORTANT));
        assertFalse(AiCaseGenerator.selectable(ep("/c", ADMIN), CORE_IMPORTANT));
        assertFalse(AiCaseGenerator.selectable(ep("/d", null), CORE_IMPORTANT));
    }

    @Test
    void manualLevelWins() {
        ApiEndpoint e = ep("/c", ADMIN);
        e.setLevelManual("CORE");
        assertTrue(AiCaseGenerator.selectable(e, CORE_IMPORTANT));
    }

    @Test
    void retiredGoneAndInfraNeverSelected() {
        ApiEndpoint retired = ep("/a", TWO_NATIVE);
        retired.setRetiredAt(LocalDateTime.now());
        ApiEndpoint gone = ep("/a", TWO_NATIVE);
        gone.setGoneAt(LocalDateTime.now());
        assertFalse(AiCaseGenerator.selectable(retired, null));
        assertFalse(AiCaseGenerator.selectable(gone, null));
        assertFalse(AiCaseGenerator.selectable(ep("/acty/basic/offline", TWO_NATIVE), Set.of()));
        assertTrue(AiCaseGenerator.selectable(ep("/x", null), Set.of()));
    }
}
