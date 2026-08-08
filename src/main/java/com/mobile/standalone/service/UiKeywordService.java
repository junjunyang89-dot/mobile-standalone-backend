package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiKeyword;
import com.mobile.standalone.mapper.UiKeywordMapper;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.KeywordRegistry;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ParamSchemaWriter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UiKeywordService {

    private final UiKeywordMapper uiKeywordMapper;
    private final KeywordRegistry keywordRegistry;

    /**
     * 把关键字目录同步成注册表的样子：新增缺失的、刷新已有的、清掉已合并的别名条目。
     * <p>
     * 目录不再手工维护——{@link KeywordHandler} 是唯一来源，加关键字只需新增一个实现类。
     */
    @PostConstruct
    public void syncFromRegistry() {
        try {
            Set<String> retired = new HashSet<>();
            for (KeywordHandler handler : keywordRegistry.all()) {
                upsert(handler);
                // 合并掉的旧 code 仍能执行（别名），但不该再出现在关键字面板里
                for (String alias : handler.aliases()) {
                    retired.add(alias.trim().toUpperCase(Locale.ROOT));
                }
            }
            retireMergedKeywords(retired);
            log.info("关键字目录已与注册表同步: {} 个关键字", keywordRegistry.all().size());
        } catch (Exception e) {
            log.warn("同步关键字库失败（请先执行 schema_ui_assets.sql 与 migration_ui_keywords_custom_ui.sql）: {}",
                    e.getMessage());
        }
    }

    /**
     * 重试是跨关键字的公共能力，不该让每个 handler 各自声明一遍。
     * 只给可重试的关键字加，避免在「安装应用」这类不该自动重试的表单上出现误导性开关。
     */
    private static List<ParamDef> withCommonParams(KeywordHandler handler) {
        if (!handler.retryable()) {
            return handler.params();
        }
        List<ParamDef> all = new ArrayList<>(handler.params());
        all.add(ParamDef.number("retryTimes", "失败重试", 1)
                .withHelp("这一步失败后自动再试几次。UI 的失败很多是偶发的，重试一次能消掉大量偶现红；填 0 关闭"));
        all.add(ParamDef.number("retryDelayMs", "重试前等待", 800)
                .withHelp("失败往往是页面还没稳，立刻重试多半还是失败"));
        return all;
    }

    private void upsert(KeywordHandler handler) {
        String schema = ParamSchemaWriter.toJson(withCommonParams(handler));
        UiKeyword existing = uiKeywordMapper.findByCode(handler.code());
        LocalDateTime now = LocalDateTime.now();

        if (existing == null) {
            UiKeyword k = new UiKeyword();
            k.setId("uik-" + handler.code().toLowerCase(Locale.ROOT));
            k.setCode(handler.code());
            k.setBuiltin(true);
            k.setEnabled(true);
            k.setCreatedAt(now);
            applyMeta(k, handler, schema, now);
            uiKeywordMapper.insert(k);
            log.info("新增关键字: {} {}", handler.code(), handler.nameZh());
            return;
        }
        // 保留管理员对 enabled 的调整，其余字段以代码为准
        applyMeta(existing, handler, schema, now);
        uiKeywordMapper.update(existing);
    }

    private void applyMeta(UiKeyword k, KeywordHandler handler, String schema, LocalDateTime now) {
        k.setNameZh(handler.nameZh());
        k.setDescription(handler.description());
        k.setCategory(handler.category());
        k.setNeedsElement(handler.needsElement());
        k.setParamSchema(schema);
        k.setCustomUi(handler.customUi());
        k.setSortOrder(handler.sortOrder());
        k.setUpdatedAt(now);
    }

    /** 删除已被合并为别名的旧内置关键字条目；用户自建的同名关键字不动 */
    private void retireMergedKeywords(Set<String> retiredCodes) {
        for (String code : retiredCodes) {
            UiKeyword old = uiKeywordMapper.findByCode(code);
            if (old != null && Boolean.TRUE.equals(old.getBuiltin())) {
                uiKeywordMapper.deleteById(old.getId());
                log.info("移除已合并的关键字条目: {}（执行时仍作为别名兼容存量流程）", code);
            }
        }
    }

    public List<UiKeyword> list(String category, Boolean enabledOnly) {
        return uiKeywordMapper.findAll(blankToNull(category), enabledOnly);
    }

    public UiKeyword get(String id) {
        return uiKeywordMapper.findById(id);
    }

    public UiKeyword getByCode(String code) {
        return uiKeywordMapper.findByCode(code);
    }

    @Transactional
    public UiKeyword create(UiKeyword k) {
        if (k.getCode() == null || k.getCode().isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        if (k.getNameZh() == null || k.getNameZh().isBlank()) {
            throw new IllegalArgumentException("中文名不能为空");
        }
        String code = k.getCode().trim().toUpperCase();
        if (uiKeywordMapper.findByCode(code) != null) {
            throw new IllegalArgumentException("关键字 code 已存在: " + code);
        }
        k.setId("uik-" + UUID.randomUUID());
        k.setCode(code);
        k.setNameZh(k.getNameZh().trim());
        if (k.getCategory() == null || k.getCategory().isBlank()) k.setCategory("ACTION");
        if (k.getNeedsElement() == null) k.setNeedsElement(false);
        if (k.getBuiltin() == null) k.setBuiltin(false);
        if (k.getEnabled() == null) k.setEnabled(true);
        if (k.getSortOrder() == null) k.setSortOrder(999);
        LocalDateTime now = LocalDateTime.now();
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        uiKeywordMapper.insert(k);
        return k;
    }

    @Transactional
    public UiKeyword update(String id, UiKeyword patch) {
        UiKeyword existing = uiKeywordMapper.findById(id);
        if (existing == null) return null;
        if (patch.getNameZh() != null) existing.setNameZh(patch.getNameZh().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getCategory() != null) existing.setCategory(patch.getCategory());
        if (patch.getNeedsElement() != null) existing.setNeedsElement(patch.getNeedsElement());
        if (patch.getParamSchema() != null) existing.setParamSchema(patch.getParamSchema());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        existing.setUpdatedAt(LocalDateTime.now());
        uiKeywordMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        UiKeyword existing = uiKeywordMapper.findById(id);
        if (existing == null) return false;
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException("内置关键字不可删除，可禁用");
        }
        return uiKeywordMapper.deleteById(id) > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
