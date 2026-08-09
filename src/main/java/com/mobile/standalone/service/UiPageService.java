package com.mobile.standalone.service;

import com.mobile.standalone.entity.UiPage;
import com.mobile.standalone.mapper.UiPageMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.LinkedHashMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UiPageService {

    private static final Pattern PAGE_ID_OK = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,120}$");

    private final UiPageMapper uiPageMapper;
    private final com.mobile.standalone.mapper.UiElementMapper uiElementMapper;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS ui_pages (
                        id VARCHAR(64) PRIMARY KEY,
                        app_id VARCHAR(64) NOT NULL,
                        page_id VARCHAR(128) NOT NULL,
                        name_zh VARCHAR(128) NOT NULL,
                        description VARCHAR(512) NULL,
                        module_tag VARCHAR(64) NULL,
                        sort_order INT DEFAULT 0,
                        enabled TINYINT(1) DEFAULT 1,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_app_page (app_id, page_id),
                        INDEX idx_app (app_id),
                        INDEX idx_enabled (enabled)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            try {
                jdbcTemplate.execute("""
                        ALTER TABLE ui_elements
                        ADD COLUMN locator_fallbacks TEXT NULL COMMENT '备用定位 JSON' AFTER locator_value
                        """);
            } catch (Exception ignore) {
                // 列已存在
            }
            log.info("[ui-pages] 表已就绪 ui_pages");
        } catch (Exception e) {
            log.warn("[ui-pages] 建表失败（可手动执行 migration_ui_pages.sql）: {}", e.getMessage());
        }
    }

    public List<UiPage> list(String appId, Boolean enabledOnly) {
        List<UiPage> pages = uiPageMapper.findByAppId(blankToNull(appId), enabledOnly);
        // 元素数跟删除校验用同一个查询——两边各算各的，就会出现
        // 「界面显示 0 个，删的时候说还有 2 个」
        for (UiPage p : pages) {
            p.setElementCount((int) countElements(p));
            p.setElementCountByPlatform(platformBreakdown(p));
        }
        return pages;
    }

    /** 这个页面下的元素数。不带平台/场景/状态等界面筛选，数的是库里真实有多少 */
    private long countElements(UiPage page) {
        if (page == null || page.getPageId() == null || page.getPageId().isBlank()) {
            return 0;
        }
        return uiElementMapper.countByFilters(
                page.getAppId(), null, null, null, null, page.getPageId(), null, null, null);
    }

    /**
     * 各平台各有多少。
     * <p>
     * 页面是按应用建的、<b>不分平台</b>，元素才分平台。所以「Android 下看着是空的」
     * 完全可能同时「iOS 下还有 2 个」——只说总数的话，人清空了所有筛选还是找不到那 2 个，
     * 只会觉得系统在瞎报。得直接说清楚它们在哪一端。
     */
    private Map<String, Integer> platformBreakdown(UiPage page) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (page == null || page.getPageId() == null || page.getPageId().isBlank()) {
            return out;
        }
        try {
            for (Map<String, Object> row : uiElementMapper.countByPageGroupByPlatform(
                    page.getAppId(), page.getPageId())) {
                Object pf = row.get("platform");
                Object cnt = row.get("cnt");
                if (pf != null && cnt instanceof Number n && n.intValue() > 0) {
                    out.put(String.valueOf(pf), n.intValue());
                }
            }
        } catch (Exception e) {
            log.debug("[ui-pages] 平台分布统计失败: {}", e.getMessage());
        }
        return out;
    }

    public UiPage get(String id) {
        return uiPageMapper.findById(id);
    }

    public UiPage findByAppAndPageId(String appId, String pageId) {
        if (appId == null || pageId == null) return null;
        return uiPageMapper.findByAppAndPageId(appId.trim(), pageId.trim());
    }

    /**
     * 确保页面存在：有则返回，无则按 nameZh 创建。
     */
    @Transactional
    public UiPage ensurePage(String appId, String pageId, String nameZh, String moduleTag) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        String pid = normalizePageId(pageId);
        UiPage existing = uiPageMapper.findByAppAndPageId(appId.trim(), pid);
        if (existing != null) {
            return existing;
        }
        UiPage p = new UiPage();
        p.setAppId(appId.trim());
        p.setPageId(pid);
        p.setNameZh(nameZh != null && !nameZh.isBlank() ? nameZh.trim() : pid);
        p.setModuleTag(blankToNull(moduleTag));
        return create(p);
    }

    @Transactional
    public UiPage create(UiPage body) {
        if (body.getAppId() == null || body.getAppId().isBlank()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (body.getPageId() == null || body.getPageId().isBlank()) {
            throw new IllegalArgumentException("pageId 不能为空");
        }
        if (body.getNameZh() == null || body.getNameZh().isBlank()) {
            throw new IllegalArgumentException("nameZh 不能为空");
        }
        String pageId = normalizePageId(body.getPageId());
        if (uiPageMapper.findByAppAndPageId(body.getAppId().trim(), pageId) != null) {
            throw new IllegalArgumentException("该应用下页面编码已存在: " + pageId);
        }
        body.setId("uip-" + UUID.randomUUID());
        body.setPageId(pageId);
        body.setAppId(body.getAppId().trim());
        body.setNameZh(body.getNameZh().trim());
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        uiPageMapper.insert(body);
        return body;
    }

    @Transactional
    public UiPage update(String id, UiPage patch) {
        UiPage existing = uiPageMapper.findById(id);
        if (existing == null) return null;
        String oldPageId = existing.getPageId();
        if (patch.getPageId() != null && !patch.getPageId().isBlank()) {
            String newPageId = normalizePageId(patch.getPageId());
            if (!newPageId.equals(oldPageId)) {
                UiPage clash = uiPageMapper.findByAppAndPageId(existing.getAppId(), newPageId);
                if (clash != null && !clash.getId().equals(id)) {
                    throw new IllegalArgumentException("该应用下页面编码已存在: " + newPageId);
                }
                existing.setPageId(newPageId);
            } else {
                // 即使规范化后相同，也把字典里的脏编码（多下划线）写干净
                existing.setPageId(newPageId);
            }
        }
        if (patch.getNameZh() != null) existing.setNameZh(patch.getNameZh().trim());
        if (patch.getDescription() != null) existing.setDescription(blankToNull(patch.getDescription()));
        if (patch.getModuleTag() != null) existing.setModuleTag(blankToNull(patch.getModuleTag()));
        if (patch.getSortOrder() != null) existing.setSortOrder(patch.getSortOrder());
        if (patch.getEnabled() != null) existing.setEnabled(patch.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        uiPageMapper.update(existing);

        String newId = existing.getPageId();
        if (oldPageId != null && !oldPageId.equals(newId)) {
            syncElementPageIds(existing.getAppId(), oldPageId, newId);
        } else if (patch.getPageId() != null && !patch.getPageId().isBlank()) {
            // 用户输入 CS_EXPLORE，库里可能是 CS___EXPLORE：用规范化结果扫脏数据
            String requested = normalizePageId(patch.getPageId());
            syncElementPageIdsLoose(existing.getAppId(), requested);
        }
        return existing;
    }

    /** 精确：旧编码 → 新编码 */
    private void syncElementPageIds(String appId, String oldPageId, String newPageId) {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE ui_elements SET page_id = ? WHERE app_id = ? AND page_id = ?",
                    newPageId, appId, oldPageId);
            log.info("[ui-pages] 页面编码 {} → {}，精确同步 {} 个元素", oldPageId, newPageId, n);
            // 再扫一遍「多下划线但规范化后相同」的脏 page_id
            syncElementPageIdsLoose(appId, newPageId);
        } catch (Exception e) {
            throw new IllegalArgumentException("更新页面编码失败（同步元素 page_id）: " + e.getMessage());
        }
    }

    /**
     * 把同应用下「去掉连续下划线后等于 target」的 page_id 全部改成 target。
     * 用于修复 CS___EXPLORE → CS_EXPLORE。
     */
    private void syncElementPageIdsLoose(String appId, String targetPageId) {
        try {
            List<String> dirty = jdbcTemplate.queryForList(
                    "SELECT DISTINCT page_id FROM ui_elements WHERE app_id = ? AND page_id IS NOT NULL AND page_id <> ?",
                    String.class, appId, targetPageId);
            int total = 0;
            for (String pid : dirty) {
                if (pid == null) continue;
                String norm;
                try {
                    norm = normalizePageId(pid);
                } catch (Exception e) {
                    continue;
                }
                if (targetPageId.equals(norm)) {
                    total += jdbcTemplate.update(
                            "UPDATE ui_elements SET page_id = ? WHERE app_id = ? AND page_id = ?",
                            targetPageId, appId, pid);
                }
            }
            // 字典里同脏编码也清掉（若有第二条）
            List<String> dirtyPages = jdbcTemplate.queryForList(
                    "SELECT page_id FROM ui_pages WHERE app_id = ? AND page_id <> ?",
                    String.class, appId, targetPageId);
            for (String pid : dirtyPages) {
                try {
                    if (targetPageId.equals(normalizePageId(pid))) {
                        // 已有目标页时删掉脏字典行，避免唯一键冲突；否则改名
                        Integer cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM ui_pages WHERE app_id = ? AND page_id = ?",
                                Integer.class, appId, targetPageId);
                        if (cnt != null && cnt > 0) {
                            jdbcTemplate.update(
                                    "DELETE FROM ui_pages WHERE app_id = ? AND page_id = ?",
                                    appId, pid);
                        } else {
                            jdbcTemplate.update(
                                    "UPDATE ui_pages SET page_id = ? WHERE app_id = ? AND page_id = ?",
                                    targetPageId, appId, pid);
                        }
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (total > 0) {
                log.info("[ui-pages] 松散同步到 {}：{} 个元素", targetPageId, total);
            }
        } catch (Exception e) {
            log.warn("[ui-pages] 松散同步失败: {}", e.getMessage());
        }
    }

    /**
     * 删页面。
     * <p>
     * <b>页面下还有元素时直接拒绝</b>，不级联删元素、也不静默把它们挪成「未归属」：
     * <ul>
     *   <li>级联删会一次毁掉别人采了半天的元素，而且流程里引用它们的步骤会集体失效——
     *       现象是执行时「找不到元素」，没人会联想到是有人删了个页面</li>
     *   <li>静默挪走则相反，元素还在但人以为丢了，会重新采一遍，元素库里就多出一批重复的</li>
     * </ul>
     * 报清楚有几个元素、让人自己决定先挪还是先删，比替他做决定安全得多。
     * 界面上那些「0 个元素」的空页面（建错的、重名的）本来也是删除的主要场景。
     */
    @Transactional
    public boolean delete(String id) {
        UiPage page = uiPageMapper.findById(id);
        if (page == null) {
            return false;
        }
        long count = countElements(page);
        if (count > 0) {
            Map<String, Integer> byPlatform = platformBreakdown(page);
            String where = byPlatform.isEmpty() ? "" : "（" + byPlatform.entrySet().stream()
                    .map(e -> e.getKey() + " " + e.getValue() + " 个")
                    .reduce((a, b) -> a + "、" + b).orElse("") + "）";
            throw new IllegalArgumentException(
                    "页面「" + page.getNameZh() + "」下还有 " + count + " 个元素" + where + "，不能直接删。"
                            + "页面是按应用建的、不分平台，元素才分平台——"
                            + "所以在当前平台下看着是空的，别的平台下可能还有。"
                            + "把顶部「平台」切到上面写的那一端就能看到。"
                            + "请先把它们改挂到别的页面，或先删掉元素");
        }
        return uiPageMapper.deleteById(id) > 0;
    }

    public static String normalizePageId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("pageId 不能为空");
        }
        String p = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        p = p.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (!PAGE_ID_OK.matcher(p).matches()) {
            // 中文名转编码
            if (raw.contains("温馨提示")) return "PRIVACY_WARM_TIP";
            if (raw.contains("手机号") || raw.contains("登录")) return "LOGIN_PHONE";
            if (raw.contains("验证码")) return "LOGIN_SMS";
            throw new IllegalArgumentException("pageId 须为英文数字下划线，如 PRIVACY_WARM_TIP");
        }
        return p;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
