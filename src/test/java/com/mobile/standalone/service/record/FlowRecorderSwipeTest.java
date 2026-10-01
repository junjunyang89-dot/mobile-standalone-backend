package com.mobile.standalone.service.record;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowRecorderSwipeTest {

    @Test
    void 往上拖是上滑() {
        assertThat(FlowRecorder.inferSwipeDirection(540, 1800, 540, 600)).isEqualTo("up");
    }

    @Test
    void 斜着往上仍认上滑() {
        assertThat(FlowRecorder.inferSwipeDirection(500, 1700, 560, 800)).isEqualTo("up");
    }

    @Test
    void 横向优先左右() {
        assertThat(FlowRecorder.inferSwipeDirection(800, 1000, 200, 1040)).isEqualTo("left");
    }
}
