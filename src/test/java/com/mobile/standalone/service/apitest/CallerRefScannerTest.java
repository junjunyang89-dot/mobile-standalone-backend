package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CallerRefScannerTest {

    @TempDir
    Path root;

    private void write(String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    @Test
    void wrapperImportedElsewhereIsUsed_unimportedIsUnused() throws Exception {
        write("h5/src/api/match.js", """
                export function getAiComment(params) {
                    return request({
                        url: '/appdatacenter/ai/getAiComment',
                        params
                    })
                }
                """);
        write("h5/src/pages/Match.vue", "import { getAiComment } from '@/api/match'\ngetAiComment({})\n");
        write("sphere/src/api/match.js", """
                export function getAiComment(params) {
                    return request({ url: '/appdatacenter/ai/getAiComment', params })
                }
                """);

        Map<String, List<CallerRefScanner.Ref>> r = CallerRefScanner.scan(
                List.of("/appdatacenter/ai/getAiComment"), List.of(root), null);

        List<CallerRefScanner.Ref> refs = r.get("/appdatacenter/ai/getAiComment");
        assertThat(refs).hasSize(2);
        CallerRefScanner.Ref h5 = refs.stream().filter(x -> x.repo().equals("h5")).findFirst().orElseThrow();
        assertThat(h5.via()).isEqualTo("getAiComment");
        assertThat(h5.unused()).isFalse();
        assertThat(h5.usedBy()).containsExactly("src/pages/Match.vue");
        assertThat(h5.line()).isEqualTo(3);
        CallerRefScanner.Ref sphere = refs.stream().filter(x -> x.repo().equals("sphere")).findFirst().orElseThrow();
        assertThat(sphere.unused()).isTrue();
    }

    @Test
    void shortPathWithoutContextAndPathVariable() throws Exception {
        write("android/app/Api.kt", """
                interface Api {
                    @GET("ai/getAiComment") fun comment(): Call<Any>
                    @GET("match/detail/{id}") fun detail(@Path("id") id: Long): Call<Any>
                }
                """);
        write("h5/src/a.js", "const u = `/appdatacenter/match/detail/${id}`; request(u)\n");

        Map<String, List<CallerRefScanner.Ref>> r = CallerRefScanner.scan(
                List.of("/appdatacenter/ai/getAiComment", "/appdatacenter/match/detail/{matchId}"),
                List.of(root), null);

        assertThat(r.get("/appdatacenter/ai/getAiComment")).extracting(CallerRefScanner.Ref::repo)
                .containsExactly("android");
        assertThat(r.get("/appdatacenter/match/detail/{matchId}")).extracting(CallerRefScanner.Ref::repo)
                .containsExactlyInAnyOrder("android", "h5");
        CallerRefScanner.Ref local = r.get("/appdatacenter/match/detail/{matchId}").stream()
                .filter(x -> x.repo().equals("h5")).findFirst().orElseThrow();
        assertThat(local.unused()).isFalse();
    }

    @Test
    void retrofitBaseUrlPlaceholderAndSpringValueDefault() throws Exception {
        write("android/DataApi.kt", "@GET(\"{baseUrl}/appdatacenter/dota2/weekly/card\") fun card(): Call<Any>\n");
        write("admin/DataCenterServiceImpl.java",
                "@Value(\"${ai.translate.url:/appdatacenter/ai/aiTranslate}\")\nprivate String url;\n");
        write("ios/Service.swift", "let u = \"\\(host)/appdatacenter/dota2/weekly/card\"\n");

        Map<String, List<CallerRefScanner.Ref>> r = CallerRefScanner.scan(
                List.of("/appdatacenter/dota2/weekly/card", "/appdatacenter/ai/aiTranslate"), List.of(root), null);

        assertThat(r.get("/appdatacenter/dota2/weekly/card")).extracting(CallerRefScanner.Ref::repo)
                .containsExactlyInAnyOrder("android", "ios");
        assertThat(r.get("/appdatacenter/ai/aiTranslate")).extracting(CallerRefScanner.Ref::repo)
                .containsExactly("admin");
    }

    @Test
    void otherServiceOwnMappingAndExcludedRepoAreNotCallers() throws Exception {
        write("data-center/src/AiController.java", "@GetMapping(\"/appdatacenter/ai/getAiComment\")\n");
        write("other-svc/src/Other.java", "@GetMapping(\"/ai/getAiComment\")\npublic void x() {}\n");
        write("other-svc/src/Feign.java", "@FeignClient(\"dc\")\ninterface F { @GetMapping(\"/ai/getAiComment\") Object c(); }\n");
        write("h5/src/list.js", "request('/list')\n");

        Map<String, List<CallerRefScanner.Ref>> r = CallerRefScanner.scan(
                List.of("/appdatacenter/ai/getAiComment", "/appdatacenter/list"),
                List.of(root), root.resolve("data-center"));

        assertThat(r.get("/appdatacenter/ai/getAiComment")).extracting(CallerRefScanner.Ref::file)
                .containsExactly("src/Feign.java");
        assertThat(r).doesNotContainKey("/appdatacenter/list");
    }
}
