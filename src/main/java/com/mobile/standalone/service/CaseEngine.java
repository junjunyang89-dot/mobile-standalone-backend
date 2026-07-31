package com.mobile.standalone.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.entity.*;
import com.mobile.standalone.mapper.ApplicationMapper;
import com.mobile.standalone.service.driver.PlatformDriver;
import com.mobile.standalone.service.driver.PlatformRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用例引擎
 * 负责执行测试用例，协调驱动和页面对象模型
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaseEngine {
    
    private final PlatformRouter platformRouter;
    private final PageObjectService pageObjectService;
    private final AntiMisoperationService antiMisoperationService;
    private final ApplicationMapper applicationMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 执行测试用例
     */
    public TestReport executeCase(TestCase testCase, Device device, String appId) {
        log.info("开始执行测试用例: {}, 设备: {}, 应用: {}", testCase.getName(), device.getId(), appId);
        
        TestReport report = new TestReport();
        report.setId(java.util.UUID.randomUUID().toString());
        report.setCaseId(testCase.getId());
        report.setDeviceId(device.getId());
        report.setAppId(appId);
        report.setStatus(TestReport.ExecutionStatus.RUNNING);
        report.setStartTime(LocalDateTime.now());
        
        PlatformDriver driver = platformRouter.getDriver(device);
        Application application = applicationMapper.findById(appId);
        
        try {
            // 1. 启动应用
            if (application != null && !launchApp(driver, device.getId(), application.getPackageName())) {
                throw new RuntimeException("启动应用失败");
            }
            
            // 2. 等待应用启动
            Thread.sleep(3000);
            
            // 3. 解析测试步骤
            List<TestStep> steps = parseTestSteps(testCase.getSteps());
            
            // 4. 执行测试步骤
            List<String> screenshotPaths = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            
            for (int i = 0; i < steps.size(); i++) {
                TestStep step = steps.get(i);
                log.info("执行步骤 {}/{}: {}", i + 1, steps.size(), step.getAction());
                
                try {
                    // 安全校验
                    if (!antiMisoperationService.validateStep(step, device, application)) {
                        throw new RuntimeException("安全校验失败: " + step.getAction());
                    }
                    
                    // 执行步骤
                    boolean success = executeStep(driver, device.getId(), step, testCase, application);
                    
                    if (!success) {
                        errors.add("步骤 " + (i + 1) + " 执行失败: " + step.getAction());
                    }
                    
                    // 截图（可选）
                    if (Boolean.TRUE.equals(step.getScreenshot())) {
                        String screenshotPath = takeScreenshot(driver, device.getId(), testCase.getId(), i);
                        if (screenshotPath != null) {
                            screenshotPaths.add(screenshotPath);
                        }
                    }
                    
                    // 步骤间延迟
                    if (step.getDelay() > 0) {
                        Thread.sleep(step.getDelay());
                    }
                    
                } catch (Exception e) {
                    log.error("执行步骤失败: {}", step.getAction(), e);
                    errors.add("步骤 " + (i + 1) + " 执行异常: " + e.getMessage());
                }
            }
            
            // 5. 验证预期结果
            boolean passed = validateExpectedResult(driver, device.getId(), testCase.getExpectedResult(), errors);
            
            // 6. 生成报告
            report.setStatus(passed ? TestReport.ExecutionStatus.PASSED : TestReport.ExecutionStatus.FAILED);
            report.setEndTime(LocalDateTime.now());
            report.setDuration(calculateDuration(report.getStartTime(), report.getEndTime()));
            report.setScreenshotPaths(screenshotPaths);
            report.setErrorMessage(errors.isEmpty() ? null : String.join("; ", errors));
            
            log.info("测试用例执行完成: {}, 结果: {}", testCase.getName(), report.getStatus());
            
        } catch (Exception e) {
            log.error("执行测试用例失败", e);
            report.setStatus(TestReport.ExecutionStatus.FAILED);
            report.setEndTime(LocalDateTime.now());
            report.setErrorMessage(e.getMessage());
        }
        
        return report;
    }
    
    /**
     * 启动应用
     */
    private boolean launchApp(PlatformDriver driver, String deviceId, String packageName) {
        return driver.launchApp(deviceId, packageName);
    }
    
    /**
     * 解析测试步骤（JSON格式）
     */
    private List<TestStep> parseTestSteps(String stepsJson) {
        try {
            if (stepsJson == null || stepsJson.trim().isEmpty()) {
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> stepsList = objectMapper.readValue(stepsJson, 
                    new TypeReference<List<Map<String, Object>>>() {});
            
            List<TestStep> steps = new ArrayList<>();
            for (Map<String, Object> stepMap : stepsList) {
                TestStep step = new TestStep();
                step.setAction((String) stepMap.get("action"));
                step.setSelector((String) stepMap.get("selector"));
                step.setText((String) stepMap.get("text"));
                step.setX1((Integer) stepMap.getOrDefault("x1", 0));
                step.setY1((Integer) stepMap.getOrDefault("y1", 0));
                step.setX2((Integer) stepMap.getOrDefault("x2", 0));
                step.setY2((Integer) stepMap.getOrDefault("y2", 0));
                step.setDuration((Integer) stepMap.getOrDefault("duration", 500));
                step.setTimeout((Integer) stepMap.getOrDefault("timeout", 10));
                step.setDelay((Integer) stepMap.getOrDefault("delay", 1000));
                step.setScreenshot((Boolean) stepMap.getOrDefault("screenshot", false));
                step.setPageObject((String) stepMap.get("pageObject"));
                step.setElementName((String) stepMap.get("elementName"));
                steps.add(step);
            }
            
            return steps;
        } catch (Exception e) {
            log.error("解析测试步骤失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 执行测试步骤
     */
    private boolean executeStep(PlatformDriver driver, String deviceId, TestStep step, 
                                TestCase testCase, Application application) {
        switch (step.getAction().toLowerCase()) {
            case "click":
                if (step.getPageObject() != null && step.getElementName() != null) {
                    // 使用页面对象模型
                    PageObject pageObject = pageObjectService.findByPagePath(
                            testCase.getPlatform().name(), testCase.getAppId(), step.getPageObject());
                    if (pageObject != null) {
                        return driver.clickByPageObject(deviceId, pageObject, step.getElementName());
                    }
                } else if (step.getSelector() != null) {
                    // 使用选择器
                    return driver.clickElement(deviceId, step.getSelector());
                } else if (step.getX1() > 0 && step.getY1() > 0) {
                    // 使用坐标
                    return driver.click(deviceId, step.getX1(), step.getY1());
                }
                break;
                
            case "swipe":
                return driver.swipe(deviceId, step.getX1(), step.getY1(), 
                        step.getX2(), step.getY2(), step.getDuration());
                
            case "input":
            case "inputtext":
                if (step.getSelector() != null) {
                    return driver.inputTextToElement(deviceId, step.getSelector(), step.getText());
                } else {
                    return driver.inputText(deviceId, step.getText());
                }
                
            case "wait":
            case "waitforelement":
                if (step.getSelector() != null) {
                    return driver.waitForElement(deviceId, step.getSelector(), step.getTimeout());
                } else if (step.getDelay() > 0) {
                    try {
                        Thread.sleep(step.getDelay());
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                break;
                
            case "assert":
            case "verify":
                return verifyAssertion(driver, deviceId, step);
                
            default:
                log.warn("未知的操作类型: {}", step.getAction());
                return false;
        }
        
        return false;
    }
    
    /**
     * 验证断言
     */
    private boolean verifyAssertion(PlatformDriver driver, String deviceId, TestStep step) {
        String assertType = step.getSelector(); // 使用 selector 字段存储断言类型
        
        if ("element_exists".equals(assertType)) {
            return driver.isElementPresent(deviceId, step.getText()); // 使用 text 字段存储选择器
        } else if ("text_equals".equals(assertType)) {
            String actualText = driver.getElementText(deviceId, step.getText());
            return step.getText().equals(actualText);
        }
        
        return false;
    }
    
    /**
     * 验证预期结果
     */
    private boolean validateExpectedResult(PlatformDriver driver, String deviceId, 
                                          String expectedResult, List<String> errors) {
        if (expectedResult == null || expectedResult.trim().isEmpty()) {
            return errors.isEmpty();
        }
        
        // 解析预期结果（JSON格式）
        try {
            Map<String, Object> expected = objectMapper.readValue(expectedResult, 
                    new TypeReference<Map<String, Object>>() {});
            
            // 验证元素存在
            if (expected.containsKey("elementExists")) {
                String selector = (String) expected.get("elementExists");
                if (!driver.isElementPresent(deviceId, selector)) {
                    errors.add("预期元素不存在: " + selector);
                    return false;
                }
            }
            
            // 验证文本内容
            if (expected.containsKey("textEquals")) {
                Object textEqualsObj = expected.get("textEquals");
                if (textEqualsObj instanceof Map<?, ?> textCheck) {
                    Object selectorObj = textCheck.get("selector");
                    Object expectedTextObj = textCheck.get("text");
                    if (selectorObj instanceof String selector && expectedTextObj instanceof String expectedText) {
                        String actualText = driver.getElementText(deviceId, selector);
                        if (actualText == null || !expectedText.equals(actualText)) {
                            errors.add("文本不匹配: 预期=" + expectedText + ", 实际=" + actualText);
                            return false;
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("验证预期结果失败", e);
            return errors.isEmpty();
        }
        
        return errors.isEmpty();
    }
    
    /**
     * 截图
     */
    private String takeScreenshot(PlatformDriver driver, String deviceId, String caseId, int stepIndex) {
        String screenshotPath = String.format("/tmp/screenshots/%s_step_%d_%d.png", 
                caseId, stepIndex, System.currentTimeMillis());
        return driver.takeScreenshot(deviceId, screenshotPath);
    }
    
    /**
     * 计算执行时长（毫秒）
     */
    private Long calculateDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return java.time.Duration.between(start, end).toMillis();
    }
    
    /**
     * 测试步骤内部类
     */
    @lombok.Data
    public static class TestStep {
        private String action;  // click, swipe, input, wait, assert等
        private String selector;  // 元素选择器
        private String text;  // 输入文本或断言文本
        private Integer x1, y1, x2, y2;  // 坐标
        private Integer duration = 500;  // 滑动时长
        private Integer timeout = 10;  // 等待超时（秒）
        private Integer delay = 1000;  // 步骤延迟（毫秒）
        private Boolean screenshot = false;  // 是否截图
        private String pageObject;  // 页面对象名称
        private String elementName;  // 页面对象中的元素名称
    }
}
