package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentPlaybookRulesTest {

    @Test
    void 标题必填() {
        assertThrows(IllegalArgumentException.class, () -> IncidentPlaybookRules.requireTitle(" "));
        assertEquals("API 包不对", IncidentPlaybookRules.requireTitle(" API 包不对 "));
    }

    @Test
    void 分类和工具状态归一() {
        assertEquals("OTHER", IncidentPlaybookRules.normalizeCategory(null));
        assertEquals("DEPENDENCY", IncidentPlaybookRules.normalizeCategory("dependency"));
        assertEquals("OTHER", IncidentPlaybookRules.normalizeCategory("unknown"));
        assertEquals("NONE", IncidentPlaybookRules.normalizeToolStatus(null));
        assertEquals("SCRIPT", IncidentPlaybookRules.normalizeToolStatus("script"));
    }

    @Test
    void 报错指纹按行拆() {
        assertEquals(List.of("does not override", "cannot find symbol"),
                IncidentPlaybookRules.parseFingerprints("does not override\ncannot find symbol"));
        assertEquals(List.of("too many Body parameters"),
                IncidentPlaybookRules.parseFingerprints(List.of(" too many Body parameters ")));
        assertTrue(IncidentPlaybookRules.fingerprintsText(null).isEmpty());
    }
}
