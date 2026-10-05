package com.mobile.standalone.service.kafka;

import com.mobile.standalone.entity.KafkaConnection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaToolServiceTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);
    private static final TopicPartition P1 = new TopicPartition("t", 1);

    @Test
    @DisplayName("地址从配置文件直接粘：逗号空格换行都认，没端口补 9092，去重")
    void 地址归一() {
        assertThat(KafkaConnectionService.normalizeServers(" 10.0.0.1:9092, 10.0.0.2\n10.0.0.1:9092 ;kafka.test:19092 "))
                .isEqualTo("10.0.0.1:9092,10.0.0.2:9092,kafka.test:19092");
        assertThatThrownBy(() -> KafkaConnectionService.normalizeServers("h:abc")).hasMessageContaining("host:port");
        assertThatThrownBy(() -> KafkaConnectionService.normalizeServers(" ")).hasMessageContaining("必填");
    }

    @Test
    @DisplayName("PLAINTEXT 不带认证；SASL 要用户名，密码里的引号要转义")
    void 认证配置() {
        KafkaConnection b = new KafkaConnection();
        b.setName("k");
        b.setBootstrapServers("h:9092");
        KafkaConnection plain = KafkaConnectionService.normalize(b);
        assertThat(plain.getSecurityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(KafkaToolService.clientProps(plain)).doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);

        b.setSecurityProtocol("sasl_plaintext");
        assertThatThrownBy(() -> KafkaConnectionService.normalize(b)).hasMessageContaining("用户名");

        b.setUsername("u");
        b.setPassword("p\"w");
        b.setSaslMechanism("scram-sha-256");
        Properties p = KafkaToolService.clientProps(KafkaConnectionService.normalize(b));
        assertThat(p.getProperty(SaslConfigs.SASL_MECHANISM)).isEqualTo("SCRAM-SHA-256");
        assertThat(p.getProperty(SaslConfigs.SASL_JAAS_CONFIG))
                .startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required")
                .contains("username=\"u\"").contains("password=\"p\\\"w\"");
    }

    @Test
    @DisplayName("读消息不加入消费组、不自动提交位点、不自动建 topic")
    void 消费者不动别人进度() {
        KafkaConnection c = new KafkaConnection();
        c.setBootstrapServers("h:9092");
        Properties p = KafkaToolService.consumerProps(c);
        assertThat(p).doesNotContainKey("group.id");
        assertThat(p.get("enable.auto.commit")).isEqualTo(false);
        assertThat(p.get("allow.auto.create.topics")).isEqualTo(false);
    }

    @Test
    @DisplayName("最近 N 条：每个分区从末尾往回 N 条，不越过起点；空分区跳过")
    void 最近N条() {
        Map<TopicPartition, long[]> r = KafkaToolService.ranges("LATEST", List.of(P0, P1),
                Map.of(P0, 0L, P1, 100L), Map.of(P0, 30L, P1, 100L), null, Map.of(), 50);
        assertThat(r).containsOnlyKeys(P0);
        assertThat(r.get(P0)).containsExactly(0L, 30L);
    }

    @Test
    @DisplayName("按位点：超出范围的位点夹到有效区间；按时间：那之后没消息的分区跳过")
    void 位点和时间() {
        Map<TopicPartition, long[]> o = KafkaToolService.ranges("OFFSET", List.of(P0),
                Map.of(P0, 10L), Map.of(P0, 1000L), 5L, Map.of(), 20);
        assertThat(o.get(P0)).containsExactly(10L, 30L);

        Map<TopicPartition, long[]> t = KafkaToolService.ranges("TIME", List.of(P0, P1),
                Map.of(P0, 0L, P1, 0L), Map.of(P0, 500L, P1, 500L), null, Map.of(P0, 480L), 50);
        assertThat(t).containsOnlyKeys(P0);
        assertThat(t.get(P0)).containsExactly(480L, 500L);
    }

    @Test
    @DisplayName("报错剥掉 ExecutionException 外壳，超时 / 认证失败说人话")
    void 报错说人话() {
        assertThat(KafkaToolService.explainStatic(new ExecutionException(new TimeoutException("Timed out waiting for a node assignment"))))
                .contains("超时").contains("advertised.listeners");
        assertThat(KafkaToolService.explainStatic(new SaslAuthenticationException("bad creds")))
                .contains("认证失败");
    }

    @Test
    @DisplayName("生产连接不能发消息，在连 Kafka 之前就拒掉")
    void 生产不能发() {
        KafkaConnectionService conns = mock(KafkaConnectionService.class);
        KafkaConnection prod = new KafkaConnection();
        prod.setEnvironment("PROD");
        prod.setBootstrapServers("127.0.0.1:1");
        when(conns.get("prod")).thenReturn(prod);
        KafkaToolService tool = new KafkaToolService(conns, mock(LoggingSystem.class));
        assertThatThrownBy(() -> tool.produce("prod", "t", null, "v", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能看不能发");
    }

    @Test
    @DisplayName("真起一个客户端连不存在的地址：配置合法，报超时而不是一堆英文堆栈")
    void 连不上() {
        KafkaConnection c = new KafkaConnection();
        c.setName("x");
        c.setBootstrapServers("127.0.0.1:1");
        KafkaToolService tool = new KafkaToolService(mock(KafkaConnectionService.class), mock(LoggingSystem.class));
        Map<String, Object> r = tool.probe(c);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(r.get("message"))).contains("超时");
    }
}
