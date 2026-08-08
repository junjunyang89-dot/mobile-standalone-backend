package com.mobile.standalone.service.keyword;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 关键字注册表：启动时收集所有 {@link KeywordHandler}，按主 code 与别名建索引。
 * <p>
 * 执行器与关键字目录（ui_keywords）都以此为唯一来源，杜绝「目录里有、执行器不认」
 * 或「执行器能跑、目录里查不到」这类漂移。
 */
@Component
@Slf4j
public class KeywordRegistry {

    private final Map<String, KeywordHandler> byCode = new LinkedHashMap<>();
    private final List<KeywordHandler> handlers;

    public KeywordRegistry(List<KeywordHandler> handlers) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(KeywordHandler::sortOrder)
                        .thenComparing(KeywordHandler::code))
                .toList();
        for (KeywordHandler h : this.handlers) {
            register(normalize(h.code()), h, false);
            for (String alias : h.aliases()) {
                register(normalize(alias), h, true);
            }
        }
        log.info("关键字注册完成: {} 个关键字, {} 个可识别 code（含别名）", this.handlers.size(), byCode.size());
    }

    private void register(String code, KeywordHandler handler, boolean alias) {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("关键字 code 不能为空: " + handler.getClass().getName());
        }
        KeywordHandler existing = byCode.putIfAbsent(code, handler);
        if (existing != null && existing != handler) {
            throw new IllegalStateException("关键字 code 冲突: " + code
                    + " 同时来自 " + existing.getClass().getSimpleName()
                    + " 与 " + handler.getClass().getSimpleName());
        }
        if (alias) {
            log.debug("关键字别名: {} → {}", code, handler.code());
        }
    }

    /** 按 code 或别名查找，找不到返回 null */
    public KeywordHandler find(String code) {
        return code == null ? null : byCode.get(normalize(code));
    }

    /** 目录用：只含主关键字，不含别名，已按 sortOrder 排好 */
    public List<KeywordHandler> all() {
        return new ArrayList<>(handlers);
    }

    /** 全部可识别 code（含别名），用于错误提示 */
    public List<String> knownCodes() {
        return new ArrayList<>(byCode.keySet());
    }

    private static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}
