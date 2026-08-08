package com.mobile.standalone.service;

import com.mobile.standalone.dto.PageResult;
import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.mapper.UiElementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiElementService {

    private static final Set<String> SOURCES = Set.of(
            "HUMAN_AI", "SOURCE_CODE", "IMPORT_LEGACY", "INSPECTOR_XML", "MANUAL");
    private static final Set<String> STATUSES = Set.of("CANDIDATE", "ACTIVE", "DEPRECATED");

    private final UiElementMapper uiElementMapper;

    public List<UiElement> list(String appId, String platform, String catalog, String moduleTag, String keyword) {
        return list(appId, platform, catalog, null, moduleTag, null, null, null, keyword);
    }

    public List<UiElement> list(String appId, String platform, String catalog, List<String> catalogs,
                                String moduleTag, String pageId, String source, String status, String keyword) {
        return uiElementMapper.findByFilters(
                blankToNull(appId), blankToNull(platform), blankToNull(catalog), catalogs,
                blankToNull(moduleTag), blankToNull(pageId), blankToNull(source), blankToNull(status),
                blankToNull(keyword), null, null);
    }

    /** 分页列表；page 从 1 起。 */
    public PageResult<UiElement> listPage(String appId, String platform, String catalog, List<String> catalogs,
                                          String moduleTag, String pageId, String source, String status,
                                          String keyword, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        int offset = (p - 1) * s;
        String a = blankToNull(appId);
        String plat = blankToNull(platform);
        String cat = blankToNull(catalog);
        String mod = blankToNull(moduleTag);
        String pid = blankToNull(pageId);
        String src = blankToNull(source);
        String st = blankToNull(status);
        String kw = blankToNull(keyword);
        long total = uiElementMapper.countByFilters(a, plat, cat, catalogs, mod, pid, src, st, kw);
        List<UiElement> content = uiElementMapper.findByFilters(
                a, plat, cat, catalogs, mod, pid, src, st, kw, offset, s);
        int totalPages = total == 0 ? 0 : (int) ((total + s - 1) / s);
        return new PageResult<>(content, total, totalPages, p, s);
    }

    public UiElement get(String id) {
        return uiElementMapper.findById(id);
    }

    public UiElement findByKey(String appId, String platform, String nameEn) {
        return uiElementMapper.findByAppPlatformKey(appId, platform, nameEn, null);
    }

    public UiElement findByKey(String appId, String platform, String nameEn, String source) {
        return uiElementMapper.findByAppPlatformKey(appId, platform, nameEn, blankToNull(source));
    }

    /** 流程/执行解析：只用 ACTIVE */
    public UiElement findActiveByKey(String appId, String platform, String nameEn) {
        return uiElementMapper.findActiveByKey(appId, platform, nameEn);
    }

    public UiElement findByExactLocator(String appId, String platform, String locatorType, String locatorValue) {
        if (appId == null || platform == null || locatorType == null || locatorValue == null) {
            return null;
        }
        String lv = locatorValue.trim();
        String lt = locatorType.trim();
        if (lv.isEmpty() || lt.isEmpty()) return null;
        return uiElementMapper.findByExactLocator(appId.trim(), platform.trim(), lt, lv);
    }

    /** 点选命中后按定位符匹配已有元素 */
    public List<UiElement> findByLocatorHint(String appId, String platform, String locatorValue, int limit) {
        if (locatorValue == null || locatorValue.isBlank()) {
            return List.of();
        }
        String lv = locatorValue.trim();
        String shortId = null;
        int slash = lv.lastIndexOf('/');
        if (slash >= 0 && slash < lv.length() - 1) {
            shortId = lv.substring(slash + 1);
        } else if (lv.contains(":id:")) {
            int i = lv.indexOf(":id:");
            shortId = lv.substring(i + 4);
        }
        return uiElementMapper.findByLocatorHint(
                blankToNull(appId), blankToNull(platform), lv, blankToNull(shortId), Math.max(1, Math.min(limit, 20)));
    }

    @Transactional
    public UiElement create(UiElement e) {
        validate(e);
        if (e.getCatalog() == null || e.getCatalog().isBlank()) {
            e.setCatalog("SHARED");
        }
        if (e.getSource() == null || e.getSource().isBlank()) {
            e.setSource("HUMAN_AI");
        }
        if (e.getStatus() == null || e.getStatus().isBlank()) {
            e.setStatus("CANDIDATE");
        }
        normalizeEnums(e);
        if (e.getEnabled() == null) e.setEnabled(true);
        if (e.getDefaultTimeoutSec() == null) e.setDefaultTimeoutSec(10);
        if (e.getSortOrder() == null) e.setSortOrder(0);
        e.setId("uie-" + UUID.randomUUID());
        e.setNameEn(e.getNameEn().trim());
        e.setNameZh(e.getNameZh().trim());
        e.setLocatorType(e.getLocatorType().trim());
        e.setLocatorValue(e.getLocatorValue().trim());
        if (e.getLocatorFallbacks() != null) {
            e.setLocatorFallbacks(blankToNull(e.getLocatorFallbacks()));
        }
        if (e.getVisualHint() != null) e.setVisualHint(blankToNull(e.getVisualHint()));
        if (e.getPageId() != null) e.setPageId(blankToNull(e.getPageId()));
        if (e.getModuleTag() != null) e.setModuleTag(blankToNull(e.getModuleTag()));
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        UiElement dup = uiElementMapper.findByAppPlatformKey(e.getAppId(), e.getPlatform(), e.getNameEn(), e.getSource());
        if (dup != null) {
            throw new IllegalArgumentException(
                    "同应用同平台同来源下英文名已存在: " + e.getNameEn() + " (" + e.getSource() + ")");
        }
        uiElementMapper.insert(e);
        return e;
    }

    @Transactional
    public UiElement update(String id, UiElement patch) {
        UiElement existing = uiElementMapper.findById(id);
        if (existing == null) return null;
        if (patch.getCatalog() != null) existing.setCatalog(patch.getCatalog());
        if (patch.getNameZh() != null) existing.setNameZh(patch.getNameZh().trim());
        if (patch.getNameEn() != null) existing.setNameEn(patch.getNameEn().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getVisualHint() != null) existing.setVisualHint(blankToNull(patch.getVisualHint()));
        if (patch.getModuleTag() != null) existing.setModuleTag(blankToNull(patch.getModuleTag()));
        if (patch.getPageId() != null) existing.setPageId(blankToNull(patch.getPageId()));
        if (patch.getSource() != null) existing.setSource(patch.getSource().trim());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus().trim());
        if (patch.getLastVerifiedAt() != null) existing.setLastVerifiedAt(patch.getLastVerifiedAt());
        if (patch.getLocatorType() != null) existing.setLocatorType(patch.getLocatorType().trim());
        if (patch.getLocatorValue() != null) existing.setLocatorValue(patch.getLocatorValue().trim());
        if (patch.getLocatorFallbacks() != null) {
            existing.setLocatorFallbacks(patch.getLocatorFallbacks().isBlank() ? null : patch.getLocatorFallbacks().trim());
        }
        if (patch.getPreviewImage() != null) {
            existing.setPreviewImage(patch.getPreviewImage().isBlank() ? null : patch.getPreviewImage().trim());
        }
        if (patch.getDefaultTimeoutSec() != null) existing.setDefaultTimeoutSec(patch.getDefaultTimeoutSec());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        normalizeEnums(existing);
        validate(existing);
        UiElement dup = uiElementMapper.findByAppPlatformKey(
                existing.getAppId(), existing.getPlatform(), existing.getNameEn(), existing.getSource());
        if (dup != null && !dup.getId().equals(id)) {
            throw new IllegalArgumentException(
                    "同应用同平台同来源下英文名已存在: " + existing.getNameEn() + " (" + existing.getSource() + ")");
        }
        existing.setUpdatedAt(LocalDateTime.now());
        uiElementMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        return uiElementMapper.deleteById(id) > 0;
    }

    private static void normalizeEnums(UiElement e) {
        if (e.getSource() != null) {
            e.setSource(e.getSource().trim().toUpperCase());
        }
        if (e.getStatus() != null) {
            e.setStatus(e.getStatus().trim().toUpperCase());
        }
        if (e.getSource() != null && !SOURCES.contains(e.getSource())) {
            throw new IllegalArgumentException(
                    "source 须为 HUMAN_AI / SOURCE_CODE / IMPORT_LEGACY / INSPECTOR_XML / MANUAL");
        }
        if (e.getStatus() != null && !STATUSES.contains(e.getStatus())) {
            throw new IllegalArgumentException("status 须为 CANDIDATE / ACTIVE / DEPRECATED");
        }
    }

    private static void validate(UiElement e) {
        if (e.getAppId() == null || e.getAppId().isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (e.getPlatform() == null || e.getPlatform().isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (e.getNameZh() == null || e.getNameZh().isBlank()) {
            throw new IllegalArgumentException("中文名称不能为空");
        }
        if (e.getNameEn() == null || e.getNameEn().isBlank()) {
            throw new IllegalArgumentException("英文名称不能为空");
        }
        if (e.getLocatorType() == null || e.getLocatorType().isBlank()) {
            throw new IllegalArgumentException("定位方式不能为空");
        }
        if (e.getLocatorValue() == null || e.getLocatorValue().isBlank()) {
            throw new IllegalArgumentException("定位值不能为空");
        }
        if (e.getPreviewImage() != null && e.getPreviewImage().isBlank()) {
            e.setPreviewImage(null);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
