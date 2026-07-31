package com.mobile.standalone.entity;

import lombok.Data;

@Data
public class App {
    private String id;
    private String name;
    private String packageName;
    private String version;
    private String deviceId;
    private String platform;
    private String iconPath;
    private Long installTime;
    private Long fileSize;
}
