package com.mobile.standalone.service.releasemerge;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestingMergeServiceTest {

    @Test
    void targetIsTesting() {
        assertThat(TestingMergeService.TARGET).isEqualTo("testing");
    }

    @Test
    void issueKeysFromPaste() {
        assertThat(ReleaseBatchService.issueKeys("请看 http://jira/browse/APP-9001 和 APP-9002"))
                .containsExactly("APP-9001", "APP-9002");
    }

    @Test
    void resolveTargetsFromNotesMatchesProject() {
        ApiProject p = new ApiProject();
        p.setCode("app-admin");
        p.setName("后台");
        p.setKind("BACKEND");
        List<Target> targets = ReleaseBatchService.resolveTargets(
                "app-admin：agent/2026-09-17/x", List.of(p));
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getProjectCode()).isEqualTo("app-admin");
        assertThat(targets.get(0).getBranch()).isEqualTo("agent/2026-09-17/x");
    }

    @Test
    void multiProjectSharedBranch() {
        ApiProject a = new ApiProject();
        a.setCode("comment-service");
        a.setName("评论");
        a.setKind("BACKEND");
        ApiProject b = new ApiProject();
        b.setCode("app-admin");
        b.setName("后台");
        b.setKind("BACKEND");
        List<Target> targets = ReleaseBatchService.resolveTargets(
                "comment-service、app-admin：agent/x", List.of(a, b));
        assertThat(targets).hasSize(2);
        assertThat(targets).extracting(Target::getBranch).containsOnly("agent/x");
    }
}
