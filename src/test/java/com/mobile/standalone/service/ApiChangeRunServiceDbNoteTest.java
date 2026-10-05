package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiChangeRunServiceDbNoteTest {

    private static final String LINK = ApiChangeRunService.shortDbError("Communications link failure\nThe last packet ...");

    @Test
    void 没关联库_通用库全连不上_不列无关库名_写明其余没再试() {
        Map<String, List<String>> failed = new LinkedHashMap<>();
        failed.put(LINK, List.of("app_mall", "app_meme", "app_rating"));
        String note = ApiChangeRunService.sharedDbNote(15, 3, failed, List.of("comment_score", "comment_reply"));
        assertEquals("这个项目还没关联测试库，试了 3 个通用库都连不上（" + LINK + "），其余 12 个没再试。"
                + "去「项目管理」给这个项目点「关联库」勾上，下次只查关联的库", note);
        assertFalse(note.contains("app_mall"));
    }

    @Test
    void 没关联库_能连上但没找到表() {
        String note = ApiChangeRunService.sharedDbNote(15, 15, Map.of(), List.of("comment_score"));
        assertTrue(note.startsWith("这个项目还没关联测试库，在 15 个通用库里都没找到 comment_score"), note);
    }

    @Test
    void 没关联库_部分连不上() {
        Map<String, List<String>> failed = new LinkedHashMap<>();
        failed.put(LINK, List.of("a", "b"));
        String note = ApiChangeRunService.sharedDbNote(5, 5, failed, List.of("t"));
        assertTrue(note.contains("通用库里没找到 t（2 个连不上"), note);
    }

    @Test
    void 表都找到了就不写说明() {
        assertNull(ApiChangeRunService.sharedDbNote(15, 2, Map.of(), List.of()));
    }
}
