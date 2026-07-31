package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试计划实体
 * 支持批量设备(deviceIds)、批量场景(scenarioIds)、计划类型(planType)：iOS/Android/鸿蒙/pogpog
 */
@Data
public class TestPlan {
    private String id;
    private String name;
    private String description;
    private String cronExpression;  // Cron表达式，如：0 0 2 * * ? (每天凌晨2点)
    private String timezone = "Asia/Shanghai";  // 时区
    private String deviceId;  // 单设备（与 deviceIds 二选一，兼容旧数据）
    private List<String> deviceIds;  // 批量设备ID，定时计划选择对哪批手机执行
    private String appId;
    private List<String> caseIds;  // 关联的用例ID列表（与 scenarioId 二选一）
    /** 场景 ID（如 login_android），与 caseIds 二选一；设定时按 cron 执行该场景 */
    private String scenarioId;
    private List<String> scenarioIds;  // 批量场景ID，定时计划选择哪批场景执行
    /** 场景执行时的 config JSON，如 {"phoneNumber":"13800138000","verificationCode":"666888"} */
    private String scenarioConfig;
    /** 计划类型：IOS, ANDROID, HARMONYOS, POG_ANDROID, POG_IOS, POGPOG（兼容旧值），用于筛选设备与场景 */
    private PlanType planType;
    private Boolean enabled = true;
    private Status status = Status.ACTIVE;
    private LocalDateTime lastRunTime;
    private LocalDateTime nextRunTime;
    private Integer runCount = 0;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public enum Status {
        ACTIVE, PAUSED, COMPLETED
    }

    /** 定时计划类型：iOS、Android、鸿蒙、Pog-Android、Pog-iOS、pogpog（兼容） */
    public enum PlanType {
        IOS, ANDROID, HARMONYOS, POG_ANDROID, POG_IOS, POGPOG
    }
}
