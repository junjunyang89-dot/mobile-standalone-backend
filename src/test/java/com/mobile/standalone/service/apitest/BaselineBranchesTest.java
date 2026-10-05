package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineBranchesTest {

    @Test
    void 测试环境和主干都算基线() {
        assertThat(BaselineBranches.isBaseline("testing", null)).isTrue();
        assertThat(BaselineBranches.isBaseline("origin/testing", null)).isTrue();
        assertThat(BaselineBranches.isBaseline("master", null)).isTrue();
        assertThat(BaselineBranches.isBaseline("Main", null)).isTrue();
    }

    @Test
    void 项目自己配的基线也算() {
        assertThat(BaselineBranches.isBaseline("release", "release")).isTrue();
        assertThat(BaselineBranches.isBaseline("origin/release", "release")).isTrue();
    }

    @Test
    void 需求分支不算() {
        assertThat(BaselineBranches.isBaseline("feature/APP-1234-coupon", "master")).isFalse();
        assertThat(BaselineBranches.isBaseline("testing-fix", null)).isFalse();
        assertThat(BaselineBranches.isBaseline("", null)).isFalse();
        assertThat(BaselineBranches.isBaseline(null, null)).isFalse();
    }
}
