package com.mobile.standalone.service.perf;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专项指标按<b>版本</b>留档，并和上个版本比。
 * <p>
 * 之前每次专项测试是一次孤立的任务，于是没法回答最该回答的那个问题：
 * 「这个版本比上个版本质量如何」。两周一个大版本，发版评审上要拿出来的正是这个。
 * <p>
 * 比较维度是 <b>(包名, 平台, 机型, 指标)</b>：不同机型的 CPU、帧率根本不是一个量级，
 * 跨机型比出来的百分比没有意义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricBaselineService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS app_version_metrics (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id VARCHAR(64) NULL,
                        app_id VARCHAR(64) NULL,
                        package_name VARCHAR(255) NOT NULL,
                        platform VARCHAR(16) NOT NULL,
                        version_name VARCHAR(64) NOT NULL,
                        device_model VARCHAR(128) NULL,
                        metric_key VARCHAR(64) NOT NULL,
                        metric_value DOUBLE NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_lookup (package_name, platform, device_model, metric_key, created_at),
                        INDEX idx_version (package_name, version_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            log.info("[metric-baseline] 表已就绪");
        } catch (Exception e) {
            log.warn("[metric-baseline] 建表失败: {}", e.getMessage());
        }
    }

    /** 一次专项执行采到的指标 */
    public record Snapshot(String taskId, String appId, String packageName, String platform,
                           String versionName, String deviceModel, Map<String, Double> metrics) {
    }

    /**
     * 留档。
     * <p>
     * 拿不到版本号就<b>不记</b>：记成 "unknown" 的话，几次之后基线就会指向一个
     * 混了好几个版本的 unknown，对比结果全是错的，而且看不出错在哪。
     */
    public int record(Snapshot s) {
        if (s == null || s.metrics() == null || s.metrics().isEmpty()) {
            return 0;
        }
        if (isBlank(s.packageName()) || isBlank(s.versionName())) {
            log.warn("[metric-baseline] 缺包名或版本号，本次不留档 pkg={} version={}",
                    s.packageName(), s.versionName());
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, Double> e : s.metrics().entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            try {
                jdbcTemplate.update(
                        "INSERT INTO app_version_metrics (task_id, app_id, package_name, platform, "
                                + "version_name, device_model, metric_key, metric_value) "
                                + "VALUES (?,?,?,?,?,?,?,?)",
                        s.taskId(), s.appId(), s.packageName(), norm(s.platform()),
                        s.versionName().trim(), s.deviceModel(), e.getKey(), e.getValue());
                n++;
            } catch (Exception ex) {
                log.warn("[metric-baseline] 写入失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return n;
    }

    /**
     * 和上个版本比。
     *
     * @param versionName 本次测的版本
     * @return 每个指标一条结论，变差的排在最前面
     */
    public List<MetricBaseline> compareWithPrevious(String packageName, String platform,
                                                    String versionName, String deviceModel) {
        Map<String, Double> current = latestOf(packageName, platform, versionName, deviceModel);
        if (current.isEmpty()) {
            return List.of();
        }
        String prev = previousVersion(packageName, platform, versionName, deviceModel);
        Map<String, Double> baseline = prev == null
                ? Map.of()
                : latestOf(packageName, platform, prev, deviceModel);

        List<MetricBaseline> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : current.entrySet()) {
            out.add(MetricComparator.compare(
                    e.getKey(), e.getValue(), baseline.get(e.getKey()), prev, deviceModel));
        }
        // 变差的排最前：打开报告，人要看的永远是「哪儿退步了」
        out.sort((a, b) -> Integer.compare(rank(a.getLevel()), rank(b.getLevel())));
        return out;
    }

    private static int rank(MetricBaseline.Level level) {
        return switch (level) {
            case WORSE -> 0;
            case BETTER -> 1;
            case STABLE -> 2;
            case NO_BASELINE -> 3;
        };
    }

    /**
     * 某个版本某机型上，每个指标<b>最近一次</b>的值。
     * <p>
     * 同一个版本可能跑过好几遍（重跑、换设备），取最近一次而不是平均：
     * 平均会把一次明显异常的采样（比如后台在下载）摊进去，结论变糊。
     */
    Map<String, Double> latestOf(String packageName, String platform,
                                 String versionName, String deviceModel) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (isBlank(packageName) || isBlank(versionName)) {
            return out;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT m.metric_key, m.metric_value FROM app_version_metrics m "
                            + "JOIN (SELECT metric_key, MAX(id) AS max_id FROM app_version_metrics "
                            + "      WHERE package_name = ? AND platform = ? AND version_name = ? "
                            + "        AND (? IS NULL OR device_model = ?) "
                            + "      GROUP BY metric_key) t "
                            + "  ON t.max_id = m.id",
                    packageName, norm(platform), versionName.trim(), deviceModel, deviceModel);
            for (Map<String, Object> r : rows) {
                Object v = r.get("metric_value");
                out.put(String.valueOf(r.get("metric_key")),
                        v == null ? null : ((Number) v).doubleValue());
            }
        } catch (Exception e) {
            log.warn("[metric-baseline] 查询失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 上一个版本。
     * <p>
     * 按<b>时间</b>取上一个测过的版本，不按版本号字符串排序——
     * {@code 3.10.0} 字符串比 {@code 3.9.0} 小，按字符串排会拿错基线，
     * 而且这种错很隐蔽：数字看着都对，只是跟错的版本比了。
     */
    String previousVersion(String packageName, String platform, String versionName, String deviceModel) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT version_name FROM app_version_metrics "
                            + "WHERE package_name = ? AND platform = ? "
                            + "  AND (? IS NULL OR device_model = ?) "
                            + "  AND version_name <> ? "
                            + "  AND created_at < (SELECT MIN(created_at) FROM app_version_metrics "
                            + "                     WHERE package_name = ? AND platform = ? AND version_name = ?) "
                            + "ORDER BY created_at DESC LIMIT 1",
                    String.class,
                    packageName, norm(platform), deviceModel, deviceModel, versionName.trim(),
                    packageName, norm(platform), versionName.trim());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[metric-baseline] 查上个版本失败: {}", e.getMessage());
            return null;
        }
    }

    /** 这个包测过哪些版本，新的在前 */
    public List<String> versionsOf(String packageName, String platform) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT version_name FROM app_version_metrics "
                            + "WHERE package_name = ? AND platform = ? "
                            + "GROUP BY version_name ORDER BY MAX(created_at) DESC LIMIT 50",
                    String.class, packageName, norm(platform));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String norm(String platform) {
        return platform == null ? "ANDROID" : platform.trim().toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
