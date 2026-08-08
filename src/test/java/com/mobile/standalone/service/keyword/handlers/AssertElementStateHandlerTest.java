package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.service.UiFlowExecutionException;
import com.mobile.standalone.service.keyword.KeywordContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断言元素状态。
 * <p>
 * 存在的理由是勾选类控件<b>不幂等</b>：「只点一次」的点击对已勾选的框会变成取消勾选。
 * 那种失败极具迷惑性——日志显示「点击成功」，下一步却没反应，人会去查下一步的元素。
 * 所以这组用例里最要紧的一批，都是在盯「什么情况下<b>不</b>该点」。
 */
class AssertElementStateHandlerTest {

    private static final String POLICY_XML_FALSE = """
            <hierarchy>
              <android.widget.ImageView resource-id="login_policy_checkbox"
                checked="false" checkable="true" />
              <android.widget.Button resource-id="login_send_code_btn" enabled="false" />
            </hierarchy>
            """;

    private static final String POLICY_XML_TRUE = """
            <hierarchy>
              <android.widget.ImageView resource-id="login_policy_checkbox"
                checked="true" checkable="true" />
              <android.widget.Button resource-id="login_send_code_btn" enabled="true" />
            </hierarchy>
            """;

    private final AssertElementStateHandler handler = new AssertElementStateHandler();
    private FakeRuntime runtime;
    private FakeDriver driver;
    private List<ExecutionLogEntryDto> logs;

    @BeforeEach
    void setUp() {
        runtime = new FakeRuntime();
        // 按 resource-id 从 pageSource 找节点，selector 必须是 id 形态
        runtime.selectorPrefix = "id:";
        driver = new FakeDriver();
        logs = new ArrayList<>();
    }

    private KeywordContext ctx(String elementKey, String expectState, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>(extra);
        params.put("expectState", expectState);
        // 复判等待在测试里没必要真睡
        params.putIfAbsent("recheckDelayMs", 0L);
        return KeywordContext.builder()
                .rawCode("ASSERT_ELEMENT_STATE")
                .elementKey(elementKey)
                .stepParams(params)
                .driver(driver)
                .deviceId("dev-1")
                .platform("ANDROID")
                .uiAppId("app-1")
                .execParams(Map.of())
                .logs(logs)
                .pathNo("4.2")
                .state(new KeywordContext.RunState(false, null))
                .runtime(runtime)
                .build();
    }

    private KeywordContext policy(String expectState, boolean fixByClick) {
        return ctx("login_policy_checkbox", expectState, Map.of("fixByClick", fixByClick));
    }

    private String lastMessage() {
        return logs.get(logs.size() - 1).getMessage();
    }

    // ==================== 基本判定 ====================

    @Test
    void 状态符合时通过且不点击() throws Exception {
        driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(policy("checked", false));

        assertTrue(lastMessage().contains("已勾选"), lastMessage());
        assertEquals(0, driver.clicks, "已是期望状态就不该再点");
    }

    @Test
    void 状态不符且不允许纠正时失败() {
        driver.pageSourceXml = POLICY_XML_FALSE;

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", false)));

        // 细节进日志；异常文案是简短版，会落进任务列表所以不带细节
        assertTrue(lastMessage().contains("未勾选"), lastMessage());
        assertEquals(0, driver.clicks);
    }

    @Test
    void 支持未勾选与禁用等反向状态() throws Exception {
        driver.pageSourceXml = POLICY_XML_FALSE;

        handler.execute(policy("unchecked", false));
        assertTrue(lastMessage().contains("未勾选"), lastMessage());

        handler.execute(ctx("login_send_code_btn", "disabled", Map.of()));
        assertTrue(lastMessage().contains("禁用"), lastMessage());
    }

    // ==================== 幂等：什么情况下不该点 ====================

    /** 已经勾上还去点，就把它取消了。这正是直接用 CLICK 会踩的坑 */
    @Test
    void 已是期望状态时即使允许纠正也不点() throws Exception {
        driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(policy("checked", true));

        assertEquals(0, driver.clicks, "已勾选还去点，会把它取消掉");
    }

    /**
     * 读不准时也不能点：万一它其实已经达标，点下去就成了反向操作。
     * 宁可失败并说清楚，也不要赌。
     */
    @Test
    void 状态读不准时跳过点击并说明原因() {
        driver.pageSourceXml = "";

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", true)));

        assertEquals(0, driver.clicks);
        assertTrue(lastMessage().contains("读不准"), lastMessage());
    }

    @Test
    void 允许纠正时先点再复判() throws Exception {
        driver.pageSourceXml = POLICY_XML_FALSE;
        driver.onClick = () -> driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(policy("checked", true));

        assertEquals(1, driver.clicks);
        assertTrue(lastMessage().contains("点击后已变为"), lastMessage());
    }

    @Test
    void 点了仍不符则失败() {
        driver.pageSourceXml = POLICY_XML_FALSE;

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", true)));

        assertEquals(1, driver.clicks);
        assertTrue(lastMessage().contains("点击后仍不是"), lastMessage());
    }

    // ==================== 读 A 点 B ====================

