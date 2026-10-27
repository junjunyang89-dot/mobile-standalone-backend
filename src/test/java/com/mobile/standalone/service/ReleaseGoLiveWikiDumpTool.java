package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一次性：拉指定 Wiki 上线看板 JSON 写到 target/go-live-board.json
 * <pre>mvn -Dtest=ReleaseGoLiveWikiDumpTool -Dtool.run=true test</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "tool.run", matches = "true")
class ReleaseGoLiveWikiDumpTool {

    @Autowired
    ReleaseGoLiveService goLive;

    @Test
    void dump() throws Exception {
        String url = System.getProperty("tool.wiki",
                "https://wiki.shdev.net/pages/viewpage.action?pageId=119730957");
        Map<String, Object> board = goLive.board(url);
        Path out = Path.of("target/go-live-board.json");
        Files.writeString(out, new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(board));
        System.out.println("wrote " + out.toAbsolutePath());
        System.out.println("projects=" + board.get("projectCount") + " issues=" + board.get("issueCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) board.get("projects");
        int branches = 0;
        for (Map<String, Object> p : projects) {
            @SuppressWarnings("unchecked")
            List<String> bs = (List<String>) p.get("branches");
            branches += bs == null ? 0 : bs.size();
            System.out.println(p.get("project") + " (" + (bs == null ? 0 : bs.size()) + "): " + bs);
        }
        System.out.println("totalBranches=" + branches);
    }
}
