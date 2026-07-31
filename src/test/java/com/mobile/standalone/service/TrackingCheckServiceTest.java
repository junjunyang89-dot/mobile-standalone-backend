package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import com.mobile.standalone.dto.TrackingCheckDtos.WikiTrackingRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrackingCheckServiceTest {

    private TrackingCheckService svc;

    @BeforeEach
    void setUp() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setWikiBaseUrl("https://wiki.shdev.net");
        props.setUsername("u");
        props.setPassword("p");
        svc = new TrackingCheckService(props);
    }

    @Test
    void parseTrackingTableAndFilterVersion() {
        String html = """
                <h3>数据页Deadlock板块</h3>
                <table>
                <tr><th>页面图片</th><th>element_id</th><th>element_name</th><th>element_class_name</th>
                <th>value</th><th>上线日期</th><th>QA</th><th>版本</th><th>备注</th></tr>
                <tr><td></td><td>110</td><td>DL更多数据</td><td>数据页Deadlock板块</td><td></td><td></td><td></td><td>v4.9</td><td></td></tr>
                <tr><td></td><td>113</td><td>DL模式切换</td><td>数据页Deadlock板块</td>
                <td>{tab_name:标准模式/街斗模式}</td><td></td><td></td><td>v4.9</td><td></td></tr>
                <tr><td></td><td>1</td><td>其它</td><td>x</td><td></td><td></td><td></td><td>v5.0</td><td></td></tr>
                </table>
                """;
        List<WikiTrackingRow> rows = svc.parseTrackingTables(html);
        assertEquals(3, rows.size());
        var v49 = TrackingCheckService.filterRows(rows, "v4.9", null);
        assertEquals(2, v49.size());
        var one = TrackingCheckService.filterRows(rows, "4.9", "DL模式切换");
        assertEquals(1, one.size());
        assertEquals("113", one.get(0).getElementId());
        assertTrue(one.get(0).getValue().contains("tab_name"));
    }

    @Test
    void matchKibanaLogAgainstWikiRow() {
        WikiTrackingRow row = new WikiTrackingRow();
        row.setElementId("110");
        row.setElementName("DL更多数据");
        row.setElementClassName("数据页Deadlock板块");

        String kibana = """
                {"ip":"1.1.1.1","log":"{\\"event_name\\":\\"click\\",\\"properties\\":{\\"element_id\\":\\"110\\",\\"element_name\\":\\"DL更多数据\\",\\"element_class_name\\":\\"数据页Deadlock板块\\"}}","timestamp":1}
                """;
        List<Map<String, Object>> logs = svc.parseKibanaJson(kibana);
        assertEquals(1, logs.size());
        assertTrue(svc.logMatchesRow(logs.get(0), row));

        row.setElementName("DL模式切换");
        assertFalse(svc.logMatchesRow(logs.get(0), row));
    }

    @Test
    void extractValueKeysFromWikiValue() {
        assertEquals(List.of("tab_name"),
                TrackingCheckService.extractValueKeys("{tab_name:标准模式/街斗模式}"));
        assertTrue(TrackingCheckService.extractValueKeys("").isEmpty());
    }

    @Test
    void extractPageId() {
        assertEquals("74142172", TrackingCheckService.extractPageId(
                "https://wiki.shdev.net/pages/viewpage.action?pageId=74142172"));
    }

    @Test
    void parseKibanaResponsesWithSourceMessage() throws Exception {
        // 模拟 Kibana 导出：responses -> hits -> _source.message -> {ip,log,timestamp}
        String innerLog = "{\"event_name\":\"click\",\"properties\":{"
                + "\"element_id\":\"111\","
                + "\"element_name\":\"DL下方tab\","
                + "\"element_class_name\":\"数据页Deadlock板块\","
                + "\"value\":{\"tab_name\":\"更新日志\"}}}";
        String message = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "ip", "1.1.1.1",
                "log", innerLog,
                "timestamp", 1
        ));
        String kibana = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "took", 5,
                "responses", List.of(Map.of(
                        "hits", Map.of(
                                "hits", List.of(Map.of(
                                        "_source", Map.of("message", message)
                                ))
                        )
                ))
        ));
        List<Map<String, Object>> logs = svc.parseKibanaJson(kibana);
        assertFalse(logs.isEmpty(), "应解析出日志");
        WikiTrackingRow row = new WikiTrackingRow();
        row.setElementId("111");
        row.setElementName("DL下方tab");
        row.setElementClassName("数据页Deadlock板块");
        assertTrue(svc.logMatchesRow(logs.get(0), row), "应命中 DL下方tab");
    }

    @Test
    void parseRealKibanaData2IfPresent() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(
                System.getProperty("user.home"), "Downloads", "kibana_data_2.json");
        if (!java.nio.file.Files.exists(p)) {
            return;
        }
        String raw = java.nio.file.Files.readString(p);
        List<Map<String, Object>> logs = svc.parseKibanaJson(raw);
        assertTrue(logs.size() >= 1, "应解析出至少 1 条, actual=" + logs.size());
        WikiTrackingRow row = new WikiTrackingRow();
        row.setElementId("111");
        row.setElementName("DL下方tab");
        row.setElementClassName("数据页Deadlock板块");
        long hit = logs.stream().filter(l -> svc.logMatchesRow(l, row)).count();
        assertTrue(hit >= 1, "文件中应能命中 DL下方tab, logs=" + logs.size());

        long android = logs.stream().filter(l -> TrackingCheckService.osMatches(l, "Android")).count();
        long ios = logs.stream().filter(l -> TrackingCheckService.osMatches(l, "iOS")).count();
        assertTrue(android >= 1, "应有 Android 日志");
        assertEquals(0, ios, "样例文件不应有 iOS");
    }

    @Test
    void osCategorizeAndFilter() {
        assertEquals("iOS", TrackingCheckService.categorizeOs("iOS"));
        assertEquals("Android", TrackingCheckService.categorizeOs("Android"));
        assertEquals("H5", TrackingCheckService.categorizeOs("H5"));
        assertEquals("H5", TrackingCheckService.categorizeOs("web"));

        Map<String, Object> androidLog = Map.of("log", Map.of("os", "Android", "properties", Map.of()));
        Map<String, Object> iosLog = Map.of("os", "iOS");
        assertTrue(TrackingCheckService.osMatches(androidLog, "Android"));
        assertFalse(TrackingCheckService.osMatches(androidLog, "iOS"));
        assertTrue(TrackingCheckService.osMatches(iosLog, "iOS"));
        assertTrue(TrackingCheckService.osMatches(androidLog, null));
    }
}
