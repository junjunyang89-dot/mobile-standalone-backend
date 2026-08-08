package com.mobile.standalone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.entity.Task;
import com.mobile.standalone.entity.TaskFailureDiagnosis;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.entity.UiFlow;
import com.mobile.standalone.mapper.TaskExecutionMapper;
import com.mobile.standalone.mapper.TaskFailureDiagnosisMapper;
import com.mobile.standalone.mapper.TaskMapper;
import com.mobile.standalone.util.ExecutionLogJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采纳 AI 候选定位。
 * <p>
 * 这一步是整条链路里唯一会改动执行根基的操作——定位一旦被错误覆盖，后面每条引用该元素的
 * 流程都会跟着错。所以本组用例的重点全在「改错了还能救回来」：旧定位必须留在备用里。
 */
class ApplySuggestedLocatorTest {

    private static final String TASK_ID = "task-1";
    private static final String DIAG_ID = "diag-1";
    private static final String UI_APP_ID = "app-demo";

    private FailureDiagnosisService service;
    private TaskFailureDiagnosisMapper diagnosisMapper;
    private UiElementService uiElementService;
    private TaskFailureDiagnosis diagnosis;
    private UiElement element;

    @BeforeEach
    void setUp() {
        TaskMapper taskMapper = Mockito.mock(TaskMapper.class);
        TaskExecutionMapper executionMapper = Mockito.mock(TaskExecutionMapper.class);
        diagnosisMapper = Mockito.mock(TaskFailureDiagnosisMapper.class);
        UiFlowService uiFlowService = Mockito.mock(UiFlowService.class);
        uiElementService = Mockito.mock(UiElementService.class);

        Task task = new Task();
        task.setId(TASK_ID);
        task.setPlatform(Task.Platform.IOS);
        task.setConfig("{\"flowId\":\"flow-1\"}");
        Mockito.when(taskMapper.findById(TASK_ID)).thenReturn(task);

        UiFlow flow = new UiFlow();
        flow.setId("flow-1");
        flow.setAppId(UI_APP_ID);
        Mockito.when(uiFlowService.get("flow-1")).thenReturn(flow);

        element = new UiElement();
        element.setId("el-1");
        element.setNameEn("login_submit_btn");
        element.setPlatform("IOS");
        element.setLocatorType("xpath");
        element.setLocatorValue("//XCUIElementTypeStaticText[@name=\"赛事高光 · 26分钟前\"]");
        Mockito.when(uiElementService.findActiveByKey(UI_APP_ID, "IOS", "login_submit_btn"))
                .thenReturn(element);
        Mockito.when(uiElementService.update(ArgumentMatchers.eq("el-1"), ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(1, UiElement.class));

        diagnosis = new TaskFailureDiagnosis();
        diagnosis.setId(DIAG_ID);
        diagnosis.setTaskId(TASK_ID);
        diagnosis.setElementKey("login_submit_btn");
        diagnosis.setCategory("ELEMENT_BROKEN");
        diagnosis.setSuggestedLocatorType("xpath");
        diagnosis.setSuggestedLocatorValue("//XCUIElementTypeButton[@name=\"点击登录\"]");
        Mockito.when(diagnosisMapper.findById(DIAG_ID)).thenReturn(diagnosis);

        service = new FailureDiagnosisService(taskMapper, executionMapper, diagnosisMapper,
                uiFlowService, uiElementService);
    }

    private UiElement capturePatch() {
        ArgumentCaptor<UiElement> patch = ArgumentCaptor.forClass(UiElement.class);
        Mockito.verify(uiElementService).update(ArgumentMatchers.eq("el-1"), patch.capture());
        return patch.getValue();
    }

    @Test
    void 候选定位写成新的主定位() {
        service.applySuggestedLocator(DIAG_ID, "yangjun");

        UiElement patch = capturePatch();
        assertEquals("//XCUIElementTypeButton[@name=\"点击登录\"]", patch.getLocatorValue());
        assertEquals("xpath", patch.getLocatorType());
    }

    /**
     * 这条是本组最重要的用例。AI 判错时，执行仍会回退到旧定位试一次，人也还能看出改之前是什么；
     * 覆盖掉的话一次误判就没有退路了。
     */
    @Test
    void 旧定位被降级进备用而不是丢掉() throws Exception {
        service.applySuggestedLocator(DIAG_ID, "yangjun");

        JsonNode fallbacks = ExecutionLogJson.mapper().readTree(capturePatch().getLocatorFallbacks());
        assertTrue(fallbacks.isArray());
        assertEquals("//XCUIElementTypeStaticText[@name=\"赛事高光 · 26分钟前\"]",
                fallbacks.get(0).path("locatorValue").asText());
        assertEquals("xpath", fallbacks.get(0).path("locatorType").asText());
    }

    /** 旧定位要排在最前：它刚刚还是主定位，是最可能仍然管用的那条 */
    @Test
    void 原有备用定位保留在旧主定位之后() throws Exception {
        element.setLocatorFallbacks(
                "[{\"locatorType\":\"id\",\"locatorValue\":\"login_btn\"}]");

        service.applySuggestedLocator(DIAG_ID, "yangjun");

        JsonNode fallbacks = ExecutionLogJson.mapper().readTree(capturePatch().getLocatorFallbacks());
        assertEquals(2, fallbacks.size());
        assertTrue(fallbacks.get(0).path("locatorValue").asText().contains("赛事高光"));
        assertEquals("login_btn", fallbacks.get(1).path("locatorValue").asText());
    }

    /** 新主定位本来就在备用里的话，留着就成了自己回退到自己 */
    @Test
    void 备用里与旧主定位重复的条目被去掉() throws Exception {
        element.setLocatorFallbacks(
                "[{\"locatorType\":\"xpath\",\"locatorValue\":\"//XCUIElementTypeStaticText[@name=\\\"赛事高光 · 26分钟前\\\"]\"}]");

        service.applySuggestedLocator(DIAG_ID, "yangjun");

        JsonNode fallbacks = ExecutionLogJson.mapper().readTree(capturePatch().getLocatorFallbacks());
        assertEquals(1, fallbacks.size());
    }

    @Test
    void 采纳后打上时间戳() {
        service.applySuggestedLocator(DIAG_ID, "yangjun");

        Mockito.verify(diagnosisMapper).markApplied(ArgumentMatchers.eq(DIAG_ID),
                ArgumentMatchers.any(LocalDateTime.class));
    }

    /** 重复采纳会把上一次刚降级的旧定位再挤下去一层，越点越乱 */
    @Test
    void 已采纳过的不再重复写入() {
        diagnosis.setAppliedAt(LocalDateTime.now());

        assertThrows(IllegalStateException.class,
                () -> service.applySuggestedLocator(DIAG_ID, "yangjun"));
        Mockito.verify(uiElementService, Mockito.never())
                .update(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void 没有候选定位时拒绝() {
        diagnosis.setSuggestedLocatorValue(null);

        assertThrows(IllegalStateException.class,
                () -> service.applySuggestedLocator(DIAG_ID, "yangjun"));
    }

    /** 没有 elementKey 就不知道该改哪一行，不能瞎猜 */
    @Test
    void 没有关联元素时拒绝() {
        diagnosis.setElementKey(null);

        assertThrows(IllegalStateException.class,
                () -> service.applySuggestedLocator(DIAG_ID, "yangjun"));
    }

    @Test
    void 归因记录不存在时报错() {
        assertThrows(IllegalArgumentException.class,
                () -> service.applySuggestedLocator("nope", "yangjun"));
    }

    /** AI 没给定位类型时沿用原来的，不要凭空改成别的类型 */
    @Test
    void 未给定位类型时沿用原类型() {
        diagnosis.setSuggestedLocatorType(null);
        element.setLocatorType("id");

        service.applySuggestedLocator(DIAG_ID, "yangjun");

        assertEquals("id", capturePatch().getLocatorType());
    }

    @Test
    void 返回更新后的元素() {
        assertNotNull(service.applySuggestedLocator(DIAG_ID, "yangjun"));
    }
}
