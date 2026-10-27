package com.mobile.standalone.service.openapi;

import com.mobile.standalone.entity.Task;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mobile.standalone.service.openapi.OpenRunStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRunStatusTest {

    private static Task task(Task.TaskStatus s, String deviceId) {
        Task t = new Task();
        t.setStatus(s);
        t.setDeviceId(deviceId);
        return t;
    }

    @Test
    void 任务状态映射() {
        assertThat(ofTask(task(Task.TaskStatus.PENDING, null))).isEqualTo(QUEUED);
        // 已分到设备、等起跑：对外算在跑，不然对方会以为还在排队
        assertThat(ofTask(task(Task.TaskStatus.PENDING, "dev-1"))).isEqualTo(RUNNING);
        assertThat(ofTask(task(Task.TaskStatus.COMPLETED, "d"))).isEqualTo(PASSED);
        assertThat(ofTask(task(Task.TaskStatus.FAILED, "d"))).isEqualTo(FAILED);
        assertThat(ofTask(task(Task.TaskStatus.BLOCKED, "d"))).isEqualTo(BLOCKED);
        assertThat(ofTask(task(Task.TaskStatus.STOPPED, "d"))).isEqualTo(CANCELLED);
        assertThat(ofTask(null)).isEqualTo(ERROR);
    }

    @Test
    void 有没跑完的就不算结束() {
        assertThat(ofRun(List.of(QUEUED, QUEUED), false)).isEqualTo(QUEUED);
        assertThat(ofRun(List.of(PASSED, QUEUED), false)).isEqualTo(RUNNING);
        assertThat(ofRun(List.of(FAILED, RUNNING), false)).isEqualTo(RUNNING);
    }

    @Test
    void 全过或跳过算通过() {
        assertThat(ofRun(List.of(PASSED, SKIPPED), false)).isEqualTo(PASSED);
    }

    @Test
    void 有失败算失败_下发没建成也算失败() {
        assertThat(ofRun(List.of(PASSED, FAILED, BLOCKED), false)).isEqualTo(FAILED);
        assertThat(ofRun(List.of(PASSED, ERROR), false)).isEqualTo(FAILED);
    }

    /** 没跑成和用例失败要分开：一个查设备，一个查用例 */
    @Test
    void 只有没跑成的算BLOCKED() {
        assertThat(ofRun(List.of(PASSED, BLOCKED), false)).isEqualTo(BLOCKED);
    }

    @Test
    void 取消后立即算取消() {
        assertThat(ofRun(List.of(PASSED, RUNNING), true)).isEqualTo(CANCELLED);
    }

    @Test
    void 通过率分母只算通过和失败() {
        Map<String, Object> s = summary(List.of(PASSED, PASSED, PASSED, FAILED, BLOCKED, SKIPPED));
        assertThat(s.get("total")).isEqualTo(6);
        assertThat(s.get("passRate")).isEqualTo(75.0);
        assertThat(summary(List.of(BLOCKED)).get("passRate")).isNull();
    }

    @Test
    void 回调签名可被对方用同一把key复算() throws Exception {
        String sig = OpenRunService.sign("k-node", "1700000000.{\"a\":1}");
        assertThat(sig).hasSize(64).isEqualTo(OpenRunService.sign("k-node", "1700000000.{\"a\":1}"));
        assertThat(sig).isNotEqualTo(OpenRunService.sign("other", "1700000000.{\"a\":1}"));
    }
}
