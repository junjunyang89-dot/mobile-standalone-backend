package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiApp;
import com.mobile.standalone.mapper.UiAppMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UiAppService {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,62}$");

    private final UiAppMapper uiAppMapper;

    @PostConstruct
    public void seedBuiltins() {
        try {
            seed("DOMESTIC_ESPORTS", "完美世界电竞（国内）", "国内电竞正式包",
                    "com.pwrd.steam.esports", "com.wmzq.esportapp", 10);
            seed("OVERSEAS_POGPOG", "PogPog（海外）", "海外电竞正式包",
                    "com.arca.egames.intl", "com.arca.egamesintl", 20);
        } catch (Exception e) {
            log.warn("初始化应用字典失败（请先执行 schema_ui_assets.sql 或 migration_ui_apps.sql）: {}", e.getMessage());
        }
    }

    private void seed(String id, String nameZh, String description,
                      String androidPackage, String iosBundleId, int order) {
        if (uiAppMapper.findById(id) != null) return;
        UiApp a = new UiApp();
        a.setId(id);
        a.setNameZh(nameZh);
        a.setDescription(description);
        a.setAndroidPackage(androidPackage);
        a.setIosBundleId(iosBundleId);
        a.setEnabled(true);
        a.setSortOrder(order);
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        uiAppMapper.insert(a);
        log.info("已种子应用字典: {} {}", id, nameZh);
    }

    public List<UiApp> list(Boolean enabledOnly) {
        return uiAppMapper.findAll(enabledOnly);
    }

    public UiApp get(String id) {
        return uiAppMapper.findById(id);
    }

    @Transactional
    public UiApp create(UiApp app) {
        if (app.getId() == null || app.getId().isBlank()) {
            throw new IllegalArgumentException("应用编码不能为空");
        }
        String id = app.getId().trim().toUpperCase();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("应用编码需大写字母开头，仅含大写字母/数字/下划线，如 DOMESTIC_ESPORTS");
        }
        if (app.getNameZh() == null || app.getNameZh().isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空");
        }
        if (uiAppMapper.findById(id) != null) {
            throw new IllegalArgumentException("应用编码已存在: " + id);
        }
        app.setId(id);
        app.setNameZh(app.getNameZh().trim());
        if (app.getAndroidPackage() != null) app.setAndroidPackage(app.getAndroidPackage().trim());
        if (app.getIosBundleId() != null) app.setIosBundleId(app.getIosBundleId().trim());
        if (app.getEnabled() == null) app.setEnabled(true);
        if (app.getSortOrder() == null) app.setSortOrder(100);
        LocalDateTime now = LocalDateTime.now();
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        uiAppMapper.insert(app);
        return app;
    }

    @Transactional
    public UiApp update(String id, UiApp patch) {
        UiApp existing = uiAppMapper.findById(id);
        if (existing == null) return null;
        if (patch.getNameZh() != null) existing.setNameZh(patch.getNameZh().trim());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        if (patch.getAndroidPackage() != null) existing.setAndroidPackage(patch.getAndroidPackage().trim());
        if (patch.getIosBundleId() != null) existing.setIosBundleId(patch.getIosBundleId().trim());
        if (patch.getChannelUrls() != null) existing.setChannelUrls(patch.getChannelUrls().trim());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        if (existing.getNameZh() == null || existing.getNameZh().isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空");
        }
        existing.setUpdatedAt(LocalDateTime.now());
        uiAppMapper.update(existing);
        return existing;
    }

    @Transactional
    public boolean delete(String id) {
        return uiAppMapper.deleteById(id) > 0;
    }

    /** 应用字典里配的蒲公英 appKey，按平台取；没配返回 null */
    public String pgyerAppKey(String appId, String platform) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        com.mobile.standalone.entity.UiApp app = get(appId.trim());
        if (app == null) {
            return null;
        }
        boolean ios = platform != null
                && platform.toUpperCase(java.util.Locale.ROOT).contains("IOS");
        String key = ios ? app.getPgyerAppKeyIos() : app.getPgyerAppKeyAndroid();
        return key == null || key.isBlank() ? null : key.trim();
    }
}
