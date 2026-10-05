package com.mobile.standalone.controller;

import com.mobile.standalone.entity.KafkaConnection;
import com.mobile.standalone.service.kafka.KafkaConnectionService;
import com.mobile.standalone.service.kafka.KafkaToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测试工具 · Kafka 查询。
 * <p>
 * 查询也走 POST：消息里常有用户数据，按 GET 算只读账号也能翻，POST 默认要「测试」档。
 * 生产连接发消息在 service 里直接拒。
 */
@RestController
@RequestMapping("/kafka-tool")
@RequiredArgsConstructor
public class KafkaToolController {

    private final KafkaConnectionService connections;
    private final KafkaToolService tool;

    public record TopicsBody(String keyword, Boolean includeInternal) {
    }

    public record TopicBody(String topic) {
    }

    public record MessagesBody(String topic, Integer partition, String mode, Long offset, Long timeMs,
                               Integer limit, String keyword) {
    }

    public record ProduceBody(String topic, String key, String value, Integer partition,
                              List<KafkaToolService.HeaderKv> headers) {
    }

    @FunctionalInterface
    interface Call {
        Object run() throws Exception;
    }

    @GetMapping("/connections")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(connections.list());
    }

    /** 带 id 是改，不带是新建 */
    @PostMapping("/connections")
    public ResponseEntity<?> save(@RequestBody KafkaConnection body) {
        return handle(() -> connections.save(body));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", connections.delete(id)));
    }

    /** 弹窗里还没保存也能测 */
    @PostMapping("/connections/probe")
    public ResponseEntity<?> probeDraft(@RequestBody KafkaConnection body) {
        return handle(() -> tool.probe(connections.resolveForProbe(body)));
    }

    @PostMapping("/connections/{id}/ping")
    public ResponseEntity<?> ping(@PathVariable String id) {
        return handle(() -> tool.probe(id));
    }

    @PostMapping("/connections/{id}/topics")
    public ResponseEntity<?> topics(@PathVariable String id, @RequestBody TopicsBody body) {
        return handle(() -> tool.topics(id, body.keyword(), Boolean.TRUE.equals(body.includeInternal())));
    }

    @PostMapping("/connections/{id}/topic")
    public ResponseEntity<?> topic(@PathVariable String id, @RequestBody TopicBody body) {
        return handle(() -> tool.topicDetail(id, body.topic()));
    }

    @PostMapping("/connections/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id, @RequestBody MessagesBody b) {
        return handle(() -> tool.messages(id, b.topic(), b.partition(), b.mode(), b.offset(), b.timeMs(),
                b.limit(), b.keyword()));
    }

    @PostMapping("/connections/{id}/produce")
    public ResponseEntity<?> produce(@PathVariable String id, @RequestBody ProduceBody b) {
        return handle(() -> tool.produce(id, b.topic(), b.key(), b.value(), b.partition(), b.headers()));
    }

    private ResponseEntity<?> handle(Call call) {
        try {
            return ResponseEntity.ok(call.run());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", tool.explain(e)));
        }
    }
}
