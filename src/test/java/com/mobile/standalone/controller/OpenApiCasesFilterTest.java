package com.mobile.standalone.controller;

import com.mobile.standalone.entity.Task;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiCasesFilterTest {

    @Test
    void stepCountUsesTopLevelArrayLength() {
        assertThat(OpenApiController.stepCount(null)).isZero();
        assertThat(OpenApiController.stepCount("[]")).isZero();
        assertThat(OpenApiController.stepCount("[{\"a\":1},{\"a\":2},{\"a\":3}]")).isEqualTo(3);
        assertThat(OpenApiController.stepCount("[1,2,3,4]")).isEqualTo(4);
        assertThat(OpenApiController.stepCount("not-json")).isZero();
    }

    @Test
    void trialPassedOnlyWhenDebugCompleted() {
        assertThat(OpenApiController.isTrialPassed(null)).isFalse();

        Task failed = new Task();
        failed.setStatus(Task.TaskStatus.FAILED);
        assertThat(OpenApiController.isTrialPassed(failed)).isFalse();

        Task ok = new Task();
        ok.setStatus(Task.TaskStatus.COMPLETED);
        assertThat(OpenApiController.isTrialPassed(ok)).isTrue();
    }
}
