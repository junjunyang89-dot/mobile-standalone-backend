package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayBranchesTest {

    @Test
    void 本地和远程同名分支只出一条且保持最近提交在前() {
        assertThat(ApiChangeAnalyzer.displayBranches(List.of(
                "origin/fch/20260804/APP-8928", "master", "origin/master", "fch/20260804/APP-8928", "local-only")))
                .containsExactly("fch/20260804/APP-8928", "master", "local-only");
    }

    @Test
    void 空列表不报错() {
        assertThat(ApiChangeAnalyzer.displayBranches(null)).isEmpty();
    }
}
