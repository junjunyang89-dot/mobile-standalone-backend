package com.mobile.standalone.service.redis;

import com.mobile.standalone.entity.RedisConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisToolParsingTest {

    @Test
    @DisplayName("CLUSTER NODES 只取活着的主节点，排序稳定（游标里存的是序号）")
    void 主节点() {
        String nodes = """
                b1 10.0.0.3:6379@16379 master - 0 0 3 connected 10923-16383
                a1 10.0.0.1:6379@16379,redis-1 myself,master - 0 0 1 connected 0-5460
                c1 10.0.0.2:6379@16379 slave a1 0 0 1 connected
                d1 10.0.0.4:6379@16379 master,fail - 0 0 4 disconnected
                e1 :6380@16380 master - 0 0 5 connected 5461-10922
                """;
        assertThat(RedisToolService.parseMasters(nodes, "redis.test"))
                .containsExactly("10.0.0.1:6379", "10.0.0.3:6379", "redis.test:6380");
    }

    @Test
    @DisplayName("INFO 解析跳过段标题")
    void info() {
        var m = RedisToolService.parseInfo("# Server\r\nredis_version:6.2.6\r\nredis_mode:standalone\r\n\r\n# Cluster\r\ncluster_enabled:0\r\n");
        assertThat(m).containsEntry("redis_version", "6.2.6").containsEntry("cluster_enabled", "0");
    }

    @Test
    @DisplayName("没有通配符就按精确 key 查，不去 SCAN 全库")
    void 通配符() {
        assertThat(RedisToolService.hasGlob("user:info:123")).isFalse();
        assertThat(RedisToolService.hasGlob("user:info:*")).isTrue();
        assertThat(RedisToolService.hasGlob("user:?")).isTrue();
    }

    @Test
    @DisplayName("连不上时说清是网络、密码还是库号")
    void 报错说人话() {
        RedisConnection c = new RedisConnection();
        c.setHost("h");
        c.setPort(6379);
        c.setDbIndex(3);
        assertThat(RedisToolService.explain(new SocketTimeoutException(), c)).contains("超时").contains("白名单");
        assertThat(RedisToolService.explain(new RespClient.RespError("NOAUTH Authentication required."), c)).contains("要密码");
        assertThat(RedisToolService.explain(new RespClient.RespError("WRONGPASS invalid username-password pair"), c)).contains("密码不对");
        assertThat(RedisToolService.explain(new RespClient.RespError("ERR DB index is out of range"), c)).contains("库号 3");
    }

    @Test
    @DisplayName("地址里直接粘了端口也认；端口默认 6379")
    void 连接归一() {
        RedisConnection b = new RedisConnection();
        b.setName("test");
        b.setHost(" 10.1.2.3:6380 ");
        b.setEnvironment("test");
        RedisConnection c = RedisConnectionService.normalize(b);
        assertThat(c.getHost()).isEqualTo("10.1.2.3");
        assertThat(c.getPort()).isEqualTo(6380);
        assertThat(c.getEnvironment()).isEqualTo("TEST");
        assertThat(c.getDbIndex()).isZero();

        b.setHost("redis.local");
        assertThat(RedisConnectionService.normalize(b).getPort()).isEqualTo(6379);

        b.setHost(" ");
        assertThatThrownBy(() -> RedisConnectionService.normalize(b)).hasMessageContaining("地址必填");
    }
}
