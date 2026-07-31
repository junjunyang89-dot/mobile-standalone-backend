package com.mobile.standalone.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Application {
    private String id;
    private String name;
    private String packageName;
    private String version;
    private String iconPath;
    private Platform platform;
    private String deviceId;  // 设备ID（关联devices表）
    private String installPath;
    private LocalDateTime installedAt;
    private String config;
    
    public enum Platform {
        ANDROID, IOS, HARMONYOS, POG_ANDROID, POG_IOS
    }
}
