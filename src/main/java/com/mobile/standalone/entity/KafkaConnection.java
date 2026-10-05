package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测试工具里「Kafka 查询」用的集群连接。按环境分：生产环境的连接只能看，不能发消息。
 */
@Data
public class KafkaConnection {
    private String id;
    /** TEST | PROD */
    private String environment;
    private String name;
    /** host:port，多个逗号分隔 */
    private String bootstrapServers;
    /** PLAINTEXT / SASL_PLAINTEXT / SASL_SSL / SSL，空=PLAINTEXT */
    private String securityProtocol;
    /** PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512，只有 SASL 才用 */
    private String saslMechanism;
    private String username;
    private String password;
    private String remark;
    private LocalDateTime updatedAt;
}
