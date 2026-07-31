package com.mobile.standalone.service;

import com.mobile.standalone.entity.PageObject;
import com.mobile.standalone.service.driver.PlatformDriver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 元素解析器：从 page_objects 库按 pagePath + elementKey 取 selector。
 * 线上回归 / 用例引擎应通过本类访问元素，禁止在业务代码写死 xpath/id。
 * <p>
 * 缺元素时抛出明确异常，禁止静默回退到硬编码串。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ElementResolver {

    private final PageObjectService pageObjectService;

    /**
     * 解析元素定义；不存在则抛 IllegalStateException。
     */
    public PageObject.PageElement requireElement(String platform, String appId, String pagePath, String elementKey) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (pagePath == null || pagePath.isBlank()) {
            throw new IllegalArgumentException("pagePath 不能为空");
        }
        if (elementKey == null || elementKey.isBlank()) {
            throw new IllegalArgumentException("elementKey 不能为空");
        }
        PageObject page = pageObjectService.findByPagePath(platform.trim(), appId.trim(), pagePath.trim());
        if (page == null) {
            throw new IllegalStateException(
                    "库中无页面对象: platform=" + platform + ", appId=" + appId + ", pagePath=" + pagePath
                            + "（请在「页面对象」中维护）");
        }
        if (page.getElements() == null || !page.getElements().containsKey(elementKey)) {
            throw new IllegalStateException(
                    "库中无元素: pagePath=" + pagePath + ", elementKey=" + elementKey
                            + "（请在「页面对象」中新增该元素）");
        }
        PageObject.PageElement el = page.getElements().get(elementKey);
        if (el.getSelector() == null || el.getSelector().isBlank()) {
            throw new IllegalStateException(
                    "元素 selector 为空: pagePath=" + pagePath + ", elementKey=" + elementKey);
        }
        return el;
    }

    /**
     * 解析为驱动可用的 selector 字符串（已带 id:/xpath: 等前缀时原样返回；否则按 selectorType 补前缀）。
     */
    public String resolveSelector(String platform, String appId, String pagePath, String elementKey) {
        PageObject.PageElement el = requireElement(platform, appId, pagePath, elementKey);
        return toDriverSelector(el);
    }

    /**
     * 将库中元素转为驱动选择器（与 CaseEngine / AppiumDriverAdapter 约定一致）。
     */
    public static String toDriverSelector(PageObject.PageElement el) {
        String raw = el.getSelector() != null ? el.getSelector().trim() : "";
        if (raw.isEmpty()) {
            return raw;
        }
        // 已是驱动格式
        if (raw.startsWith("id:") || raw.startsWith("xpath:") || raw.startsWith("accessibility id:")
                || raw.startsWith("name:") || raw.startsWith("class name:") || raw.startsWith("css selector:")) {
            return raw;
        }
        String type = el.getSelectorType() != null ? el.getSelectorType().trim().toLowerCase() : "xpath";
        return switch (type) {
            case "id", "resource-id", "resourceid" -> "id:" + raw;
            case "accessibility-id", "accessibilityid", "accessibility id" -> "accessibility id:" + raw;
            case "name", "text" -> raw.startsWith("//") || raw.startsWith("(") ? "xpath:" + raw : "xpath://*[@text='" + raw + "' or @name='" + raw + "']";
            case "xpath" -> raw.startsWith("xpath:") ? raw : "xpath:" + raw;
            default -> raw.contains("/") ? (raw.startsWith("xpath:") ? raw : "xpath:" + raw) : raw;
        };
    }

    public boolean click(PlatformDriver driver, String deviceId, String platform, String appId,
                         String pagePath, String elementKey) {
        String selector = resolveSelector(platform, appId, pagePath, elementKey);
        log.info("ElementResolver.click: {} / {} -> {}", pagePath, elementKey, selector);
        return driver.clickElement(deviceId, selector);
    }

    public boolean input(PlatformDriver driver, String deviceId, String platform, String appId,
                         String pagePath, String elementKey, String text) {
        String selector = resolveSelector(platform, appId, pagePath, elementKey);
        log.info("ElementResolver.input: {} / {} -> {}", pagePath, elementKey, selector);
        return driver.inputTextToElement(deviceId, selector, text != null ? text : "");
    }

    public boolean exists(PlatformDriver driver, String deviceId, String platform, String appId,
                          String pagePath, String elementKey) {
        String selector = resolveSelector(platform, appId, pagePath, elementKey);
        return driver.isElementPresent(deviceId, selector);
    }

    public boolean waitFor(PlatformDriver driver, String deviceId, String platform, String appId,
                           String pagePath, String elementKey, int timeoutSeconds) {
        PageObject.PageElement el = requireElement(platform, appId, pagePath, elementKey);
        String selector = toDriverSelector(el);
        int timeout = timeoutSeconds > 0 ? timeoutSeconds
                : (el.getTimeout() != null && el.getTimeout() > 0 ? el.getTimeout() : 10);
        return driver.waitForElement(deviceId, selector, timeout);
    }
}
