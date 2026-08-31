package com.mobile.standalone.service;

import com.mobile.standalone.entity.AppProjectLink;
import com.mobile.standalone.mapper.AppProjectLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端 App ↔ 项目 关联的维护。
 * <p>
 * 这层关联是「统一树」的前提：没有它，UI 自动化的资产（流程／页面／元素）按 appId 归属，
 * 接口测试的资产按 project 归属，两边永远挂不到同一个节点下。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppProjectLinkService {

    /** 端的取值。留空表示不分端 */
    private static final Set<String> PLATFORMS = Set.of("ANDROID", "IOS", "HARMONY");

    private final AppProjectLinkMapper mapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    public List<AppProjectLink> list(String appId, String projectCode) {
        return mapper.findByFilters(appId, projectCode);
    }

    /** 沿关联统计各项目的 UI 资产量，供树节点显示覆盖情况 */
    public List<Map<String, Object>> assetStats(String projectCode) {
        return mapper.countAssetsByProject(projectCode);
    }

    /** 还没挂上任何项目的 App——这些 App 的资产在树上是看不见的 */
    public List<Map<String, Object>> unlinkedApps() {
        return mapper.findUnlinkedApps();
    }

    /**
     * 新增或更新一条关联。
     * <p>
     * 两端都做存在性校验：外键虽然也会拦，但那样用户拿到的是一句 MySQL 报错，
     * 既看不懂也不知道去哪修。
     */
    @Transactional
    public AppProjectLink save(AppProjectLink body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String appId = trim(body.getAppId());
        String projectCode = trim(body.getProjectCode());
        if (appId.isEmpty()) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        if (projectCode.isEmpty()) {
            throw new IllegalArgumentException("projectCode 不能为空");
        }
        if (!appExists(appId)) {
            throw new IllegalArgumentException("App 不存在: " + appId + "，请先到「应用字典」创建");
        }
        if (projectService.get(projectCode) == null) {
            throw new IllegalArgumentException("项目不存在: " + projectCode + "，请先到「项目管理」创建");
        }

        String platform = trim(body.getPlatform()).toUpperCase(Locale.ROOT);
        if (!platform.isEmpty() && !PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("platform 只能是 ANDROID / IOS / HARMONY，或留空表示不分端");
        }
        String relation = trim(body.getRelation()).toUpperCase(Locale.ROOT);
        if (relation.isEmpty()) {
            relation = AppProjectLink.RELATION_OWNS;
        }
        if (!AppProjectLink.RELATION_OWNS.equals(relation)
                && !AppProjectLink.RELATION_SERVES.equals(relation)) {
            throw new IllegalArgumentException("relation 只能是 OWNS（客户端本体）或 SERVES（服务方）");
        }

        body.setAppId(appId);
        body.setProjectCode(projectCode);
        body.setPlatform(platform.isEmpty() ? null : platform);
        body.setRelation(relation);

        if (mapper.findOne(appId, projectCode) != null) {
            mapper.update(body);
        } else {
            mapper.insert(body);
        }
        return mapper.findOne(appId, projectCode);
    }

    @Transactional
    public boolean delete(String appId, String projectCode) {
        return mapper.delete(appId, projectCode) > 0;
    }

    private boolean appExists(String appId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ui_apps WHERE id = ?", Integer.class, appId);
        return n != null && n > 0;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
