package com.mobile.standalone.service.capture;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 抓包会话的落盘与登记。
 * <p>
 * 原来抓包写在 {@code Files.createTempFile} 里，会话只活在内存的 Map，
 * 而且下一场开始时会把上一场的响应体删掉。抓完当场解析接口是够用的，
 * 但「昨天那次抓的再看一眼」和「拿历史流量算压测配比」都做不到。
 * <p>
 * 这里把两件事分开：
 * <ul>
 *   <li><b>元数据入库</b>——设备、需求单、域名、条数、起止时间，重启后还能列出来</li>
 *   <li><b>流水与响应体留在磁盘</b>——沿用原来的判断，几十 MB 的响应进库只会把库撑大，
 *       而「这个需求走了哪些接口」根本不需要它。只是从临时目录挪到稳定目录，
 *       并且不再被下一场删掉</li>
 * </ul>
 * 不再随手删就得有人管磁盘，所以配了保留策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureStore {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_ABORTED = "ABORTED";

    /** 抓包根目录。放 user.home 而不是 /tmp：/tmp 会被系统清理，那正是要避免的 */
    @Value("${app.capture.store-dir:${user.home}/.mobile-standalone/captures}")
    private String storeDirRoot;

    /** 保留天数。抓包文件里有真实响应体，留太久既占盘也是数据暴露面 */
    @Value("${app.capture.retain-days:30}")
    private int retainDays;

    @Value("${app.capture.cleanup-enabled:true}")
    private boolean cleanupEnabled;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动对账：把上次进程留下的 RUNNING 改成 ABORTED。
     * <p>
     * 抓包进程是后端的子进程，后端一没了它也没了。不对账的话，
     * 库里会永远躺着一场「进行中」的抓包，而 {@code start} 看到有进行中的就拒绝开新的——
     * 一次异常退出会让抓包功能从此用不了。
     */
    @PostConstruct
    public void reconcileOnBoot() {
        try {
            Files.createDirectories(Path.of(root()));
            int n = jdbcTemplate.update(
                    "UPDATE capture_sessions SET status = ?, stopped_at = COALESCE(stopped_at, NOW()) "
                            + "WHERE status = ?", STATUS_ABORTED, STATUS_RUNNING);
            if (n > 0) {
                log.info("[capture] 启动对账：{} 场残留的进行中会话已标记为中断", n);
            }
        } catch (Exception e) {
            log.warn("[capture] 启动对账失败: {}", e.getMessage());
        }
    }

    /** 这一场的落盘目录；流水与响应体都在里面 */
    public Path dirOf(String sessionId) {
        return Path.of(root(), safe(sessionId));
    }

    /**
     * 响应改写规则文件。<b>全局一份，不按会话分</b>。
     *
     * <p>按会话分看着更干净，但那会让「现在代理上到底挂没挂改写」变成一个
     * 要翻目录才能回答的问题。而这恰恰是最需要一眼看清的事——
     * 一次被悄悄改写过的执行，它的结论是不能信的。
     */
    public Path overrideRulesFile() {
        return Path.of(root(), "api-overrides.json");
    }

    /** 流水文件。mitmdump 直接往这里写 JSONL */
    public Path flowFileOf(String sessionId) throws IOException {
        Path dir = dirOf(sessionId);
        Files.createDirectories(dir);
        return dir.resolve("flows.jsonl");
    }

    public void open(String sessionId, String deviceId, String platform,
                     String requirementNo, String project, String hosts, Path storeDir) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO capture_sessions
                        (id, device_id, platform, requirement_no, project, hosts, store_dir, status, started_at)
                    VALUES (?,?,?,?,?,?,?,?,NOW())
                    ON DUPLICATE KEY UPDATE device_id = VALUES(device_id), status = VALUES(status),
                        started_at = NOW(), stopped_at = NULL
                    """, sessionId, deviceId, platform, blank(requirementNo), blank(project),
                    blank(hosts), storeDir == null ? null : storeDir.toString(), STATUS_RUNNING);
        } catch (Exception e) {
            // 登记失败不该让抓包起不来：抓包本身是有用的，登记只是为了以后能翻出来
            log.warn("[capture] 会话登记失败 {}: {}", sessionId, e.getMessage());
        }
    }

    public void close(String sessionId, int flowCount, int hostCount) {
        try {
            jdbcTemplate.update("""
                    UPDATE capture_sessions
                    SET status = ?, flow_count = ?, host_count = ?, stopped_at = NOW()
                    WHERE id = ?
                    """, STATUS_FINISHED, flowCount, hostCount, sessionId);
        } catch (Exception e) {
            log.warn("[capture] 会话收尾失败 {}: {}", sessionId, e.getMessage());
        }
    }

    /** 历史会话列表；requirementNo 为空则不过滤 */
    public List<Map<String, Object>> list(String requirementNo, int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        try {
            if (requirementNo != null && !requirementNo.isBlank()) {
                return jdbcTemplate.queryForList(
                        "SELECT * FROM capture_sessions WHERE requirement_no = ? "
                                + "ORDER BY started_at DESC LIMIT " + lim, requirementNo.trim());
            }
            return jdbcTemplate.queryForList(
                    "SELECT * FROM capture_sessions ORDER BY started_at DESC LIMIT " + lim);
        } catch (Exception e) {
            log.warn("[capture] 查询会话失败: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> get(String sessionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM capture_sessions WHERE id = ?", sessionId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 过期清理。
     * <p>
     * 不再随手删上一场之后，磁盘就得有人管。抓包文件里有真实响应体和 token，
     * 留太久不只占盘，也是数据暴露面——所以默认只留 30 天。
     */
    @Scheduled(cron = "${app.capture.cleanup-cron:0 15 3 * * ?}")
    public void cleanup() {
        if (!cleanupEnabled) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retainDays, 1));
        List<Map<String, Object>> old;
        try {
            old = jdbcTemplate.queryForList(
                    "SELECT id, store_dir FROM capture_sessions WHERE started_at < ?", cutoff);
        } catch (Exception e) {
            log.warn("[capture] 清理查询失败: {}", e.getMessage());
            return;
        }
        int files = 0;
        for (Map<String, Object> row : old) {
            Object dir = row.get("store_dir");
            if (dir != null && deleteTree(Path.of(String.valueOf(dir)))) {
                files++;
            }
        }
        try {
            // 元数据保留、只删文件：「几月几号抓过一场、多少条」这种记录很小，
            // 留着能回答「那次到底抓没抓」，而占盘的是响应体
            jdbcTemplate.update(
                    "UPDATE capture_sessions SET store_dir = NULL WHERE started_at < ? AND store_dir IS NOT NULL",
                    cutoff);
        } catch (Exception ignore) {
            // 清理失败不影响下一次
        }
        if (files > 0) {
            log.info("[capture] 已清理 {} 场超过 {} 天的抓包文件", files, retainDays);
        }
    }

    private boolean deleteTree(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 删不掉就留着，绝不能因为清理失败影响别的
                }
            });
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String root() {
        return storeDirRoot == null || storeDirRoot.isBlank()
                ? System.getProperty("user.home") + "/.mobile-standalone/captures"
                : storeDirRoot;
    }

    /**
     * sessionId 会拼进路径，挡掉路径穿越。
     * <p>
     * 只过滤分隔符是不够的：{@code .} 和 {@code -} 都在白名单里，所以 {@code ".."}
     * 会原样通过，而 {@code Path.of(root, "..")} 规范化之后就跑到根目录的上一级去了。
     * 单独把纯点的串挡掉。
     */
    static String safe(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "unknown";
        }
        String cleaned = sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        // 全是点（. / .. / ...）的都指向目录自身或上级，一律换掉
        return cleaned.chars().allMatch(c -> c == '.') ? "unknown" : cleaned;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
