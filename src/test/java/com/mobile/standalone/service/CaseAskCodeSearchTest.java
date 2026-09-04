package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaseAskCodeSearchTest {

    @Test
    void 从引号和错字里抽出检索词() {
        List<String> q = CaseAskCodeSearch.extractQueries(
                "userid：113788021824 为什么这场有「数据陆续获取中，清耐心等待」，其他场没有？判断条件是什么。前端代码在 swift-sphere",
                List.of());
        assertThat(q)
                .contains("数据陆续获取中，清耐心等待")
                .contains("数据陆续获取中")
                .contains("isParsing")
                .contains("demoStatus")
                .noneMatch(s -> s.startsWith("/Users/"));
    }

    @Test
    void 追问接口时抽出前端方法而不是裸url冒号() {
        List<String> q = CaseAskCodeSearch.extractQueries(
                "但是matchData这个字段是从哪个接口获取的呢",
                List.of());
        assertThat(q)
                .contains("getMatchDetail")
                .contains("matchData.value =")
                .contains("/gld/deadlock/match/detail")
                .contains("isParsing")
                .contains("demoStatus")
                .doesNotContain("url:")
                .doesNotContain("requestGlobal");
    }

    @Test
    void 本机作答能从前端片段抽出详情接口() {
        var api = new CaseAskCodeSearch.Hit(
                "swift-sphere",
                "src/api/deadlock/matchDetail.js",
                4,
                "   4|     return requestGlobal({\n"
                        + "   5|         url: '/gld/deadlock/match/detail',\n"
                        + "   6|         method: 'get',\n"
                        + "   7|         params\n");
        var page = new CaseAskCodeSearch.Hit(
                "swift-sphere",
                "src/pages/deadlock/matchDetail/app.vue",
                286,
                " 286|    getMatchDetail({\n"
                        + " 291|            matchData.value = res.result || {};\n"
                        + " 295|            isParsing.value = matchData.value.demoStatus == 0;\n");
        String answer = CaseAskLocalAnalysis.answer(
                List.of(api, page), List.of(), "matchData这个字段是从哪个接口获取的");
        assertThat(answer)
                .contains("/gld/deadlock/match/detail")
                .contains("matchData.value = res.result")
                .contains("getMatchDetail")
                .contains("demoStatus == 0")
                .contains("值为 0")
                .doesNotContain("千问");
    }

    @Test
    void 没命中时明确说没搜到() {
        assertThat(CaseAskCodeSearch.formatHits(List.of())).contains("没有搜到");
    }

    @Test
    void 本机分析会抽出demoStatus判断() {
        var hit = new CaseAskCodeSearch.Hit(
                "swift-sphere",
                "src/pages/deadlock/matchDetail/app.vue",
                295,
                " 295|             isParsing.value = matchData.value.demoStatus == 0;\n");
        String memo = CaseAskLocalAnalysis.memo(List.of(hit), List.of("/gld/deadlock/match/detail"), "何时露出");
        assertThat(memo)
                .contains("本机代码分析")
                .contains("demoStatus == 0")
                .contains("/gld/deadlock/match/detail");
        String answer = CaseAskLocalAnalysis.answer(List.of(hit), List.of(), "何时露出该文案");
        assertThat(answer).contains("demoStatus == 0");
    }

    @Test
    void 贴了响应也会对照demoStatus取值() {
        var page = new CaseAskCodeSearch.Hit(
                "swift-sphere",
                "src/pages/deadlock/matchDetail/app.vue",
                291,
                "matchData.value = res.result || {};\n"
                        + "isParsing.value = matchData.value.demoStatus == 0;\n");
        String answer = CaseAskLocalAnalysis.answer(
                List.of(page),
                List.of(),
                "matchData来自哪个字段 {\"code\":0,\"result\":{\"demoStatus\":0,\"matchId\":1}}");
        assertThat(answer)
                .contains("你贴的响应里 demoStatus 是 0")
                .contains("所以这场会出这段字");
    }

    @EnabledIf("swiftSphereExists")
    @Test
    void 能从swiftSphere搜到露出条件() {
        CaseAskCodeSearch search = new CaseAskCodeSearch(null);
        ReflectionTestUtils.setField(search, "workspace",
                "/Users/yangjun/projects/qa_coding/app_coding");
        List<CaseAskCodeSearch.Hit> hits = search.search(
                "为什么概览有「数据陆续获取中，请耐心等待」？前端代码在 swift-sphere，判断条件是什么",
                List.of());
        assertThat(hits).isNotEmpty();
        String blob = CaseAskCodeSearch.formatHits(hits);
        assertThat(blob).containsAnyOf("demoStatus", "isParsing", "matchOverview.loading", "数据陆续获取中");
        assertThat(blob).doesNotContain("applog.js");
    }

    @EnabledIf("swiftSphereExists")
    @Test
    void 追问matchData时能搜到详情接口() {
        CaseAskCodeSearch search = new CaseAskCodeSearch(null);
        ReflectionTestUtils.setField(search, "workspace",
                "/Users/yangjun/projects/qa_coding/app_coding");
        List<CaseAskCodeSearch.Hit> hits = search.search(
                "为什么概览有「数据陆续获取中，请耐心等待」？前端代码在 swift-sphere\n"
                        + "但是matchData这个字段是从哪个接口获取的呢",
                List.of());
        assertThat(hits).isNotEmpty();
        String answer = CaseAskLocalAnalysis.answer(hits, List.of(), "matchData这个字段是从哪个接口获取的");
        assertThat(answer).contains("/gld/deadlock/match/detail");
    }

    static boolean swiftSphereExists() {
        return Files.isDirectory(Path.of("/Users/yangjun/projects/qa_coding/app_coding/swift-sphere"));
    }
}
