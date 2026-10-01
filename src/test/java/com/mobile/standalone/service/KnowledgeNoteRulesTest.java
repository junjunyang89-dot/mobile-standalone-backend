package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeNoteRulesTest {

    @Test
    void 标题和模块不能空() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeNoteRules.requireTitle("  "));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeNoteRules.requireModuleId(null));
        assertEquals("礼物墙空态", KnowledgeNoteRules.requireTitle(" 礼物墙空态 "));
        assertEquals("m1", KnowledgeNoteRules.requireModuleId(" m1 "));
    }

    @Test
    void 需求单选填并归一成大写() {
        assertNull(KnowledgeNoteRules.optionalRequirementNo(""));
        assertEquals("APP-9069", KnowledgeNoteRules.optionalRequirementNo("app-9069"));
    }

    @Test
    void 过时状态归一() {
        assertEquals("ACTIVE", KnowledgeNoteRules.normalizeStatus(null));
        assertEquals("STALE", KnowledgeNoteRules.normalizeStatus("outdated"));
        assertEquals("ACTIVE", KnowledgeNoteRules.normalizeStatus("active"));
    }

    @Test
    void 图片列表有上限() {
        assertEquals("[]", KnowledgeNoteRules.imagesJson(null));
        assertEquals("[\"/a.png\"]", KnowledgeNoteRules.imagesJson(List.of(" /a.png ")));
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            tooMany.add("/" + i + ".png");
        }
        assertThrows(IllegalArgumentException.class, () -> KnowledgeNoteRules.imagesJson(tooMany));
    }

    @Test
    void 模块路径从叶子走到根() {
        KnowledgeNoteRules.ModuleRef leaf = new KnowledgeNoteRules.ModuleRef("m3", "m2", "礼物墙");
        KnowledgeNoteRules.ModuleRef mid = new KnowledgeNoteRules.ModuleRef("m2", "m1", "社区");
        KnowledgeNoteRules.ModuleRef root = new KnowledgeNoteRules.ModuleRef("m1", null, "数据壳");
        Map<String, KnowledgeNoteRules.ModuleRef> byId = Map.of("m1", root, "m2", mid, "m3", leaf);
        assertEquals("数据壳 / 社区 / 礼物墙", KnowledgeNoteRules.moduleLabel("m3", byId::get));
        assertEquals("数据壳", KnowledgeNoteRules.moduleLabel("m1", byId::get));
        assertTrue(KnowledgeNoteRules.moduleLabel("gone", byId::get).isEmpty());
    }
}
