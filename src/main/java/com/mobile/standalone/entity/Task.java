package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Task {
    private String id;
    private String name;
    private String description;
    private String projectPath;
    private String scriptPath;
    private Platform platform;
    private String deviceId;
    private String appId;  // 应用ID（已安装的应用）
    private TaskType taskType;  // 任务类型
    /** 测试场景键（功能基础质量测试下：launch_app=启动应用程序, login=登录, search=搜索），按平台走不同执行路径 */
    private String scenarioKey;
    /** 测试场景展示名（如「设置测试(Android)」），由列表/详情接口根据 scenarioKey+platform 计算后填入，不落库 */
    private transient String scenarioName;
    private TaskStatus status = TaskStatus.PENDING;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String config;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS, POG_ANDROID, POG_IOS
    }
    
    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, STOPPED
    }
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        FUNCTIONAL_QUALITY_TEST("功能体验基础质量测试"),
        PERFORMANCE_BASELINE_TEST("性能基础质量测试"),
        POWER_CONSUMPTION_BASELINE_TEST("功耗基础质量测试"),
        SCENARIO_PERFORMANCE_TEST("场景化性能测试"),
        SECURITY_BASELINE_QUALITY_TEST("安全基础质量测试"),
        STABILITY_QUALITY_TEST("稳定性基础质量测试"),
        PERFORMANCE_MONITORING_TEST("性能指标监控测试"),
        COMPATIBILITY_TEST("兼容性测试"),
        INSTALL_UNINSTALL_UPGRADE_TEST("安装卸载升级测试"),
        UI_ADAPTATION_TEST("界面与适配测试"),
        REGRESSION_TEST("回归测试"),
        /**
         * 兼容旧值：历史数据/旧接口可能已写入该枚举名。
         */
        @Deprecated
        PERFORMANCE_QUALITY_TEST("性能基础质量测试"),
        /**
         * 兼容旧值：历史数据/旧接口可能已写入该枚举名。
         */
        @Deprecated
        POWER_CONSUMPTION_QUALITY_TEST("功耗基础质量测试");
        
        private final String displayName;
        
        TaskType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
}
