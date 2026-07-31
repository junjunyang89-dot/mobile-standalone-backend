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
        ANDROID, IOS, HARMONYOS, POG_ANDROID, POG_IOS
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
