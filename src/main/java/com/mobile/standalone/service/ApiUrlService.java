package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiUrlEntry;
import com.mobile.standalone.mapper.ApiUrlMapper;
import com.mobile.standalone.util.TestEnvironment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@DependsOn("apiProjectService")
public class ApiUrlService {

    private final ApiUrlMapper urlMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureTableAndSeed() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS api_url_entries (
                        id VARCHAR(64) PRIMARY KEY,
                        project VARCHAR(64) NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
                        base_url VARCHAR(1024) NOT NULL,
                        description VARCHAR(1024) DEFAULT NULL,
                        enabled TINYINT(1) DEFAULT 1,
                        sort_order INT DEFAULT 100,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_project_env (project, environment)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            projectService.ensureExists(ApiTestCaseService.PROJECT_DATA_CENTER, "APP-DATA-CENTER（数据中心）");
            seedDataCenterUrlsIfEmpty();
            seedIntranetHostUrls();
            log.info("[api-url] 表已就绪");
        } catch (Exception e) {
            log.warn("[api-url] 建表失败: {}", e.getMessage());
        }
    }

    static final String DC_TEST_GATEWAY = "https://gwapi-staging.pwesports.cn";
    static final String DC_PROD_GATEWAY = "https://gwapi.pwesports.cn";

    private void seedDataCenterUrlsIfEmpty() {
        if (urlMapper.countByProject(ApiTestCaseService.PROJECT_DATA_CENTER) > 0) {
            fixDataCenterGateway("url-dc-test", "https://gateway-qa.arca42.com", DC_TEST_GATEWAY);
            fixDataCenterGateway("url-dc-prod", "https://gateway.arca42.com", DC_PROD_GATEWAY);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // project 必须走常量：这里写死 "data-center" 的话，项目码统一成 app-data-center 之后
        // 会直接违反 api_url_entries 的外键（fk_aue_project）
        upsertSeed("url-dc-test", ApiTestCaseService.PROJECT_DATA_CENTER,
                "data-center · 测试网关", TestEnvironment.TEST,
                DC_TEST_GATEWAY, "国内测试网关前缀；用例 path 拼在后面", 10, now);
        upsertSeed("url-dc-prod", ApiTestCaseService.PROJECT_DATA_CENTER,
                "data-center · 线上网关", TestEnvironment.PROD,
                DC_PROD_GATEWAY, "国内生产网关前缀", 20, now);
        log.info("[api-url] 已种子 data-center 测试/线上网关");
    }

    /**
     * app-data-center 是国内服务，早期种子却写成了海外网关 arca42，那边没有 /appdatacenter 路由，一律 404。
     * 只改还停在旧种子值上的记录：人手改过的地址不动
     */
    private void fixDataCenterGateway(String id, String wrongSeed, String gateway) {
        ApiUrlEntry u = urlMapper.findById(id);
        if (u == null || !wrongSeed.equalsIgnoreCase(ApiTestCaseService.trimSlash(u.getBaseUrl()))) return;
        u.setBaseUrl(gateway);
        u.setUpdatedAt(LocalDateTime.now());
        urlMapper.update(u);
        log.info("[api-url] {} 网关 {} → {}", id, wrongSeed, gateway);
    }

    /**
     * 测试环境实体机直连：状态监控 / 接口调试用 IP+端口。
     * 健康路径写在 description，状态监控页按固定 urlId 引用。
     * 按 id 幂等插入，已有记录不覆盖（避免冲掉人手改过的 baseUrl）。
     */
    private void seedIntranetHostUrls() {
        LocalDateTime now = LocalDateTime.now();
        int sort = 50;
        int n = 0;
        // host 172.16.108.178
        n += seedHost(now, sort++, "url-host-app-data-center-178", "app-data-center",
                "data-center · 内网 178", "172.16.108.178", 8103,
                "/appdatacenter/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-video-178", "app-video",
                "app-video · 内网 178", "172.16.108.178", 9077,
                "/avo/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-user-178", "app-user",
                "app-user · 内网 178", "172.16.108.178", 8886,
                "/appuser/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-activity-center-178", "app-activity-center",
                "activity-center · 内网 178", "172.16.108.178", 8102,
                "/activitycenter/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-partneragent-178", "app-partneragent",
                "partneragent · 内网 178", "172.16.108.178", 8107,
                "/partner/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-robot-178", "app-robot",
                "app-robot · 内网 178", "172.16.108.178", 9011,
                "/robot/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-community-178", "app-community",
                "app-community · 内网 178", "172.16.108.178", 8101,
                "/acty/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-event-178", "app-event",
                "app-event · 内网 178", "172.16.108.178", 8888,
                "/eventcenter/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-task-center-178", "app-task-center",
                "task-center · 内网 178", "172.16.108.178", 8106,
                "/task-center/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-coupon-178", "app-coupon",
                "coupon · 内网 178", "172.16.108.178", 8833,
                "/coupon/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-push-178", "app-push",
                "app-push · 内网 178", "172.16.108.178", 8100,
                "/apppush/basic/healthCheck", "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-decoration-178", "app-decoration",
                "app-decoration · 内网 178", "172.16.108.178", 8105,
                "/appdecoration/basic/healthCheck", "/basic/healthCheck");
        // host 172.16.108.120
        n += seedHost(now, sort++, "url-host-app-activity-120", "app-activity",
                "app-activity · 内网 120", "172.16.108.120", 8190,
                "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-mall-center-120", "app-mall-center",
                "mall-center · 内网 120", "172.16.108.120", 8041,
                "/mallCenter/basic/healthCheck", "/basic/healthCheck");
        // comment-service 规范码归到 app-message-center
        n += seedHost(now, sort++, "url-host-app-message-center-120", "app-message-center",
                "comment-service · 内网 120", "172.16.108.120", 8080,
                "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-data-provider-120", "app-data-provider",
                "data-provider · 内网 120", "172.16.108.120", 8049,
                "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-news-publish-120", "app-news-publish",
                "news-publish · 内网 120", "172.16.108.120", 8990,
                "/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-global-agent-120", "app-global-agent",
                "global-agent · 内网 120", "172.16.108.120", 8702,
                "/global-agent/basic/healthCheck", "/glag/basic/healthCheck");
        n += seedHost(now, sort++, "url-host-app-admin-120", "app-admin",
                "app-admin · 内网 120", "172.16.108.120", 8018,
                "/basic/healthCheck");
        if (n > 0) {
            log.info("[api-url] 已种子内网直连 URL {} 条（状态监控探活用）", n);
        }
    }

    private int seedHost(LocalDateTime now, int sort, String id, String project,
                         String name, String host, int port, String... healthPaths) {
        projectService.ensureExists(project, projectDisplayName(project));
        String desc = "健康检查: " + String.join(" | ", healthPaths);
        String baseUrl = "http://" + host + ":" + port;
        if (urlMapper.findById(id) != null) {
            return 0;
        }
        upsertSeed(id, project, name, TestEnvironment.TEST, baseUrl, desc, sort, now);
        return 1;
    }

    private static String projectDisplayName(String code) {
        if (code == null || code.isBlank()) return code;
        return code.toUpperCase(Locale.ROOT);
    }

    private void upsertSeed(String id, String project, String name, String env, String baseUrl,
                            String desc, int sort, LocalDateTime now) {
        if (urlMapper.findById(id) != null) return;
        ApiUrlEntry u = new ApiUrlEntry();
        u.setId(id);
        u.setProject(project);
        u.setName(name);
        u.setEnvironment(env);
        u.setBaseUrl(ApiTestCaseService.trimSlash(baseUrl));
        u.setDescription(desc);
        u.setEnabled(true);
        u.setSortOrder(sort);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        urlMapper.insert(u);
    }

    public List<ApiUrlEntry> list(String project, String environment, Boolean enabledOnly) {
        String env = blank(environment);
        if (env != null && !"ANY".equalsIgnoreCase(env)) {
            env = TestEnvironment.normalize(env);
        }
        return urlMapper.findByFilters(blank(project), env, enabledOnly);
    }

    public ApiUrlEntry get(String id) {
        return urlMapper.findById(id);
    }

    /** 用例没固定地址时，按项目 + 执行环境挑一条：同环境优先于 ANY，同档里按排序取第一条 */
    public ApiUrlEntry autoPick(String project, String environment) {
        String pj = blank(project);
        if (pj == null) return null;
        String env = TestEnvironment.normalize(environment);
        return pick(urlMapper.findByFilters(pj, env, true), env);
    }

    static ApiUrlEntry pick(List<ApiUrlEntry> candidates, String env) {
        if (candidates == null) return null;
        ApiUrlEntry any = null;
        for (ApiUrlEntry u : candidates) {
            if (u == null || Boolean.FALSE.equals(u.getEnabled())
                    || u.getBaseUrl() == null || u.getBaseUrl().isBlank()) {
                continue;
            }
            if (env.equalsIgnoreCase(u.getEnvironment())) return u;
            if (any == null && "ANY".equalsIgnoreCase(u.getEnvironment())) any = u;
        }
        return any;
    }

    @Transactional
    public ApiUrlEntry create(ApiUrlEntry body) {
        validate(body);
        if (body.getId() == null || body.getId().isBlank()) {
            body.setId("url-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (urlMapper.findById(body.getId()) != null) {
            throw new IllegalArgumentException("id 已存在: " + body.getId());
        }
        defaults(body);
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        urlMapper.insert(body);
        return urlMapper.findById(body.getId());
    }

    @Transactional
    public ApiUrlEntry update(String id, ApiUrlEntry body) {
        ApiUrlEntry existing = urlMapper.findById(id);
        if (existing == null) return null;
        body.setId(id);
        validate(body);
        defaults(body);
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(LocalDateTime.now());
        urlMapper.update(body);
        return urlMapper.findById(id);
    }

    @Transactional
    public boolean delete(String id) {
        return urlMapper.deleteById(id) > 0;
    }

    private void validate(ApiUrlEntry body) {
        if (body.getProject() == null || body.getProject().isBlank()) {
            throw new IllegalArgumentException("project 必填（请先在「项目管理」中创建）");
        }
        if (projectService.get(body.getProject().trim()) == null) {
            throw new IllegalArgumentException("项目不存在: " + body.getProject() + "，请先到资产管理 · 项目管理新增");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("name 必填");
        }
        if (body.getBaseUrl() == null || body.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
    }

    private void defaults(ApiUrlEntry body) {
        body.setProject(body.getProject().trim());
        body.setBaseUrl(ApiTestCaseService.trimSlash(body.getBaseUrl().trim()));
        String env = body.getEnvironment() == null ? TestEnvironment.TEST : body.getEnvironment().trim().toUpperCase(Locale.ROOT);
        if (!"ANY".equals(env)) {
            env = TestEnvironment.normalize(env);
        }
        body.setEnvironment(env);
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getSortOrder() == null) body.setSortOrder(100);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
