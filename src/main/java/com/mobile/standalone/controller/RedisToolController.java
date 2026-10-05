package com.mobile.standalone.controller;

import com.mobile.standalone.entity.RedisConnection;
import com.mobile.standalone.service.redis.RedisConnectionService;
import com.mobile.standalone.service.redis.RedisToolService;
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
 * 测试工具 · Redis 查询。
 * <p>
 * 查询也走 POST：值里常有 token、手机号这类东西，按 GET 算的话只读账号也能翻，
 * POST 默认要「测试」档。生产连接的写操作在 service 里直接拒。
 */
@RestController
@RequestMapping("/redis-tool")
@RequiredArgsConstructor
public class RedisToolController {

    private final RedisConnectionService connections;
    private final RedisToolService tool;

    public record ScanBody(String pattern, String cursor, Integer limit) {
    }

    public record KeyBody(String key) {
    }

    public record SetBody(String key, String value, Long ttlSeconds) {
    }

    public record HashBody(String key, String field, String value) {
    }

    public record ExpireBody(String key, Long ttlSeconds) {
    }

    public record DeleteBody(List<String> keys) {
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
    public ResponseEntity<?> save(@RequestBody RedisConnection body) {
        return handle(null, () -> connections.save(body));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("ok", connections.delete(id)));
    }

    /** 弹窗里还没保存也能测 */
    @PostMapping("/connections/probe")
    public ResponseEntity<?> probeDraft(@RequestBody RedisConnection body) {
        return handle(null, () -> tool.probe(connections.resolveForProbe(body)));
    }

    @PostMapping("/connections/{id}/ping")
    public ResponseEntity<?> ping(@PathVariable String id) {
        return handle(id, () -> tool.probe(id));
    }

    @PostMapping("/connections/{id}/scan")
    public ResponseEntity<?> scan(@PathVariable String id, @RequestBody ScanBody body) {
        return handle(id, () -> tool.scan(id, body.pattern(), body.cursor(), body.limit()));
    }

    @PostMapping("/connections/{id}/inspect")
    public ResponseEntity<?> inspect(@PathVariable String id, @RequestBody KeyBody body) {
        return handle(id, () -> tool.inspect(id, body.key()));
    }

    @PostMapping("/connections/{id}/set")
    public ResponseEntity<?> set(@PathVariable String id, @RequestBody SetBody body) {
        return handle(id, () -> tool.setString(id, body.key(), body.value(), body.ttlSeconds()));
    }

    @PostMapping("/connections/{id}/hset")
    public ResponseEntity<?> hset(@PathVariable String id, @RequestBody HashBody body) {
        return handle(id, () -> tool.hashSet(id, body.key(), body.field(), body.value()));
    }

    @PostMapping("/connections/{id}/hdel")
    public ResponseEntity<?> hdel(@PathVariable String id, @RequestBody HashBody body) {
        return handle(id, () -> tool.hashDelete(id, body.key(), body.field()));
    }

    @PostMapping("/connections/{id}/expire")
    public ResponseEntity<?> expire(@PathVariable String id, @RequestBody ExpireBody body) {
        return handle(id, () -> tool.expire(id, body.key(), body.ttlSeconds()));
    }

    @PostMapping("/connections/{id}/del")
    public ResponseEntity<?> del(@PathVariable String id, @RequestBody DeleteBody body) {
        return handle(id, () -> tool.delete(id, body.keys()));
    }

    private ResponseEntity<?> handle(String id, Call call) {
        try {
            return ResponseEntity.ok(call.run());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", tool.explain(id, e)));
        }
    }
}
