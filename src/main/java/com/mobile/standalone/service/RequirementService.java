package com.mobile.standalone.service;

import com.mobile.standalone.entity.ProjectModule;
import com.mobile.standalone.entity.Requirement;
import com.mobile.standalone.mapper.ProjectModuleMapper;
import com.mobile.standalone.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 需求登记的维护。
 * <p>
 * 单号来自 JIRA，平台只登记不同步。这里要保证的是：录进来的需求能回答
 * 「属于哪个项目、哪个版本、覆盖哪些模块、测到什么程度」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementService {

    private static final Set<String> STATUSES = Set.of(
            Requirement.STATUS_PLANNING,
            Requirement.STATUS_TESTING,
            Requirement.STATUS_DONE,
            Requirement.STATUS_CANCELLED);

    private final RequirementMapper mapper;
    private final ProjectModuleMapper moduleMapper;
    private final ApiProjectService projectService;
    private final JdbcTemplate jdbcTemplate;

    public List<Requirement> list(String projectCode, String versionId, String status, String keyword) {
        List<Requirement> rows = mapper.findByFilters(projectCode, versionId, status, keyword);
        for (Requirement r : rows) {
            r.setModules(mapper.findModules(r.getRequirementNo()));
        }
        return rows;
    }

    public Requirement get(String requirementNo) {
        Requirement r = mapper.findByNo(requirementNo);
        if (r != null) {
            r.setModules(mapper.findModules(requirementNo));
        }
        return r;
    }

    /**
     * 新增或更新。
     * <p>
     * 模块关联走「先清空再写入」：调用方传的是这个需求<b>当前覆盖的完整模块集合</b>，
     * 增量对比反而容易漏掉「取消勾选」那一半。
     */
    @Transactional
    public Requirement save(Requirement body, List<String> moduleIds) {
        if (body == null) {
            throw new IllegalArgumentException("请求体为空");
        }
        String no = trim(body.getRequirementNo()).toUpperCase(Locale.ROOT);
        if (no.isEmpty()) {
            throw new IllegalArgumentException("需求单号不能为空，如 APP-8999");
        }

        String projectCode = trim(body.getProjectCode());
        if (!projectCode.isEmpty() && projectService.get(projectCode) == null) {
            throw new IllegalArgumentException("项目不存在: " + projectCode + "，请先到「项目管理」创建");
        }
        String versionId = trim(body.getVersionId());
        if (!versionId.isEmpty() && !versionExists(versionId)) {
            throw new IllegalArgumentException("版本不存在: " + versionId + "，请先到「版本管理」创建");
        }
        String status = trim(body.getStatus()).toUpperCase(Locale.ROOT);
        if (status.isEmpty()) {
            status = Requirement.STATUS_PLANNING;
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException(
                    "status 只能是 PLANNING / TESTING / DONE / CANCELLED");
        }

        body.setRequirementNo(no);
        body.setProjectCode(projectCode.isEmpty() ? null : projectCode);
        body.setVersionId(versionId.isEmpty() ? null : versionId);
        body.setStatus(status);

        if (mapper.findByNo(no) == null) {
            mapper.insert(body);
        } else {
            mapper.update(body);
        }

        if (moduleIds != null) {
            replaceModules(no, moduleIds);
        }
        return get(no);
    }

    @Transactional
    public void replaceModules(String requirementNo, List<String> moduleIds) {
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(
                moduleIds == null ? List.of() : moduleIds.stream()
                        .map(RequirementService::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()));
        for (String id : clean) {
            ProjectModule m = moduleMapper.findById(id);
            if (m == null) {
                throw new IllegalArgumentException("模块不存在: " + id);
            }
        }
        mapper.clearModules(requirementNo);
        if (!clean.isEmpty()) {
            mapper.addModules(requirementNo, clean);
        }
    }

    /**
     * 删除需求登记。
     * <p>
     * 模块关联由外键 CASCADE 一并清掉——那只是关联行，没有独立价值。
     * 但用例集合、接口、Bug 里引用的单号是<b>字符串</b>不是外键，删了登记不会影响它们，
     * 只是那些数据从此挂不回一个有版本、有模块的需求上。
     */
    @Transactional
    public boolean delete(String requirementNo) {
        return mapper.deleteByNo(requirementNo) > 0;
    }

    /**
     * 只改展示标题，供测试用例左侧树重命名。
     * 单号来自 JIRA 不能改，但「选择玩家 · APP-8999」这类可读名可以手工维护。
     */
    @Transactional
    public Requirement upsertTitle(String requirementNo, String title) {
        String no = trim(requirementNo).toUpperCase(Locale.ROOT);
        if (no.isEmpty()) {
            throw new IllegalArgumentException("需求单号不能为空");
        }
        String t = trim(title);
        Requirement existing = mapper.findByNo(no);
        if (existing == null) {
            Requirement created = new Requirement();
            created.setRequirementNo(no);
            created.setTitle(t.isEmpty() ? null : t);
            created.setStatus(Requirement.STATUS_PLANNING);
            mapper.insert(created);
        } else {
            existing.setTitle(t.isEmpty() ? null : t);
            mapper.update(existing);
        }
        return get(no);
    }

    private boolean versionExists(String versionId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_version_archives WHERE id = ?", Integer.class, versionId);
        return n != null && n > 0;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
