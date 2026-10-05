package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceControllerMatcherTest {

    private static SourceEndpointScanner.Scanned s(String m, String path, String file) {
        return new SourceEndpointScanner.Scanned(m, path, file.replace(".java", "") + ".x", null, file, null);
    }

    private final List<SourceEndpointScanner.Scanned> code = List.of(
            s("POST", "/community/task/signIn", "CommunityTaskController.java"),
            s("GET", "/member/memberInfo", "MemberController.java"),
            s("ANY", "/user/{id}/detail", "UserController.java"),
            s("GET", "/xtask/signIn", "OtherController.java"));

    @Test
    void gatewayPrefixIsTolerated() {
        var r = SourceControllerMatcher.match("POST", "/steamcn/community/task/signIn", code);
        assertEquals(SourceControllerMatcher.Kind.SUFFIX, r.kind());
        assertEquals("CommunityTaskController.java", r.hit().file());
    }

    @Test
    void exactPathIsExact() {
        var r = SourceControllerMatcher.match("GET", "/member/memberInfo/", code);
        assertEquals(SourceControllerMatcher.Kind.EXACT, r.kind());
    }

    @Test
    void methodMustFitUnlessAny() {
        assertEquals(SourceControllerMatcher.Kind.NONE,
                SourceControllerMatcher.match("GET", "/steamcn/community/task/signIn", code).kind());
        var r = SourceControllerMatcher.match("DELETE", "/gw/user/42/detail", code);
        assertEquals("UserController.java", r.hit().file());
    }

    @Test
    void suffixOnlyOnWholeSegments() {
        var r = SourceControllerMatcher.match("POST", "/steamcn/community/mytask/signIn", code);
        assertEquals(SourceControllerMatcher.Kind.NONE, r.kind());
        assertNull(r.hit());
    }

    @Test
    void twoDifferentMethodsWithSameScoreAreAmbiguous() {
        var dup = List.of(
                s("GET", "/a/b", "A.java"),
                s("GET", "/a/b", "B.java"));
        assertEquals(SourceControllerMatcher.Kind.AMBIGUOUS,
                SourceControllerMatcher.match("GET", "/gw/a/b", dup).kind());
    }

    @Test
    void shorterPrefixWins() {
        var two = List.of(
                s("GET", "/b/c", "Short.java"),
                s("GET", "/a/b/c", "Long.java"));
        assertEquals("Long.java", SourceControllerMatcher.match("GET", "/gw/a/b/c", two).hit().file());
    }

    @Test
    void repoNameCandidates() {
        assertEquals(List.of("app-engine", "app_engine", "engine"),
                ControllerBackfillService.repoNameCandidates("app-engine"));
    }

    @Test
    void looksLikeSourceRepo_vueFrontendWithoutSrc(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"app-swift\"}");
        Files.createDirectory(dir.resolve("frontend"));
        assertTrue(ControllerBackfillService.looksLikeSourceRepo(dir));
        assertFalse(ControllerBackfillService.looksLikeSourceRepo(dir.resolve("missing")));
    }
}
