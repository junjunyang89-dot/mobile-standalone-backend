package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import com.mobile.standalone.mapper.DeviceMapper;
import com.mobile.standalone.service.driver.PlatformRouter;
import com.mobile.standalone.service.inspect.LocatorMatcher;
import com.mobile.standalone.service.inspect.LocatorRepairSuggester;
import com.mobile.standalone.service.inspect.UiHierarchyBuilder;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素健康：让元素在被用到时自证，而不是靠人定期巡检。
 *
 * <p>一千多个元素，一个人一天也点不完，所以「定期人工巡检」这条路从一开始就不成立。
 * 唯一可持续的做法是让维护成本跟着<b>实际使用</b>走：
 *
 * <ul>
 *   <li><b>第一层 · 被动自证</b>：流程跑到哪个元素，那个元素就记一次 {@code last_verified_at}；
 *       定位失败就累加 {@code fail_count}。零额外成本，是跑用例的副产品。</li>
 *   <li><b>第二层 · 搭车体检</b>：{@code CHECK_PAGE_ELEMENTS} 关键字插在任何已有流程里，
 *       dump 一次控件树，把本页所有 ACTIVE 元素的定位在树上求值一遍。
 *       不用为体检单独建流程，也不用真的去点。</li>
 * </ul>
 *
 * <p><b>刻意不动 {@code status} 字段。</b>把失效元素改成 SUSPECT 之类的新状态看着很自然，
 * 但流程执行是按 {@code status='ACTIVE'} 取元素的——一改，下次执行直接报「无 ACTIVE 元素」，
 * 一个本来只是「可能失效」的元素立刻变成确定跑不了的。<b>监控手段不能反过来弄坏被监控的东西。</b>
 * 所以健康数据全部落在独立字段上，「可疑」是一个查询，不是一个状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UiElementHealthService {

    /** 连续失败几次算「可疑」。1 次太吵（偶发点空很常见），3 次基本能排除偶发 */
    public static final int SUSPECT_THRESHOLD = 3;

    private final JdbcTemplate jdbcTemplate;
    private final DeviceMapper deviceMapper;
    private final PlatformRouter platformRouter;

    /**
     * 失败现场的控件树存在它下面的任务子目录里（见 UiFlowExecutor.dumpPageSourceOnFailure）。
     *
     * <p>默认值必须和 {@code UiFlowExecutor} / {@code ScreenshotRetentionService} 写的<b>完全一致</b>。
     * 不一致的后果特别隐蔽：配置没显式设时，这里会去另一个（不存在的）目录找，
     * 于是离线体检永远报「没有找到失败现场」——看起来像「还没积累到数据」，
     * 而实际上现场一直都在，只是找错了地方。
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.screenshot-dir:${user.home}/.mobile-standalone/screenshots}")
    private String screenshotDir;

    /** 一次流程执行里某个元素的使用记录，执行结束后一次性落库 */
    public record ElementUsage(String appId, String platform, String nameEn, boolean ok, String note) {
    }

    @PostConstruct
    public void ensureSchema() {
        addColumnIfMissing("last_failed_at",
                "ADD COLUMN last_failed_at DATETIME DEFAULT NULL COMMENT '最近一次定位失败'");
        addColumnIfMissing("fail_count",
                "ADD COLUMN fail_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数，成功即清零'");
        addColumnIfMissing("health_note",
                "ADD COLUMN health_note VARCHAR(500) DEFAULT NULL COMMENT '最近一次失败的上下文'");
        addColumnIfMissing("last_probed_at",
                "ADD COLUMN last_probed_at DATETIME DEFAULT NULL COMMENT '最近一次页面体检'");
        addColumnIfMissing("probe_match_count",
                "ADD COLUMN probe_match_count INT DEFAULT NULL COMMENT '体检命中数：0=失效，大于1=不唯一'");
    }

    private void addColumnIfMissing(String column, String alter) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM information_schema.COLUMNS"
                            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_elements' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE ui_elements " + alter);
            log.info("[element-health] ui_elements 已补列 {}", column);
        } catch (Exception e) {
            log.warn("[element-health] 补列 ui_elements.{} 失败: {}", column, e.getMessage());
        }
    }

    // ==================== 第一层：被动自证 ====================

    /**
     * 把一次流程执行攒下的元素使用记录落库。
     * <p>
     * 在执行的 finally 里调用，<b>任何异常都吞掉</b>：记账失败绝不能把一次本来跑成功的执行变成失败。
     * 按 (app_id, platform, name_en) 更新，不需要先查 id——执行链路上不多一次读。
     */
    public void flush(List<ElementUsage> usages) {
        if (usages == null || usages.isEmpty()) {
            return;
        }
        // 同一元素在一条流程里可能被用很多次：只要成功过一次就算验证通过。
        // 否则一条「点了 5 次、最后一次因为别的原因挂了」的流程会把它冤枉成失败
        Map<String, ElementUsage> merged = new LinkedHashMap<>();
        for (ElementUsage u : usages) {
            if (u == null || u.appId() == null || u.nameEn() == null) {
                continue;
            }
            String key = u.appId() + " " + u.platform() + " " + u.nameEn();
            ElementUsage prev = merged.get(key);
            if (prev == null || (!prev.ok() && u.ok())) {
                merged.put(key, u);
            }
        }
        int ok = 0;
        int bad = 0;
        for (ElementUsage u : merged.values()) {
            try {
                if (u.ok()) {
                    ok += jdbcTemplate.update(
                            "UPDATE ui_elements SET last_verified_at = NOW(), fail_count = 0, health_note = NULL"
                                    + " WHERE app_id = ? AND platform = ? AND name_en = ?",
                            u.appId(), u.platform(), u.nameEn());
                } else {
                    bad += jdbcTemplate.update(
                            "UPDATE ui_elements SET last_failed_at = NOW(), fail_count = fail_count + 1,"
                                    + " health_note = ? WHERE app_id = ? AND platform = ? AND name_en = ?",
                            trim(u.note(), 500), u.appId(), u.platform(), u.nameEn());
                }
            } catch (Exception e) {
                log.warn("[element-health] 记账失败 {}/{}: {}", u.platform(), u.nameEn(), e.getMessage());
            }
        }
        if (ok + bad > 0) {
            log.info("[element-health] 本次执行记账：验证通过 {} 个，定位失败 {} 个", ok, bad);
        }
    }

    // ==================== 第二层：页面体检 ====================

    @Data
    public static class ProbeResult {
        private String appId;
        private String platform;
        private String pageId;
        private int total;
        /** 命中恰好 1 个：健康 */
        private int healthy;
        /** 命中 0 个：定位已失效 */
        private int missing;
        /** 命中多个：早晚会点错，属于定时炸弹 */
        private int ambiguous;
        /** 定位含变量或随机下标，静态求值没有意义，跳过 */
        private int skipped;
        private List<Map<String, Object>> problems = new ArrayList<>();
    }

    /**
     * 拿当前屏的控件树，把该 App/平台（可再限定页面）下所有 ACTIVE 元素的定位求值一遍。
     * <p>
     * 关键点是<b>不需要导航、也不需要真的去点</b>：走到哪一页就体检哪一页，
     * 所以可以直接挂在已有流程里搭车跑，不必为体检单独维护一套流程——
     * 那套流程本身也会腐烂，等于用一个要维护的东西去维护另一个。
     */
    public ProbeResult probe(String pageSource, String appId, String platform, String pageId) {
        ProbeResult r = new ProbeResult();
        r.setAppId(appId);
        r.setPlatform(platform);
        r.setPageId(pageId);
        if (pageSource == null || pageSource.isBlank()) {
            throw new IllegalStateException("拿不到当前页控件树，无法体检");
        }
        org.w3c.dom.Document doc;
        try {
            doc = UiHierarchyBuilder.parseDocument(pageSource);
        } catch (Exception e) {
            throw new IllegalStateException("控件树解析失败: " + e.getMessage(), e);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT id, name_en, name_zh, locator_type, locator_value, page_id"
                        + " FROM ui_elements WHERE status = 'ACTIVE' AND app_id = ? AND platform = ?");
        List<Object> args = new ArrayList<>();
        args.add(appId);
        args.add(platform);
        if (pageId != null && !pageId.isBlank()) {
            sql.append(" AND page_id = ?");
            args.add(pageId);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        r.setTotal(rows.size());

        for (Map<String, Object> row : rows) {
            String type = str(row.get("locator_type"));
            String value = str(row.get("locator_value"));
            // 含变量或随机下标的定位在静态树上求值没有意义，别拿它去吓唬人
            if (value == null || value.contains("${") || RANDOM_INDEX.matcher(value).find()) {
                r.setSkipped(r.getSkipped() + 1);
                continue;
            }
            int n = LocatorMatcher.count(doc, type, value);
            if (n == LocatorMatcher.UNKNOWN) {
                r.setSkipped(r.getSkipped() + 1);
                continue;
            }
            markProbe(str(row.get("id")), n);
            if (n == 1) {
                r.setHealthy(r.getHealthy() + 1);
                continue;
            }
            if (n == 0) {
                r.setMissing(r.getMissing() + 1);
            } else {
                r.setAmbiguous(r.getAmbiguous() + 1);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", row.get("id"));
            p.put("nameEn", row.get("name_en"));
            p.put("nameZh", row.get("name_zh"));
            p.put("locatorType", type);
            p.put("locatorValue", value);
            p.put("matchCount", n);
            p.put("verdict", n == 0 ? "定位已失效" : "定位不唯一（命中 " + n + " 个）");
            r.getProblems().add(p);
        }
        log.info("[element-health] 页面体检 app={} platform={} page={}：共 {}，健康 {}，失效 {}，不唯一 {}，跳过 {}",
                appId, platform, pageId, r.getTotal(), r.getHealthy(), r.getMissing(),
                r.getAmbiguous(), r.getSkipped());
        return r;
    }

    /**
     * 对着一台真机的当前屏做体检。人工触发用：手动走到某一页，点一下就知道这页的元素还活着几个。
     * <p>
     * 平时不该靠这个——真正省事的是把 {@code CHECK_PAGE_ELEMENTS} 挂进已有流程搭车跑。
     */
    public ProbeResult probeDevice(String deviceId, String appId, String platform, String pageId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        Device device = deviceMapper.findById(deviceId.trim());
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        String xml = platformRouter.getDriver(device).getPageSource(device.getId());
        String plat = platform != null && !platform.isBlank()
                ? platform
                : (device.getPlatform() != null ? device.getPlatform().name() : null);
        return probe(xml, appId, plat, pageId);
    }

    // ==================== 第三层：拿已经存下来的失败现场离线体检 ====================

    /**
     * 扫最近保存的失败现场控件树，对元素库做一次离线体检。
     *
     * <p><b>原料是免费的。</b>每次执行失败都会在 {@code screenshots/{taskId}/pagesource-*.xml}
     * 存一份当时的控件树。不需要设备在线、不需要人走到那一页、不需要往流程里挂关键字——
     * 这是三层里唯一<b>完全被动</b>的一层。
     *
     * <h3>它刻意不判「失效」</h3>
     *
     * 这一点是整个设计里最要紧的。一个元素在这些现场里一次都没出现，有两种可能：
     * <ol>
     *   <li>它的定位真的废了</li>
     *   <li>这些现场压根不是它该出现的页面</li>
     * </ol>
     * 而离线体检<b>分不出这两者</b>——它不知道每份 xml 是哪一页。
     * 所以它只报它能证明的事：
     *
     * <ul>
     *   <li><b>还活着</b>：某个现场里命中恰好 1 个 → 这条定位此刻是好的，铁证</li>
     *   <li><b>不唯一</b>：命中多个 → 回放只取第 1 个，「找到了、点了、没反应」的来源，
     *       这是定时炸弹，而且<b>现在没有任何一层会发现它</b></li>
     *   <li><b>没覆盖到</b>：一次都没命中 → <b>不下结论</b></li>
     * </ul>
     *
     * <p>「失效」交给第一层：那里知道是哪个元素在哪一步挂的，{@code fail_count} 才是够格的证据。
     * 两层各报自己能证明的，合起来才不会冤枉人——而误报一个「失效」的代价是：
     * 人去改一条本来好好的定位。
     *
     * @param maxScenes 最多读几份现场。从新到旧，避免一次扫穿几个月的历史
     */
    public OfflineResult probeSavedScenes(String appId, String platform, int maxScenes) {
        OfflineResult r = new OfflineResult();
        r.setAppId(appId);
        r.setPlatform(platform);

        List<java.nio.file.Path> files = recentSceneFiles(Math.max(1, maxScenes));
        r.setScenesScanned(files.size());
        if (files.isEmpty()) {
            r.getNotes().add("没有找到保存下来的失败现场控件树。"
                    + "它们只在执行失败时才产生，而且跟着截图的保留策略清理（默认 7 天）");
            return r;
        }

        List<org.w3c.dom.Document> docs = new ArrayList<>();
        for (java.nio.file.Path f : files) {
            try {
                docs.add(UiHierarchyBuilder.parseDocument(java.nio.file.Files.readString(f)));
            } catch (Exception e) {
                log.debug("[element-health] 现场解析失败 {}: {}", f.getFileName(), e.getMessage());
            }
        }
        r.setScenesParsed(docs.size());
        if (docs.isEmpty()) {
            r.getNotes().add("找到了 " + files.size() + " 份现场，但一份都没解析成功");
            return r;
        }

        StringBuilder sql = new StringBuilder(
                "SELECT id, name_en, name_zh, locator_type, locator_value, page_id"
                        + " FROM ui_elements WHERE status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        if (appId != null && !appId.isBlank()) {
            sql.append(" AND app_id = ?");
            args.add(appId);
        }
        if (platform != null && !platform.isBlank()) {
            sql.append(" AND platform = ?");
            args.add(platform);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        r.setTotal(rows.size());

        for (Map<String, Object> row : rows) {
            String type = str(row.get("locator_type"));
            String value = str(row.get("locator_value"));
            if (value == null || value.contains("${") || RANDOM_INDEX.matcher(value).find()) {
                r.setSkipped(r.getSkipped() + 1);
                continue;
            }
            int best = LocatorMatcher.UNKNOWN;
            for (org.w3c.dom.Document doc : docs) {
                int n = LocatorMatcher.count(doc, type, value);
                if (n == LocatorMatcher.UNKNOWN) {
                    continue;
                }
                // 命中 1 是铁证，见到就可以收手；否则留下见过的最大命中数
                if (n == 1) {
                    best = 1;
                    break;
                }
                best = Math.max(best, n);
            }
            if (best == LocatorMatcher.UNKNOWN) {
                r.setSkipped(r.getSkipped() + 1);
                continue;
            }
            if (best == 1) {
                r.setAlive(r.getAlive() + 1);
                markProbe(str(row.get("id")), 1);
                continue;
            }
            if (best == 0) {
                // 不下结论。这些现场只是没覆盖到它而已
                r.setNotCovered(r.getNotCovered() + 1);
                continue;
            }
            r.setAmbiguous(r.getAmbiguous() + 1);
            markProbe(str(row.get("id")), best);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", row.get("id"));
            p.put("nameEn", row.get("name_en"));
            p.put("nameZh", row.get("name_zh"));
            p.put("pageId", row.get("page_id"));
            p.put("locatorType", type);
            p.put("locatorValue", value);
            p.put("matchCount", best);
            p.put("verdict", "定位不唯一（命中 " + best + " 个）：回放只会点第 1 个");
            p.put("suggestions", suggestFor(docs, type, value, str(row.get("name_zh"))));
            r.getProblems().add(p);
        }

        r.getNotes().add("「没覆盖到」不等于失效——这些现场只是没有它该出现的那一页。"
                + "真的失效看报表里的「可疑」，那一栏有 fail_count 做证据");
        log.info("[element-health] 离线体检 app={} platform={}：读现场 {}/{}，元素 {}，"
                        + "确认还活着 {}，不唯一 {}，没覆盖到 {}，跳过 {}",
                appId, platform, r.getScenesParsed(), r.getScenesScanned(), r.getTotal(),
                r.getAlive(), r.getAmbiguous(), r.getNotCovered(), r.getSkipped());
        return r;
    }

    /** 在能找到候选的第一份现场上给建议。机械办法找不出来才轮到人（或模型） */
    private List<Map<String, Object>> suggestFor(List<org.w3c.dom.Document> docs,
                                                 String type, String value, String nameZh) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (org.w3c.dom.Document doc : docs) {
            LocatorRepairSuggester.Suggestion s =
                    LocatorRepairSuggester.suggest(doc, type, value, nameZh);
            if (s.empty()) {
                continue;
            }
            for (LocatorRepairSuggester.Candidate c : s.candidates()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("locatorType", c.locatorType());
                m.put("locatorValue", c.locatorValue());
                m.put("matchCount", c.matchCount());
                m.put("why", c.why());
                out.add(m);
            }
            return out;
        }
        return out;
    }

    /** 失败现场文件，按修改时间从新到旧 */
    private List<java.nio.file.Path> recentSceneFiles(int max) {
        java.nio.file.Path root = java.nio.file.Path.of(
                screenshotDir == null || screenshotDir.isBlank() ? "screenshots" : screenshotDir);
        if (!java.nio.file.Files.isDirectory(root)) {
            return List.of();
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     java.nio.file.Files.walk(root, 2)) {
            return walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.startsWith("pagesource-") && n.endsWith(".xml");
                    })
                    .sorted((a, b) -> Long.compare(mtime(b), mtime(a)))
                    .limit(max)
                    .toList();
        } catch (Exception e) {
            log.warn("[element-health] 扫失败现场目录失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static long mtime(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 离线体检结果。
     *
     * <p>字段名刻意写成「确认还活着 / 不唯一 / 没覆盖到」而不是「健康 / 异常 / 失效」——
     * 前者说的是证据强度，后者是结论。这一层<b>没有资格下失效的结论</b>。
     */
    @Data
    public static class OfflineResult {
        private String appId;
        private String platform;
        private int scenesScanned;
        private int scenesParsed;
        private int total;
        /** 某个现场里命中恰好 1 个：铁证，这条定位此刻是好的 */
        private int alive;
        /** 命中多个：回放只取第 1 个，定时炸弹 */
        private int ambiguous;
        /** 一次都没命中：<b>不下结论</b>，这些现场没覆盖到它 */
        private int notCovered;
        /** 含变量或随机下标，静态求值没有意义 */
        private int skipped;
        private List<Map<String, Object>> problems = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
    }

    /** 与 LocatorTemplate 的随机下标写法对齐：[X] 或 [X:1-5] */
    private static final java.util.regex.Pattern RANDOM_INDEX =
            java.util.regex.Pattern.compile("\\[X(:\\d+-\\d+)?]");

    private void markProbe(String id, int matchCount) {
        try {
            jdbcTemplate.update(
                    "UPDATE ui_elements SET last_probed_at = NOW(), probe_match_count = ? WHERE id = ?",
                    matchCount, id);
        } catch (Exception e) {
            log.debug("[element-health] 写体检结果失败 id={}: {}", id, e.getMessage());
        }
    }

    // ==================== 报表 ====================

    /**
     * 健康报表。
     * <p>
     * 四张表回答四个不同的问题，刻意<b>不合成一个「健康分」</b>——分数看着好看，
     * 但拿到 78 分之后没人知道该干什么，而「这 6 个元素定位已经失效」是能直接派活的。
     *
     * @param staleDays 多少天没被任何用例验证过算「陈旧」
     */
    public Map<String, Object> report(String appId, String platform, int staleDays) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        StringBuilder w = new StringBuilder(" FROM ui_elements WHERE status = 'ACTIVE'");
        if (appId != null && !appId.isBlank()) {
            w.append(" AND app_id = ?");
            args.add(appId);
        }
        if (platform != null && !platform.isBlank()) {
            w.append(" AND platform = ?");
            args.add(platform);
        }
        String where = w.toString();
        Object[] a = args.toArray();
        String cols = "id, app_id, platform, name_en, name_zh, page_id, module_tag, locator_type,"
                + " locator_value, last_verified_at, last_failed_at, fail_count, health_note,"
                + " last_probed_at, probe_match_count";

        out.put("总数", jdbcTemplate.queryForObject("SELECT COUNT(1)" + where, Integer.class, a));

        // 一：连续失败到阈值，最该先看的一批
        out.put("可疑", jdbcTemplate.queryForList(
                "SELECT " + cols + where + " AND fail_count >= " + SUSPECT_THRESHOLD
                        + " ORDER BY fail_count DESC, last_failed_at DESC LIMIT 200", a));

        // 二：体检判定失效或不唯一
        out.put("体检异常", jdbcTemplate.queryForList(
                "SELECT " + cols + where + " AND probe_match_count IS NOT NULL AND probe_match_count <> 1"
                        + " ORDER BY probe_match_count ASC, last_probed_at DESC LIMIT 200", a));

        // 三：久未被任何用例碰过。要么该补用例，要么该降回 CANDIDATE——
        //     ACTIVE 的定义应该是「有用例在用」，不是「这个元素重要」
        out.put("久未验证", jdbcTemplate.queryForList(
                "SELECT " + cols + where
                        + " AND (last_verified_at IS NULL OR last_verified_at < DATE_SUB(NOW(), INTERVAL "
                        + Math.max(1, staleDays) + " DAY))"
                        + " ORDER BY last_verified_at IS NOT NULL, last_verified_at ASC LIMIT 200", a));

        // 四：脆弱定位（xpath 或没有备选）。改版后优先复核这一批
        out.put("脆弱定位", jdbcTemplate.queryForList(
                "SELECT " + cols + where
                        + " AND (locator_type = 'xpath' OR locator_fallbacks IS NULL"
                        + " OR locator_fallbacks = '' OR locator_fallbacks = '[]')"
                        + " ORDER BY locator_type DESC LIMIT 200", a));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("7天内验证过", jdbcTemplate.queryForObject(
                "SELECT COUNT(1)" + where + " AND last_verified_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
                Integer.class, a));
        summary.put("有备选定位", jdbcTemplate.queryForObject(
                "SELECT COUNT(1)" + where + " AND locator_fallbacks IS NOT NULL"
                        + " AND locator_fallbacks <> '' AND locator_fallbacks <> '[]'",
                Integer.class, a));
        summary.put("主定位是xpath", jdbcTemplate.queryForObject(
                "SELECT COUNT(1)" + where + " AND locator_type = 'xpath'", Integer.class, a));
        out.put("概览", summary);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
