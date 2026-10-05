package com.mobile.standalone.service.releasebatch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchFinderTest {

    @Test
    void parsesLsRemoteHeads() {
        String out = "a1b2\trefs/heads/master\nc3d4\trefs/heads/feature/APP-1234-comment\n";
        assertEquals(List.of("master", "feature/APP-1234-comment"), BranchFinder.heads(out));
    }

    @Test
    void sameNameFirstThenIssueKey() {
        List<String> heads = List.of("master", "feature/app-1234-h5", "release/10.9", "APP-1234");
        assertEquals(List.of("release/10.9", "feature/app-1234-h5", "APP-1234"),
                BranchFinder.pick(heads, Set.of("release/10.9"), "APP-1234"));
    }

    @Test
    void issueKeyMustNotBePrefixOfLongerNumber() {
        List<String> heads = List.of("feature/APP-12345", "feature/XAPP-1234", "hotfix/APP-1234_fix");
        assertEquals(List.of("hotfix/APP-1234_fix"), BranchFinder.pick(heads, Set.of(), "APP-1234"));
    }

    @Test
    void capsHitsPerRepo() {
        List<String> heads = List.of("a/APP-1", "b/APP-1", "c/APP-1", "d/APP-1");
        assertEquals(3, BranchFinder.pick(heads, Set.of(), "APP-1").size());
    }
}
