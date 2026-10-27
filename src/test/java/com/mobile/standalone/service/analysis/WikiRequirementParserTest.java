package com.mobile.standalone.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WikiRequirementParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void extractsPageIdFromViewUrl() {
        assertEquals("119717563", WikiPageIds.extract(
                "https://wiki.shdev.net/pages/viewpage.action?pageId=119717563").orElseThrow());
        assertEquals("119717563", WikiPageIds.extract("119717563").orElseThrow());
        assertTrue(WikiPageIds.extract("https://example.com").isEmpty());
    }

    @Test void samplePageKeepsTablesCodeExpandCommentsAndDuplicateImageRefs() throws Exception {
        String view = Files.readString(Path.of("src/test/resources/wiki/pvp-13859-view.html"));
        String storage = Files.readString(Path.of("src/test/resources/wiki/pvp-13859-storage.xml"));
        ObjectNode page = JSON.createObjectNode();
        page.put("title", "【进行中】玩家反馈流程优化");
        page.putObject("version").put("number", 18).put("when", "2026-10-10T18:08:57.000+08:00");
        JsonNode comment = JSON.readTree("{\"id\":\"119727888\",\"body\":{\"view\":{\"value\":\"<p>补一下发到站内信再打开的流程</p>\"}},\"version\":{\"when\":\"2026-09-11T15:27:27.000+08:00\",\"by\":{\"displayName\":\"qa\"}}}");
        JsonNode att = JSON.readTree("{\"id\":\"1\",\"title\":\"image2026-7-14_17-39-22.png\",\"version\":{\"number\":1},\"extensions\":{\"mediaType\":\"image/png\"}}");
        ObjectNode snap = WikiRequirementParser.snapshot("119717563", page, storage, view, List.of(comment), List.of(att), "rest");
        assertEquals(7, snap.path("tableCount").asInt());
        assertEquals(9, snap.path("codeBlockCount").asInt());
        assertTrue(snap.path("expandPresent").asBoolean());
        assertEquals(1, snap.path("comments").size());
        assertEquals("comment-supplement", snap.path("comments").get(0).path("role").asText());
        assertTrue(snap.path("comments").get(0).path("body").asText().contains("站内信"));
        int reportImg = 0;
        for (JsonNode img : snap.path("images")) {
            if ("image2026-7-14_17-39-22.png".equals(img.path("filename").asText())) reportImg++;
        }
        assertTrue(reportImg >= 2, "同一报告图应保留多处引用");
        boolean demo = false;
        for (JsonNode s : snap.path("sections")) {
            if (s.path("unconfirmedDemo").asBoolean(false)) demo = true;
        }
        assertTrue(demo);
        assertTrue(WikiMimeExportParser.looksLikeMime(
                "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=x\r\n".getBytes(StandardCharsets.US_ASCII)));
    }
}
