package com.mobile.standalone.service;

import com.mobile.standalone.entity.Application;
import com.mobile.standalone.entity.Device;
import com.mobile.standalone.service.CaseEngine.TestStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 安全校验模块
 * 防止误操作，确保测试安全
 */
@Component
@Slf4j
public class AntiMisoperationService {
    
    // 危险操作列表
    private static final Set<String> DANGEROUS_ACTIONS = new HashSet<>();
    
    static {
        DANGEROUS_ACTIONS.add("uninstall");
        DANGEROUS_ACTIONS.add("delete");
        DANGEROUS_ACTIONS.add("format");
        DANGEROUS_ACTIONS.add("factory_reset");
    }
    
    // 系统应用包名列表（不允许操作）
    private static final Set<String> SYSTEM_APPS = new HashSet<>();
    
    static {
        SYSTEM_APPS.add("com.android.settings");
        SYSTEM_APPS.add("com.android.systemui");
        SYSTEM_APPS.add("com.android.launcher");
    }
    
    /**
     * 验证测试步骤是否安全
     */
    public boolean validateStep(TestStep step, Device device, Application application) {
        // 1. 检查是否为危险操作
        if (step.getAction() != null && DANGEROUS_ACTIONS.contains(step.getAction().toLowerCase())) {
            log.warn("检测到危险操作: {}", step.getAction());
            return false;
        }
        
        // 2. 检查是否为系统应用
        if (application != null && SYSTEM_APPS.contains(application.getPackageName())) {
            log.warn("不允许操作系统应用: {}", application.getPackageName());
            return false;
        }
        
        // 3. 检查设备状态
        if (device == null || device.getStatus() != Device.DeviceStatus.CONNECTED) {
            log.warn("设备未连接: {}", device != null ? device.getId() : "null");
            return false;
        }
        
        // 4. 检查坐标范围（防止超出屏幕）
        if (step.getX1() != null && step.getY1() != null) {
            if (step.getX1() < 0 || step.getY1() < 0 || step.getX1() > 10000 || step.getY1() > 10000) {
                log.warn("坐标超出合理范围: ({}, {})", step.getX1(), step.getY1());
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 验证测试计划是否安全
     */
    public boolean validatePlan(String deviceId, String appId) {
        // 可以添加更多的验证逻辑
        // 例如：检查设备是否在维护时间
        // 检查应用是否在测试白名单中
        return true;
    }
}
