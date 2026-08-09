package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.mapper.ApiTestCollectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用例集合。
 * <p>
 * 集合是<b>执行单元 + 顺序 + 变量作用域</b>三件事合一：集合内按 sortOrder 顺序跑、共享一份运行时上下文，
 * 登录用例 extract 出来的 token 才轮得到后面的用例引用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTestCollectionService {

    private final ApiTestCollectionMapper mapper;
    private final ApiTestCaseMapper caseMapper;

    public List<ApiTestCollection> list(String project) {
        return mapper.findAll(project);
    }

    public ApiTestCollection get(String id) {
        return mapper.findById(id);
    }

    public List<String> caseIdsOf(String collectionId) {
        return mapper.caseIdsOf(collectionId, true);
    }

    @Transactional
    public ApiTestCollection create(ApiTestCollection body) {
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("集合名称不能为空");
        }
        if (body.getProject() == null || body.getProject().isBlank()) {
            throw new IllegalArgumentException("请选择归属项目");
        }
        ApiTestCollection dup = mapper.findByName(body.getProject(), body.getName().trim());
        if (dup != null) {
            throw new IllegalArgumentException("同项目下已有同名集合: " + body.getName());
        }
        body.setId("atcol-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        body.setName(body.getName().trim());
        if (body.getEnabled() == null) {
            body.setEnabled(true);
        }
        if (body.getSortOrder() == null) {
            body.setSortOrder(100);
        }
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        mapper.insert(body);
        return mapper.findById(body.getId());
    }

    @Transactional
    public ApiTestCollection update(String id, ApiTestCollection body) {
        ApiTestCollection existing = mapper.findById(id);
        if (existing == null) {
            return null;
        }
        body.setId(id);
        if (body.getName() == null || body.getName().isBlank()) {
            body.setName(existing.getName());
        }
        if (body.getEnabled() == null) {
            body.setEnabled(existing.getEnabled());
        }
        if (body.getSortOrder() == null) {
            body.setSortOrder(existing.getSortOrder());
        }
        body.setUpdatedAt(LocalDateTime.now());
        mapper.update(body);
        return mapper.findById(id);
    }

    /**
     * 删集合，用例本身保留（只是移出集合）。
     * <p>
     * 连用例一起删太危险：集合是个组织方式，删错一个分组不该赔上里面几十条用例。
     */
    @Transactional
    public boolean delete(String id) {
        List<String> ids = mapper.caseIdsOf(id, false);
        if (!ids.isEmpty()) {
            mapper.assignCases(null, ids);
        }
        return mapper.deleteById(id) > 0;
    }

    @Transactional
    public int assign(String collectionId, List<String> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        return mapper.assignCases(collectionId, caseIds);
    }

    /**
     * 按 Controller 自动归组。
     * <p>
     * 导进来的用例 {@code requirementRef} 就是 Controller 名（如 {@code AiController}），
     * 现成的分组依据。几百条用例平铺在一个项目下没法用——既选不出「这次只跑活动相关的」，
     * 也没法给它们排一个有意义的执行顺序。
     * <p>
     * 只处理<b>还没归组</b>的用例：已经手工整理过的分组不能被一次「自动归组」冲掉。
     *
     * @return 新建的集合数 → 归组的用例数
     */
    @Transactional
    public Map<String, Integer> autoGroup(String project) {
        List<ApiTestCase> all = caseMapper.findByProject(project);
        Map<String, List<String>> byController = new LinkedHashMap<>();
        for (ApiTestCase c : all) {
            if (c.getCollectionId() != null && !c.getCollectionId().isBlank()) {
                continue;
            }
            String group = groupNameOf(c);
            byController.computeIfAbsent(group, k -> new ArrayList<>()).add(c.getId());
        }
        int created = 0;
        int assigned = 0;
        int order = 10;
        for (Map.Entry<String, List<String>> e : byController.entrySet()) {
            ApiTestCollection col = mapper.findByName(project, e.getKey());
            if (col == null) {
                ApiTestCollection n = new ApiTestCollection();
                n.setProject(project);
                n.setName(e.getKey());
                n.setDescription("按 Controller 自动归组");
                n.setSortOrder(order);
                col = create(n);
                created++;
            }
            assigned += assign(col.getId(), e.getValue());
            order += 10;
        }
        log.info("[api-test] 自动归组 project={} 新建 {} 个集合，归组 {} 条用例", project, created, assigned);
        return Map.of("created", created, "assigned", assigned);
    }

    /**
     * 组名：Controller 名去掉 {@code Controller} 后缀。
     * <p>
     * 拿不到就归到「未分类」而不是丢掉——静默漏掉几条用例，比多一个杂项分组糟得多。
     */
    static String groupNameOf(ApiTestCase c) {
        String ref = c.getRequirementRef();
        if (ref != null && !ref.isBlank()) {
            String s = ref.trim();
            if (s.endsWith("Controller") && s.length() > "Controller".length()) {
                s = s.substring(0, s.length() - "Controller".length());
            }
            return s;
        }
        return "未分类";
    }
}
