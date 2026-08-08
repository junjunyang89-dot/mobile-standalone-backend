package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 元素库 locator → 驱动 selector 的转换。这是纯函数，却直接决定每一次点击能不能命中，
 * 之前一行测试都没有（而且同一套规则在两个类里各写了一份、还不一致）。
 */
class UiFlowExecutorSelectorTest {

    @Test
    void 已带驱动前缀的原样返回() {
        assertEquals("id:com.app:id/btn", UiFlowExecutor.toDriverSelector("xpath", "id:com.app:id/btn"));
        assertEquals("xpath://*[@text='登录']", UiFlowExecutor.toDriverSelector("id", "xpath://*[@text='登录']"));
        assertEquals("accessibility id:submit", UiFlowExecutor.toDriverSelector(null, "accessibility id:submit"));
        assertEquals("ios class chain:**/XCUIElementTypeButton",
                UiFlowExecutor.toDriverSelector("xpath", "ios class chain:**/XCUIElementTypeButton"));
    }

    @Test
    void resourceId各种写法都归一到id前缀() {
        assertEquals("id:com.app:id/btn", UiFlowExecutor.toDriverSelector("id", "com.app:id/btn"));
        assertEquals("id:com.app:id/btn", UiFlowExecutor.toDriverSelector("resource-id", "com.app:id/btn"));
        assertEquals("id:com.app:id/btn", UiFlowExecutor.toDriverSelector("resourceId", "com.app:id/btn"));
        assertEquals("id:com.app:id/btn", UiFlowExecutor.toDriverSelector("  ID  ", "  com.app:id/btn  "));
    }

    @Test
    void accessibilityId的三种写法等价() {
        assertEquals("accessibility id:submit", UiFlowExecutor.toDriverSelector("accessibilityId", "submit"));
        assertEquals("accessibility id:submit", UiFlowExecutor.toDriverSelector("accessibility-id", "submit"));
        assertEquals("accessibility id:submit", UiFlowExecutor.toDriverSelector("accessibility id", "submit"));
    }

    @Test
    void iOS专有定位补对应前缀() {
        assertEquals("ios predicate:label == '登录'",
                UiFlowExecutor.toDriverSelector("iosPredicate", "label == '登录'"));
        assertEquals("ios predicate:label == '登录'",
                UiFlowExecutor.toDriverSelector("-ios predicate string", "label == '登录'"));
        assertEquals("ios class chain:**/XCUIElementTypeCell[1]",
                UiFlowExecutor.toDriverSelector("ios-class-chain", "**/XCUIElementTypeCell[1]"));
    }

    @Test
    void 文本类定位转成同时匹配text与name的xpath() {
        assertEquals("xpath://*[@text='登录' or @name='登录']",
                UiFlowExecutor.toDriverSelector("text", "登录"));
        assertEquals("xpath://*[@text='登录' or @name='登录']",
                UiFlowExecutor.toDriverSelector("name", "登录"));
    }

    @Test
    void 鸿蒙文本与id走hmdriver前缀() {
        assertEquals("text:登录", UiFlowExecutor.toDriverSelector("HARMONYOS", "text", "登录"));
        assertEquals("text:登录", UiFlowExecutor.toDriverSelector("HARMONYOS", "name", "登录"));
        assertEquals("id:loginBtn", UiFlowExecutor.toDriverSelector("HARMONYOS", "id", "loginBtn"));
        assertEquals("key:tab_home", UiFlowExecutor.toDriverSelector("HARMONYOS", "key", "tab_home"));
        assertEquals("desc:设置", UiFlowExecutor.toDriverSelector("HARMONYOS", "desc", "设置"));
        assertEquals("text:登录", UiFlowExecutor.toDriverSelector("HARMONYOS", null, "登录"));
    }

    @Test
    void 鸿蒙已有前缀原样返回() {
        assertEquals("text:登录", UiFlowExecutor.toDriverSelector("HARMONYOS", "xpath", "text:登录"));
        assertEquals("xpath://*[@text='登录']",
                UiFlowExecutor.toDriverSelector("HARMONYOS", "text", "//*[@text='登录']"));
    }

    @Test
    void 文本类定位若本身是xpath则不再包一层() {
        assertEquals("xpath://android.widget.TextView[@text='登录']",
                UiFlowExecutor.toDriverSelector("name", "//android.widget.TextView[@text='登录']"));
        assertEquals("xpath:(//android.widget.Button)[2]",
                UiFlowExecutor.toDriverSelector("text", "(//android.widget.Button)[2]"));
    }

    @Test
    void 坐标定位补coordinate前缀() {
        assertEquals("coordinate:100,200", UiFlowExecutor.toDriverSelector("coordinate", "100,200"));
        assertEquals("coordinate:100,200", UiFlowExecutor.toDriverSelector("tap", "coordinate:100,200"));
    }

    @Test
    void 类型缺省时按是否像路径猜xpath() {
        assertEquals("xpath://a/b", UiFlowExecutor.toDriverSelector(null, "//a/b"));
        assertEquals("plain_value", UiFlowExecutor.toDriverSelector("unknown-type", "plain_value"));
    }

    @Test
    void 空值安全() {
        assertEquals("", UiFlowExecutor.toDriverSelector("id", null));
        assertEquals("", UiFlowExecutor.toDriverSelector("id", "   "));
    }

    @Test
    void 滑动方向兼容中文与展示文案() {
        assertEquals("up", UiFlowExecutor.normalizeSwipeDirection("up（上滑）"));
        assertEquals("up", UiFlowExecutor.normalizeSwipeDirection("向上"));
        assertEquals("down", UiFlowExecutor.normalizeSwipeDirection("下滑"));
        assertEquals("left", UiFlowExecutor.normalizeSwipeDirection("LEFT"));
        assertEquals("right", UiFlowExecutor.normalizeSwipeDirection("向右滑动"));
    }

    @Test
    void 滑动方向缺省或无法识别时向上() {
        assertEquals("up", UiFlowExecutor.normalizeSwipeDirection(null));
        assertEquals("up", UiFlowExecutor.normalizeSwipeDirection(""));
        assertEquals("up", UiFlowExecutor.normalizeSwipeDirection("斜着滑"));
    }
}
