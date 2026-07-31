package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseDocDeferredReleaseTest {

    @Test
    void qaNotFollowVersionExcluded() {
        assertTrue(ReleaseDocService.isDeferredFromThisRelease(
                "QA：暂时不跟7.23日版本上线"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease(
                "QA: 暂时不跟 7.23 版本上线。"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease(
                "备注\n暂时不跟本次版本上线\n分支: x"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("不随本版本发版"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("本期不上线"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("本版本不上线"));
    }

    @Test
    void normalReleaseNotesNotExcluded() {
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(
                "eventcenter fch/20260714/APP-8742\n顺序发版"));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(
                "注意：先发 app-engine 再发客户端"));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(""));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(null));
    }
}
