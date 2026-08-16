package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.UiFlowMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 扫描本身在 {@link FlowDataScannerTest} 里验过了，这里验的是<b>接线</b>：
 * 步骤 JSON 是嵌套的（分支、竞速、组件），走树的时候漏掉一层，
 * 扫描器再准也是返回空列表——而空列表和「这条流程干净」长得一模一样。
 */
class UiFlowEnvDataScanTest {

    private UiFlowService serviceWith(String steps) {
        UiFlowMapper mapper = mock(UiFlowMapper.class);
        UiFlow flow = new UiFlow();
        flow.setId("uif-1");
        flow.setName("登录");
        flow.setSteps(steps);
        when(mapper.findById(anyString())).thenReturn(flow);
        return new UiFlowService(mapper, null, null);
    }

    @Test
    void 扫出顶层步骤里写死的手机号() {
        String steps = """
                [
                  {"stepNo":"1","keywordCode":"INPUT","params":{"text":"13800138000"}},
                  {"stepNo":"2","keywordCode":"CLICK","params":{"timeoutMs":"5000"}}
                ]
                """;
        List<FlowDataScanner.Hit> hits = serviceWith(steps).scanEnvData("uif-1");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getValue()).isEqualTo("13800138000");
        // 序号按数组下标给，人要照着这个去流程里找那一步
        assertThat(hits.get(0).getStepNo()).isEqualTo("1");
        // timeoutMs 不该被扫——误报几条之后这份清单就没人看了
    }

    @Test
    void 分支里的写死数据也要扫到() {
        // 分支/竞速的子步骤是嵌套数组，只扫顶层会漏掉整个 else 分支
        String steps = """
                [
                  {"keywordCode":"IF","branches":[
                    {"then":[{"keywordCode":"INPUT","params":{"keyword":"13900139000"}}]}
                  ]}
                ]
                """;
        List<FlowDataScanner.Hit> hits = serviceWith(steps).scanEnvData("uif-1");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getValue()).isEqualTo("13900139000");
    }

    @Test
    void 按文案定位的步骤要提示改写() {
        // 换环境跑挂在这里最难查：报错是「找不到元素」，但元素在，只是那条新闻不在
        String steps = """
                [
                  {"keywordCode":"CLICK","locatorType":"TEXT","locatorValue":"XX战队晋级四强"}
                ]
                """;
        List<FlowDataScanner.Hit> hits = serviceWith(steps).scanEnvData("uif-1");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getKind()).isEqualTo("LOCATOR");
        assertThat(hits.get(0).getReason()).contains("按位置选");
    }

    @Test
    void 已经用变量的不再报() {
        String steps = """
                [{"keywordCode":"INPUT","params":{"text":"${loginPhone}"}}]
                """;
        assertThat(serviceWith(steps).scanEnvData("uif-1")).isEmpty();
    }

    @Test
    void 空流程不炸() {
        assertThat(serviceWith("[]").scanEnvData("uif-1")).isEmpty();
        // 步骤 JSON 坏掉时返回空而不是抛——这是个辅助检查，不该挡住流程页面
        assertThat(serviceWith("{ 坏掉的 json").scanEnvData("uif-1")).isEmpty();
    }
}
