package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试工具里「Redis 查询」用的连接。按环境分：生产环境的连接只能查，不能改。
 */
@Data
public class RedisConnection {
    private String id;
    /** TEST | PROD */
    private String environment;
    private String name;
    private String host;
    private Integer port;
    /** Redis 6 ACL 用户名；老版本只有密码，留空 */
    private String username;
    private String password;
    private Integer dbIndex;
    private String remark;
    private LocalDateTime updatedAt;
}