    /**
     * 协议框是自定义 ImageView，checked 不可信；而「发送验证码」的 enabled 语义清晰，
     * 且它点亮本身就证明手机号和协议都到位了。所以读发码按钮、点协议框。
     */
    @Test
    void 读发码按钮状态点协议框纠正() throws Exception {
        driver.pageSourceXml = POLICY_XML_FALSE;
        driver.onClick = () -> driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(ctx("login_send_code_btn", "enabled",
                Map.of("fixByClick", true, "fixElementKey", "login_policy_checkbox")));

        assertEquals(1, driver.clicks);
        assertEquals("id:login_policy_checkbox", driver.clickedSelectors.get(0),
                "该点的是协议框，不是被判断的发码按钮");
        assertTrue(lastMessage().contains("点击后已变为"), lastMessage());
    }

    @Test
    void 发码按钮已点亮时一次都不点() throws Exception {
        driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(ctx("login_send_code_btn", "enabled",
                Map.of("fixByClick", true, "fixElementKey", "login_policy_checkbox")));

        assertEquals(0, driver.clicks, "已点亮说明协议已勾，再点协议框反而会把它取消");
    }

    @Test
    void 未指定纠正目标时点自己() throws Exception {
        driver.pageSourceXml = POLICY_XML_FALSE;
        driver.onClick = () -> driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(policy("checked", true));

        assertEquals("id:login_policy_checkbox", driver.clickedSelectors.get(0));
    }

    // ==================== 属性读取规则 ====================

    /**
     * 自定义控件的 getAttribute 常返回空串或恒为 false，所以属性层<b>只采信 true</b>。
     * 采信单独的 false 会让这类控件永远被判成未勾选，然后每次都去点——正好把已勾的取消掉。
     */
    @Test
    void 属性上单独一个false不足以判未勾选() {
        driver.attributes.put("checked", "false");

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", false)));

        assertTrue(lastMessage().contains("读不到"), lastMessage());
    }

    @Test
    void 属性上的true可以采信() throws Exception {
        driver.attributes.put("checked", "true");

        handler.execute(policy("checked", false));

        assertTrue(lastMessage().contains("已勾选"), lastMessage());
    }

    /** iOS 的勾选态挂在 value 上（"1"/"0"），不认的话 iOS 流程全废 */
    @Test
    void ios的value属性也能读出勾选态() throws Exception {
        driver.attributes.put("value", "1");

        handler.execute(policy("checked", false));

        assertTrue(lastMessage().contains("已勾选"), lastMessage());
    }

    /** enabled 是标准属性，false 可信——不像自定义控件的 checked */
    @Test
    void enabled属性的false可以采信() throws Exception {
        driver.attrOn("login_send_code_btn", "enabled", "false");

        handler.execute(ctx("login_send_code_btn", "disabled", Map.of()));

        assertTrue(lastMessage().contains("禁用"), lastMessage());
    }

    // ==================== 视觉判断 ====================

    /** 颜色判断脆（换主题/深色模式/改版都会失效），必须显式开启，不能悄悄兜底 */
    @Test
    void 读不到状态时提示可选的兜底手段() {
        driver.pageSourceXml = "";

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", false)));

        assertTrue(lastMessage().contains("截图"), "应提示还有视觉判断这条路：" + lastMessage());
    }

    // ==================== 控件树里的 false 什么时候可信 ====================

    /**
     * 取自真实失败现场：某 APP 的协议勾选是 {@code ImageView checkable="false" checked="false"}，
     * 蓝色勾只是换了张图，控件树上永远读不到状态。
     * <p>
     * Android 每个节点都自带 {@code checked="false"}，那是默认值不是「未勾选」。
     * 曾经把它当未勾选，于是断言去点一下「纠正」，正好把上一步刚勾好的取消掉——
     * 而日志里每一步都显示成功。这组用例就是钉死这个回归。
     */
    private static final String CUSTOM_IMAGEVIEW_XML = """
            <hierarchy>
              <android.widget.ImageView resource-id="login_policy_checkbox"
                checkable="false" checked="false" selected="false" clickable="true" enabled="true" />
            </hierarchy>
            """;

    @Test
    void checkable为false时checked的false不可信() {
        driver.pageSourceXml = CUSTOM_IMAGEVIEW_XML;

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", false)));

        assertTrue(lastMessage().contains("读不到"), lastMessage());
    }

    /** 最关键的一条：读不准就绝不点，否则会把上一步勾好的取消掉 */
    @Test
    void 自定义勾选控件读不准时不点击() {
        driver.pageSourceXml = CUSTOM_IMAGEVIEW_XML;

        assertThrows(UiFlowExecutionException.class, () -> handler.execute(policy("checked", true)));

        assertEquals(0, driver.clicks, "读不到状态还去点，会把已勾选的取消掉");
        assertTrue(lastMessage().contains("读不准"), lastMessage());
    }

    /** checked=true 任何时候都采信：控件敢报 true 就不会是默认值 */
    @Test
    void checkable为false但checked为true仍采信() throws Exception {
        driver.pageSourceXml = """
                <hierarchy>
                  <android.widget.ImageView resource-id="login_policy_checkbox"
                    checkable="false" checked="true" />
                </hierarchy>
                """;

        handler.execute(policy("checked", true));

        assertTrue(lastMessage().contains("已勾选"), lastMessage());
        assertEquals(0, driver.clicks);
    }

    @Test
    void 未知状态名退回勾选判断() throws Exception {
        driver.pageSourceXml = POLICY_XML_TRUE;

        handler.execute(policy("whatever", false));

        assertTrue(lastMessage().contains("已勾选"), lastMessage());
    }
}
