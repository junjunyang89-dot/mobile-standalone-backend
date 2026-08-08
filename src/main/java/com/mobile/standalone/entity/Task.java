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
    /** 测试场景键（历史 FUNCTIONAL 任务只读；新任务请用流程编排 flowId） */
    private String scenarioKey;
    /** 测试场景展示名（列表/详情计算填入，不落库） */
    private transient String scenarioName;
    private TaskStatus status = TaskStatus.PENDING;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String config;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS,
        /**
         * @deprecated 产品线混进平台的历史值。设备扫描从不写入，回归编排也已改为只写操作系统。
         * 保留仅为让迁移前的存量数据仍能反序列化；跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_ANDROID,
        /**
         * @deprecated 产品线混进平台的历史值。设备扫描从不写入，回归编排也已改为只写操作系统。
         * 保留仅为让迁移前的存量数据仍能反序列化；跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_IOS
    }
    
    public enum TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, STOPPED,
        /** 流程判定为「本次不适用」而提前结束：不算通过也不算失败 */
        SKIPPED
    }
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        /** @deprecated 硬编码场景已下线，请改用 {@link #UI_FLOW_TEST}（流程编排） */
        @Deprecated
        FUNCTIONAL_QUALITY_TEST("功能体验基础质量测试（已下线）"),
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
        /** 低代码流程执行：config 含 flowId，按 ui_flows 步骤跑 */
        UI_FLOW_TEST("流程用例执行"),
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
