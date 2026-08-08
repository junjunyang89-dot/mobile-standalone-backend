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
        ANDROID, IOS, HARMONYOS,
        /**
         * @deprecated 产品线混进平台的历史值，保留仅为让存量数据反序列化；
         * 跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_ANDROID,
        /**
         * @deprecated 产品线混进平台的历史值，保留仅为让存量数据反序列化；
         * 跑完 migration_drop_pog_platform.sql 后可删。
         */
        @Deprecated
        POG_IOS
    }
}
