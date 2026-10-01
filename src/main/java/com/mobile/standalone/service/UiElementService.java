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
            "HUMAN_AI", "SOURCE_CODE", "IMPORT_LEGACY", "INSPECTOR_XML", "MANUAL",
            // 录制生成的候选。来源是事实、不该被审核改掉——
            // 改了就丢了溯源，以后再想知道「这批是怎么来的」就没依据了。
            // 审核动的是 status（CANDIDATE → ACTIVE），不是 source
            "RECORDED",
            // 从另一个平台照搬过来的：名字、页面都有了，定位是空的等人补
            "PLATFORM_COPY");
    private static final Set<String> STATUSES = Set.of("CANDIDATE", "ACTIVE", "DEPRECATED");
    private static final List<String> MIRROR_PLATFORMS = List.of("ANDROID", "IOS");
    private static final Set<String> NO_MIRROR_SOURCES = Set.of("PLATFORM_COPY", "RECORDED", "IMPORT_LEGACY");

    private final UiElementMapper uiElementMapper;

    /**
     * 用来查「有哪些流程在引用这个元素」。
     * <p>
     * 注入的是 mapper 而不是 {@code UiFlowService}：后者虽然也能查，但会多一层依赖，
     * 而这里只需要一个按应用+平台列流程的查询。（{@code UiFlowService} 那边注入的也只是
     * {@code UiElementMapper}，两边都只碰 mapper，不存在循环依赖。）
     */
    private final com.mobile.standalone.mapper.UiFlowMapper uiFlowMapper;
    private final UiPageService uiPageService;

    public record CopyResult(int created, List<String> existing, int pagesCreated, boolean dryRun) {}

    /**
     * 把一个平台的元素照搬到另一个平台：名字、页面、说明都带过去，定位留空、状态待审。
     * <p>
     * 两端差的基本只有定位，其余从头再录一遍纯属重复劳动。
     * 目标平台已有同英文名的（不管什么来源、什么状态）一律跳过——那是人已经做过的，不能盖。
     * 同名多条时取选用的那条，没有选用的取第一条。停用的不搬。
     */
    @Transactional
    public CopyResult copyPlatform(String fromAppId, String fromPlatform, String toAppId, String toPlatform,
                                   String pageId, boolean dryRun) {
        String fa = blankToNull(fromAppId);
        String fp = blankToNull(fromPlatform);
        String ta = blankToNull(toAppId) == null ? fa : toAppId.trim();
        String tp = blankToNull(toPlatform);
        if (fa == null || fp == null || tp == null) {
            throw new IllegalArgumentException("来源应用、来源平台、目标平台都要选");
        }
        if (fa.equals(ta) && fp.equalsIgnoreCase(tp)) {
            throw new IllegalArgumentException("来源和目标是同一个平台");
        }
        String pid = blankToNull(pageId);

        java.util.Map<String, UiElement> byKey = new java.util.LinkedHashMap<>();
        for (UiElement e : list(fa, fp, null, null, null, pid, null, null, null)) {
            if (e.getNameEn() == null || e.getNameEn().isBlank() || "DEPRECATED".equals(e.getStatus())) continue;
            UiElement prev = byKey.get(e.getNameEn());
            if (prev == null || (!"ACTIVE".equals(prev.getStatus()) && "ACTIVE".equals(e.getStatus()))) {
                byKey.put(e.getNameEn(), e);
            }
        }
        Set<String> taken = new java.util.HashSet<>();
        for (UiElement e : list(ta, tp, null, null, null, null, null, null, null)) {
            if (e.getNameEn() != null) taken.add(e.getNameEn());
        }

        List<String> existing = new java.util.ArrayList<>();
        Set<String> pages = new java.util.LinkedHashSet<>();
        int created = 0;
        for (UiElement src : byKey.values()) {
            if (taken.contains(src.getNameEn())) {
                existing.add(src.getNameEn());
                continue;
            }
            created++;
            if (src.getPageId() != null) pages.add(src.getPageId());
            if (dryRun) continue;
            create(placeholderOf(src, ta, tp));
        }

        int pagesCreated = 0;
        if (!fa.equals(ta)) {
            for (String p : pages) {
                if (uiPageService.findByAppAndPageId(ta, p) != null) continue;
                pagesCreated++;
                if (dryRun) continue;
                com.mobile.standalone.entity.UiPage sp = uiPageService.findByAppAndPageId(fa, p);
                uiPageService.ensurePage(ta, p, sp != null ? sp.getNameZh() : p, sp != null ? sp.getModuleTag() : null);
            }
        }
        return new CopyResult(created, existing, pagesCreated, dryRun);
    }

    /**
     * 人新建一个元素后，在同应用的另一端补一个同名、定位留空的候选。
     * <p>
     * 同一个应用的 Android 和 iOS 是同一套界面，名字、页面、说明都一样，只有定位不同；
     * 不补的话 iOS 那边看到的是空页面，要么重录一遍名字，要么根本不知道缺了什么。
     * 鸿蒙是独立应用（appId 不同、界面也不一样），不在此列。
     * 录制、旧数据导入这类批量来源不补：那些候选很多还没人看过，搬过去只会翻倍噪音。
     *
     * @return 实际补了的平台；另一端已有同英文名的（不管来源、状态）不动
     */
    @Transactional
    public List<String> mirrorToSiblingPlatforms(UiElement src) {
        if (src == null || src.getAppId() == null || src.getNameEn() == null
                || NO_MIRROR_SOURCES.contains(src.getSource())) {
            return List.of();
        }
        String platform = src.getPlatform() == null ? "" : src.getPlatform().toUpperCase(java.util.Locale.ROOT);
        if (!MIRROR_PLATFORMS.contains(platform)) {
            return List.of();
        }
        List<String> done = new java.util.ArrayList<>();
        for (String other : MIRROR_PLATFORMS) {
            if (other.equals(platform)) continue;
            if (uiElementMapper.findByAppPlatformKey(src.getAppId(), other, src.getNameEn(), null) != null) continue;
            create(placeholderOf(src, src.getAppId(), other));
            done.add(other);
        }
        return done;
    }

    private static UiElement placeholderOf(UiElement src, String appId, String platform) {
        UiElement c = new UiElement();
        c.setAppId(appId);
        c.setPlatform(platform);
        c.setCatalog(src.getCatalog());
        c.setNameZh(src.getNameZh());
        c.setNameEn(src.getNameEn());
        c.setDescription(src.getDescription());
        c.setVisualHint(src.getVisualHint());
        c.setModuleTag(src.getModuleTag());
        c.setPageId(src.getPageId());
        c.setDefaultTimeoutSec(src.getDefaultTimeoutSec());
        c.setSortOrder(src.getSortOrder());
        c.setEnabled(src.getEnabled());
        c.setLocatorType("xpath");
        c.setLocatorValue("");
        c.setSource("PLATFORM_COPY");
        c.setStatus("CANDIDATE");
        return c;
    }

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
        // 服务端盖章，覆盖请求体里的值——理由见 CurrentUser 的类注释
        e.setCreatedBy(com.mobile.standalone.auth.CurrentUser.nameOrNull());
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
        e.setLocatorValue(e.getLocatorValue() == null ? "" : e.getLocatorValue().trim());
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
        if (patch.getNameEn() != null) {
            String newName = patch.getNameEn().trim();
            // 流程是按英文名晚绑定元素的（步骤里只存 elementKey，不写死 xpath）。
            // 所以改名等于把所有引用它的流程指向一个不存在的 key——
            // 那些流程下一次跑会报「找不到元素」，而没人会联想到是有人改了个名字。
            if (!newName.equals(existing.getNameEn())) {
                assertNotReferenced(existing, "改名（" + existing.getNameEn() + " → " + newName + "）");
            }
            existing.setNameEn(newName);
        }
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

    /**
     * 删元素。
     *
     * <p><b>还有流程在引用时直接拒绝。</b>这一条是照 {@code UiPageService.delete} 的护栏抄的——
     * 那里的注释已经把后果写清楚了：「流程里引用它们的步骤会集体失效，
     * 现象是执行时『找不到元素』，没人会联想到是有人删了个页面」。
     * 页面层挡住了，元素层原先没挡，而元素才是被直接引用的那一层。
     *
     * <p>报清楚是哪几条流程在用、让人自己决定先改流程还是先删，比替他做决定安全得多。
     */
    @Transactional
    public boolean delete(String id) {
        UiElement existing = uiElementMapper.findById(id);
        if (existing == null) {
            return false;
        }
        assertNotReferenced(existing, "删除");
        return uiElementMapper.deleteById(id) > 0;
    }

    /**
     * 有流程在引用这个元素就抛异常。
     *
     * @param action 动作名，直接进报错文案——「删除」和「改名」的后果一样，但人需要知道是哪一个被拦了
     */
    private void assertNotReferenced(UiElement element, String action) {
        if (element.getNameEn() == null || element.getNameEn().isBlank()) {
            return;
        }
        String key = element.getNameEn().trim();
        java.util.List<com.mobile.standalone.entity.UiFlow> flows;
        try {
            // 只查同应用同平台：元素是按应用+平台维护的，别的应用里同名的 key 是另一个东西
            flows = uiFlowMapper.findByFilters(
                    element.getAppId(), element.getPlatform(), null, null, null, null, null);
        } catch (Exception e) {
            // 查不到流程时<b>拒绝操作</b>，不能放行。
            // 放行的话，「数据库抖了一下」就等于「没人在用」，而这正是这道护栏要防的事
            throw new IllegalStateException(
                    "无法确认有没有流程在引用元素「" + key + "」（查询流程失败：" + e.getMessage()
                            + "），为安全起见本次" + action + "已取消，请稍后重试");
        }
        if (flows == null || flows.isEmpty()) {
            return;
        }

        java.util.List<String> referencing = new java.util.ArrayList<>();
        java.util.List<String> unreadable = new java.util.ArrayList<>();
        for (com.mobile.standalone.entity.UiFlow flow : flows) {
            String steps = flow.getSteps();
            if (!com.mobile.standalone.util.FlowStepScanner.isParsable(steps)) {
                // 扫不动的流程单独列出来。当成「没引用」放过去，
                // 就是把「没扫出来」当成了「没人用」
                unreadable.add(flowLabel(flow));
                continue;
            }
            if (com.mobile.standalone.util.FlowStepScanner.referencesElement(steps, key)) {
                referencing.add(flowLabel(flow));
            }
        }

        if (!referencing.isEmpty()) {
            throw new IllegalArgumentException(
                    "元素「" + key + "」还有 " + referencing.size() + " 条流程在引用，不能" + action + "："
                            + preview(referencing)
                            + "。流程按英文名引用元素，" + action + "之后这些流程执行时会报「找不到元素」。"
                            + "请先改这些流程，或把元素状态改成 DEPRECATED 停用而不是删掉");
        }
        if (!unreadable.isEmpty()) {
            throw new IllegalStateException(
                    "有 " + unreadable.size() + " 条流程的步骤解析不了，无法确认是否引用了元素「" + key
                            + "」：" + preview(unreadable)
                            + "。为安全起见本次" + action + "已取消——"
                            + "把「没扫出来」当成「没人用」放过去，正是这道检查要防的事");
        }
    }

    private static String flowLabel(com.mobile.standalone.entity.UiFlow flow) {
        String name = flow.getName() == null || flow.getName().isBlank() ? flow.getId() : flow.getName();
        return name;
    }

    /** 只列前几条：几十条流程全列出来的报错没人读得下去 */
    private static String preview(java.util.List<String> names) {
        int show = Math.min(5, names.size());
        String head = String.join("、", names.subList(0, show));
        return names.size() > show ? head + " 等 " + names.size() + " 条" : head;
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
                    "source 须为 HUMAN_AI / SOURCE_CODE / IMPORT_LEGACY / INSPECTOR_XML / MANUAL / RECORDED");
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
        // 定位空着只允许停在待审：选用的元素会进正式执行，空定位到那时才报就晚了
        if ((e.getLocatorValue() == null || e.getLocatorValue().isBlank())
                && "ACTIVE".equalsIgnoreCase(String.valueOf(e.getStatus()).trim())) {
            throw new IllegalArgumentException("定位值还没填，不能选用");
        }
        if (e.getPreviewImage() != null && e.getPreviewImage().isBlank()) {
            e.setPreviewImage(null);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
