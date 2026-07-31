package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiElement;
import com.mobile.standalone.mapper.UiElementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UiElementService {

    private final UiElementMapper uiElementMapper;

    public List<UiElement> list(String appId, String platform, String catalog, String moduleTag, String keyword) {
        return list(appId, platform, catalog, null, moduleTag, keyword);
    }

    public List<UiElement> list(String appId, String platform, String catalog, List<String> catalogs,
                                String moduleTag, String keyword) {
        return uiElementMapper.findByFilters(blankToNull(appId), blankToNull(platform), blankToNull(catalog),
                catalogs, blankToNull(moduleTag), blankToNull(keyword));
    }

    public UiElement get(String id) {
        return uiElementMapper.findById(id);
    }

    public UiElement findByKey(String appId, String platform, String nameEn) {
        return uiElementMapper.findByAppPlatformKey(appId, platform, nameEn);
    }

    @Transactional
    public UiElement create(UiElement e) {
        validate(e);
        if (e.getCatalog() == null || e.getCatalog().isBlank()) {
            e.setCatalog("SHARED");
        }
        if (e.getEnabled() == null) e.setEnabled(true);
        if (e.getDefaultTimeoutSec() == null) e.setDefaultTimeoutSec(10);
        if (e.getSortOrder() == null) e.setSortOrder(0);
        e.setId("uie-" + UUID.randomUUID());
        e.setNameEn(e.getNameEn().trim());
        e.setNameZh(e.getNameZh().trim());
        e.setLocatorType(e.getLocatorType().trim());
        e.setLocatorValue(e.getLocatorValue().trim());
        LocalDateTime now = LocalDateTime.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        UiElement dup = uiElementMapper.findByAppPlatformKey(e.getAppId(), e.getPlatform(), e.getNameEn());
        if (dup != null) {
            throw new IllegalArgumentException("同应用同平台下英文名已存在: " + e.getNameEn());
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
        if (patch.getModuleTag() != null) existing.setModuleTag(patch.getModuleTag());
        if (patch.getLocatorType() != null) existing.setLocatorType(patch.getLocatorType().trim());
        if (patch.getLocatorValue() != null) existing.setLocatorValue(patch.getLocatorValue().trim());
        if (patch.getPreviewImage() != null) existing.setPreviewImage(patch.getPreviewImage().isBlank() ? null : patch.getPreviewImage().trim());
        if (patch.getDefaultTimeoutSec() != null) existing.setDefaultTimeoutSec(patch.getDefaultTimeoutSec());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        validate(existing);
        UiElement dup = uiElementMapper.findByAppPlatformKey(existing.getAppId(), existing.getPlatform(), existing.getNameEn());
        if (dup != null && !dup.getId().equals(id)) {
            throw new IllegalArgumentException("同应用同平台下英文名已存在: " + existing.getNameEn());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        uiElementMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        return uiElementMapper.deleteById(id) > 0;
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
