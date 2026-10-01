package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.driver.PlatformDriver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用平台驱动：可设定某个 selector 在第几次探测后才出现，用来模拟慢设备。
 */
class FakeDriver implements PlatformDriver {

    /** selector → 从第几次探测开始返回存在 */
    private final Map<String, Integer> presentFromCheck = new HashMap<>();
    private final Map<String, Integer> checkCount = new HashMap<>();
    /** 点掉后消失的 selector */
    private final Set<String> dismissOnClick = new HashSet<>();
    /** 已被点掉、当前不在屏上的 selector */
    private final Set<String> dismissed = new HashSet<>();
    /** 关掉 A 之后才会冒出来的 B（模拟串弹窗） */
    private final Map<String, String> revealAfterDismiss = new HashMap<>();
    /** 点了也关不掉的 selector */
    private final Set<String> stubborn = new HashSet<>();

    final List<String> clickedSelectors = new ArrayList<>();

    /** 元素属性（checked / enabled / value 等），供状态断言用；不设则视为读不到 */
    final Map<String, String> attributes = new HashMap<>();
    /** 按 selector 区分的属性（如发送验证码按钮 enabled） */
    private final Map<String, Map<String, String>> perSelectorAttrs = new HashMap<>();
    /** 点击次数：勾选框类断言要验证「已是期望状态时一次都不能点」 */
    int clicks = 0;
    /** 点击引发的状态变化，比如点一下把 checked 从 false 变 true */
    Runnable onClick;
    /** 供断言读 page source */
    String pageSourceXml = "";

    /** @param afterChecks 0 表示一开始就存在 */
    void presentAfter(String selector, int afterChecks) {
        presentFromCheck.put(selector, afterChecks);
    }

    /** 一开始就在屏上，点一下就消失 */
    void popup(String selector) {
        delayedPopup(selector, 0);
    }

    /** 第 afterChecks 次探测之后才出现的弹窗，点一下消失（模拟启动慢、弹窗晚渲染） */
    void delayedPopup(String selector, int afterChecks) {
        presentFromCheck.put(selector, afterChecks);
        dismissOnClick.add(selector);
    }

    /** 关掉 trigger 之后 revealed 才出现 */
    void popupAfterDismissing(String trigger, String revealed) {
        dismissOnClick.add(revealed);
        revealAfterDismiss.put(trigger, revealed);
    }

    /** 在屏上但点不掉 */
    void stubbornPopup(String selector) {
        presentFromCheck.put(selector, 0);
        stubborn.add(selector);
    }

    @Override
    public boolean isElementPresent(String deviceId, String selector) {
        if (dismissed.contains(selector)) {
            return false;
        }
        int seen = checkCount.merge(selector, 1, Integer::sum);
        Integer threshold = presentFromCheck.get(selector);
        return threshold != null && seen > threshold;
    }

    // —— 以下为接口要求实现但本测试用不到的方法 ——

    @Override
    public boolean launchApp(String deviceId, String packageName) {
        return true;
    }

    @Override
    public boolean click(String deviceId, int x, int y) {
        return true;
    }

    @Override
    public boolean clickElement(String deviceId, String selector) {
        clickedSelectors.add(selector);
        clicks++;
        if (onClick != null) {
            onClick.run();
        }
        if (stubborn.contains(selector)) {
            return false;
        }
        if (dismissOnClick.contains(selector)) {
            dismissed.add(selector);
            String revealed = revealAfterDismiss.get(selector);
            if (revealed != null) {
                presentFromCheck.put(revealed, 0);
            }
        }
        return true;
    }

    @Override
    public boolean swipe(String deviceId, int x1, int y1, int x2, int y2, int duration) {
        return true;
    }

    @Override
    public boolean inputText(String deviceId, String text) {
        return true;
    }

    @Override
    public boolean inputTextToElement(String deviceId, String selector, String text) {
        return true;
    }

    String lastFocusedSelector;
    String lastFocusedText;
    boolean focusedInputOk = true;

    @Override
    public boolean inputTextToFocusedEditText(String deviceId, String selector, String text) {
        lastFocusedSelector = selector;
        lastFocusedText = text;
        return focusedInputOk;
    }

    @Override
    public String takeScreenshot(String deviceId, String savePath) {
        return savePath;
    }

    @Override
    public String getPageSource(String deviceId) {
        return pageSourceXml;
    }

    @Override
    public boolean waitForElement(String deviceId, String selector, int timeoutSeconds) {
        return isElementPresent(deviceId, selector);
    }

    @Override
    public String getElementText(String deviceId, String selector) {
        return "";
    }

    /** 按元素名登记属性；selector 前缀（xpath: / id:）由取值时按后缀匹配，测试不用关心 */
    void attrOn(String elementKey, String attribute, String value) {
        perSelectorAttrs.computeIfAbsent(elementKey, k -> new HashMap<>()).put(attribute, value);
    }

    @Override
    public String getElementAttribute(String deviceId, String selector, String attribute) {
        for (Map.Entry<String, Map<String, String>> e : perSelectorAttrs.entrySet()) {
            if (selector != null && selector.endsWith(e.getKey()) && e.getValue().containsKey(attribute)) {
                return e.getValue().get(attribute);
            }
        }
        // 返回 null 表示「读不到这个属性」，跟「属性为空串」是两回事
        return attributes.get(attribute);
    }
}
