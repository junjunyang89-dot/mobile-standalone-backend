package com.mobile.standalone.service.driver;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 平台驱动接口
 * 定义所有平台驱动需要实现的方法
 */
public interface PlatformDriver {
    
    /**
     * 启动应用
     */
    boolean launchApp(String deviceId, String packageName);
    
    /**
     * 点击坐标
     */
    boolean click(String deviceId, int x, int y);
    
    /**
     * 点击元素
     */
    boolean clickElement(String deviceId, String selector);
    
    /**
     * 滑动
     */
    boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration);
    
    /**
     * 输入文本
     */
    boolean inputText(String deviceId, String text);
    
    /**
     * 输入文本到指定元素
     */
    boolean inputTextToElement(String deviceId, String selector, String text);
    
    /**
     * 截图
     */
    String takeScreenshot(String deviceId, String savePath);
    
    /**
     * 获取页面元素树
     */
    String getPageSource(String deviceId);

    /**
     * 获取当前前台页面标识（如 Android 的 Activity 名），用于性能测试等记录「在哪个页面操作」。
     * 不支持时返回 null。
     */
    default String getCurrentActivity(String deviceId) {
        return null;
    }
    
    /**
     * 等待元素出现
     */
    boolean waitForElement(String deviceId, String selector, int timeoutSeconds);
    
    /**
     * 检查元素是否存在
     */
    boolean isElementPresent(String deviceId, String selector);
    
    /**
     * 获取元素文本
     */
    String getElementText(String deviceId, String selector);
    
    /**
     * 获取元素属性
     */
    String getElementAttribute(String deviceId, String selector, String attribute);

    /**
     * 获取当前会话信息（如 Appium 的 capabilities），用于兼容性测试等产出真实结果数据。
     * 非 Appium 驱动可返回 null。
     */
    default Map<String, Object> getSessionInfo(String deviceId) {
        return null;
    }

    /**
     * 模拟按设备返回键（Android BACK、iOS 返回手势等）。
     * 当页面元素无法定位时可用此兜底返回上一页。
     * 不支持时返回 false。
     */
    default boolean pressBack(String deviceId) {
        return false;
    }

    /**
     * 获取元素中心坐标 [centerX, centerY]，用于在指定位置执行滑动等操作。
     * 不支持或元素不存在时返回 null。
     */
    default int[] getElementCenter(String deviceId, String selector) {
        return null;
    }

    /**
     * 获取所有匹配选择器的元素的文本列表，用于提取列表数据（如参赛队伍等）。
     * 不支持时返回空列表。
     */
    default List<String> getElementsTextList(String deviceId, String selector) {
        return Collections.emptyList();
    }

    /**
     * 先定位父元素，再取各父元素下子元素的文本列表。用于 ListView/View[1]..View[N] 结构。
     * 不支持时返回空列表。
     */
    default List<String> getChildTextsFromParents(String deviceId, String parentSelector, String childRelativeXPath) {
        return Collections.emptyList();
    }
}
