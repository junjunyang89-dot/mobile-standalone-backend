package com.mobile.standalone.service;

import com.mobile.standalone.dto.ReleaseDocDtos.IssueRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("对外时间待定"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("备注：对外发版时间待定，先不进本版"));
        assertTrue(ReleaseDocService.isDeferredFromThisRelease("上线时间待定"));
        // 真实标题：话术写在 summary 里，不在备注
        assertTrue(ReleaseDocService.isDeferredFromThisRelease(
                "Dota2糖果车 - 新纪/张帆/曹键/靖怡 - 9.10提测，9.17上线，对外时间待定"));
    }

    @Test
    void normalReleaseNotesNotExcluded() {
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(
                "eventcenter fch/20260714/APP-8742\n顺序发版"));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(
                "注意：先发 app-engine 再发客户端"));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease("对外接口已联调"));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(""));
        assertFalse(ReleaseDocService.isDeferredFromThisRelease(null));
    }

    @Test
    void extractReasonFromTitle() {
        String reason = ReleaseDocService.extractDeferredReason(
                "Dota2糖果车 - 新纪/张帆 - 9.10提测，对外时间待定");
        assertNotNull(reason);
        assertTrue(reason.contains("对外时间待定"), reason);
    }

    @Test
    void confirmFollowVersionAddsRemark() {
        IssueRow row = new IssueRow();
        row.setDeferredCandidate(true);
        row.setDeferredReason("暂时不跟7.23日版本上线");
        row.setRemarks("无");
        ReleaseDocService.confirmFollowVersion(row);
        assertFalse(Boolean.TRUE.equals(row.getDeferredCandidate()));
        assertNull(row.getDeferredReason());
        assertTrue(row.getRemarks().contains(ReleaseDocService.REMARK_CONFIRM_FOLLOW_VERSION));
        assertTrue(row.getRemarks().contains("暂时不跟7.23日版本上线"));
    }
}
