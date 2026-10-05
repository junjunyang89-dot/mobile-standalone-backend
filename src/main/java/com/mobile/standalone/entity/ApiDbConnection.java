package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库断言用的只读连接。一个环境下可以有多条：各项目自己的库 + 几个项目共用的库。
 * <p>
 * {@code projects} 为空＝通用：没单独绑库的项目都用它（老的「一个环境一个库」迁过来就是这种）。
 * 一个项目绑了多条时，断言里要用 {@code conn} 写明查哪条——自动猜的话，
 * 查错库的结果是「查不到」，断言照样能给出一个看似合理的结论。
 */
@Data
public class ApiDbConnection {
    private String id;
    /** TEST | PROD */
    private String environment;
    /** 断言里引用的名字，同一环境内唯一，如 admin、data_center */
    private String name;
    private String dbHost;
    private Integer dbPort;
    private String dbName;
    private String dbUser;
    private String dbPassword;
    /** 归属项目编码；空＝通用 */
    private List<String> projects = new ArrayList<>();
    private String remark;
    private LocalDateTime updatedAt;

    public boolean shared() {
        return projects == null || projects.isEmpty();
    }
}
