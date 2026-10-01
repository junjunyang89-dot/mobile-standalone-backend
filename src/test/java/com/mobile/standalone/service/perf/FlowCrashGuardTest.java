package com.mobile.standalone.service.perf;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程顺带盯崩溃。要紧的是三条：
 * 崩溃判失败、ANR 只记红；没采集时必须说出来（不能读成「没崩」）；
 * 按设备时间切窗口，读不到时间才退回清缓冲。
 */
class FlowCrashGuardTest {

    private static final String PKG = "com.pwrd.steam.esports";

    /** 不碰 adb：记下被怎么调用，返回预设的结果 */
    static class FakeMonitor extends CrashMonitor {
        String markResult = "09-29 19:30:00.000";
        List<CrashLogParser.Incident> incidents = List.of();
        boolean cleared;
        String collectedSince = "未调用";
        int calls;

        @Override
        public String mark(String deviceId) {
            calls++;
            return markResult;
        }

        @Override
        public void start(String deviceId) {
            calls++;
            cleared = true;
        }

        @Override
        public List<CrashLogParser.Incident> collectSince(String deviceId, String packageName, String since) {
            calls++;
            collectedSince = since;
            return incidents;
        }
    }

    private static Device device() {
        Device d = new Device();
        d.setId("be469eba");
        return d;
    }

    private static CrashLogParser.Incident incident(String type, String title) {
        CrashLogParser.Incident i = new CrashLogParser.Incident();
        i.setType(type);
        i.setProcess(PKG);
        i.setTitle(title);
        i.setStack("at com.pwrd.steam.esports.ui.MarketBuyActivity.onResume(MarketBuyActivity.kt:88)");
        return i;
    }

    @Test
    void 崩溃写成红色记录带堆栈_并且判任务失败() {
        FakeMonitor m = new FakeMonitor();
        m.incidents = List.of(incident("CRASH", "java.lang.NullPointerException: price is null"));
        FlowCrashGuard g = new FlowCrashGuard(m);
        List<ExecutionLogEntryDto> logs = new ArrayList<>();

        List<CrashLogParser.Incident> got = g.finish(g.begin(device(), "ANDROID", PKG), logs);

        assertThat(m.collectedSince).isEqualTo("09-29 19:30:00.000");
        assertThat(m.cleared).isFalse();
        assertThat(logs.get(0).getStep()).isEqualTo(FlowCrashGuard.CRASH_STEP);
        assertThat(logs.get(0).getStatus()).isEqualToIgnoringCase("fail");
        assertThat(logs.get(1).getMessage()).contains("MarketBuyActivity.kt:88");
        assertThat(FlowCrashGuard.failureMessage(got))
                .contains("1 次").contains("NullPointerException");
        assertThat(FlowCrashGuard.appCrashed(logs)).isTrue();
    }

    @Test
    void 进程意外退出单独成行_也判失败() {
        FakeMonitor m = new FakeMonitor();
        m.incidents = List.of(incident("PROCESS_DIED", "WebView 渲染进程崩溃没人处理，连带整个应用退出（pid 28817）"));
        FlowCrashGuard g = new FlowCrashGuard(m);
        List<ExecutionLogEntryDto> logs = new ArrayList<>();

        List<CrashLogParser.Incident> got = g.finish(g.begin(device(), "ANDROID", PKG), logs);

        assertThat(logs.get(0).getStep()).isEqualTo(FlowCrashGuard.DIED_STEP);
        assertThat(FlowCrashGuard.failureMessage(got)).contains("WebView");
        assertThat(FlowCrashGuard.appCrashed(logs)).isTrue();
        assertThat(FlowCrashGuard.isCrashEntry(logs.get(0))).isTrue();
    }

    @Test
    void 只有ANR时记红但不判失败() {
        FakeMonitor m = new FakeMonitor();
        m.incidents = List.of(incident("ANR", "ANR in com.pwrd.steam.esports"));
        FlowCrashGuard g = new FlowCrashGuard(m);
        List<ExecutionLogEntryDto> logs = new ArrayList<>();

        List<CrashLogParser.Incident> got = g.finish(g.begin(device(), "ANDROID", PKG), logs);

        assertThat(logs.get(0).getStep()).isEqualTo(FlowCrashGuard.ANR_STEP);
        assertThat(logs.get(0).getStatus()).isEqualToIgnoringCase("fail");
        assertThat(FlowCrashGuard.failureMessage(got)).isNull();
        assertThat(FlowCrashGuard.appCrashed(logs)).isFalse();
    }

    @Test
    void 没崩时明确写没发现() {
        FakeMonitor m = new FakeMonitor();
        FlowCrashGuard g = new FlowCrashGuard(m);
        List<ExecutionLogEntryDto> logs = new ArrayList<>();

        g.finish(g.begin(device(), "ANDROID", PKG), logs);

        assertThat(logs).singleElement().satisfies(e -> {
            assertThat(e.getMessage()).isEqualTo("运行期间未发现崩溃 / ANR");
            assertThat(e.getStatus()).isEqualToIgnoringCase("success");
        });
    }

    @Test
    void iOS和鸿蒙不采_但要写明未采集_不碰设备() {
        for (String platform : List.of("IOS", "HARMONYOS")) {
            FakeMonitor m = new FakeMonitor();
            FlowCrashGuard g = new FlowCrashGuard(m);
            List<ExecutionLogEntryDto> logs = new ArrayList<>();

            g.finish(g.begin(device(), platform, PKG), logs);

            assertThat(m.calls).as(platform).isZero();
            assertThat(logs).singleElement().satisfies(e ->
                    assertThat(e.getMessage()).contains("本次未采集").contains("不代表没有崩溃"));
        }
    }

    @Test
    void 不知道包名时不采_否则会把别的应用的崩溃算进来() {
        FakeMonitor m = new FakeMonitor();
        FlowCrashGuard g = new FlowCrashGuard(m);
        List<ExecutionLogEntryDto> logs = new ArrayList<>();

        g.finish(g.begin(device(), "ANDROID", " "), logs);

        assertThat(m.calls).isZero();
        assertThat(logs.get(0).getMessage()).contains("不知道被测包名");
    }

    @Test
    void 读不到设备时间就退回清缓冲再全量取() {
        FakeMonitor m = new FakeMonitor();
        m.markResult = null;
        FlowCrashGuard g = new FlowCrashGuard(m);

        g.finish(g.begin(device(), "POG_ANDROID", PKG), new ArrayList<>());

        assertThat(m.cleared).isTrue();
        assertThat(m.collectedSince).isNull();
    }

    @Test
    void 归因找失败步骤时跳过崩溃记录() {
        ExecutionLogEntryDto step = ExecutionLogEntryDto.fail("点击 购买", "元素不存在", null);
        ExecutionLogEntryDto crash = ExecutionLogEntryDto.fail(FlowCrashGuard.CRASH_STEP, "NPE", null);
        ExecutionLogEntryDto anr = ExecutionLogEntryDto.fail(FlowCrashGuard.ANR_STEP, "ANR", null);

        assertThat(FlowCrashGuard.isCrashEntry(step)).isFalse();
        assertThat(FlowCrashGuard.isCrashEntry(crash)).isTrue();
        assertThat(FlowCrashGuard.isCrashEntry(anr)).isTrue();
    }
}
