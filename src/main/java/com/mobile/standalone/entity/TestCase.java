package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 测试用例实体
 */
@Data
public class TestCase {
    private String id;
    private String name;
    private String description;
    private Platform platform;
    private String appId;
    private CaseType caseType;
    private String steps;  // JSON格式的测试步骤
    private String expectedResult;
    private Integer priority = 5;  // 1-10，数字越大优先级越高
    private Status status = Status.ACTIVE;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
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
    
    public enum CaseType {
        FUNCTIONAL("功能测试"),
        STABILITY("稳定性测试"),
        PERFORMANCE("性能测试"),
        UI("UI测试"),
        REGRESSION("回归测试");
        
        private final String displayName;
        
        CaseType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    public enum Status {
        ACTIVE, INACTIVE, DEPRECATED
    }
}
