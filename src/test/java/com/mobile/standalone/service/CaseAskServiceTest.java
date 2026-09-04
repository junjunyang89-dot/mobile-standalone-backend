package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseAskServiceTest {

    @Test
    void 检索只用测试的问句不带上分析回复() {
        String blob = CaseAskService.searchBlob(
                "matchData这个字段是从哪个接口获取的",
                List.of(
                        Map.of("role", "user", "content", "为什么有「数据陆续获取中」"),
                        Map.of("role", "assistant", "content", "本机仓库没有搜到相关代码，暂时无法确认来源接口。")));
        assertThat(blob)
                .contains("数据陆续获取中")
                .contains("matchData")
                .doesNotContain("无法确认来源接口");
    }

    @Test
    void 历史里的文件路径会带进下一轮() {
        List<String> refs = CaseAskService.sourceRefs(List.of(
                Map.of("role", "assistant",
                        "content", "结论：demoStatus == 0",
                        "sources", "swift-sphere/src/pages/deadlock/matchDetail/app.vue:295，"
                                + "swift-sphere/src/api/deadlock/matchDetail.js:6")));
        assertThat(refs)
                .contains("swift-sphere/src/pages/deadlock/matchDetail/app.vue:295")
                .contains("swift-sphere/src/api/deadlock/matchDetail.js:6");
    }

    @Test
    void 解析history会留下sources() {
        java.util.Map<String, Object> turn = new java.util.LinkedHashMap<>();
        turn.put("role", "assistant");
        turn.put("content", "ok");
        turn.put("sources", List.of("swift-sphere/src/api/deadlock/matchDetail.js:6"));
        List<Map<String, String>> turns = CaseAskService.parseHistory(List.of(turn));
        assertThat(turns.get(0).get("sources")).contains("matchDetail.js:6");
    }
}
