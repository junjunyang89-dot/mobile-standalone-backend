package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.mapper.ApiTestCollectionMapper;
import com.mobile.standalone.service.ApiProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTestCollectionService {

    private final ApiTestCollectionMapper mapper;
    private final ApiTestCaseMapper caseMapper;
    private final ApiProjectMapper projectMapper;
    private final ApiEndpointService endpointService;
    private final RequirementEndpointService requirementEndpointService;
    private final ApiProjectService projectService;
    private final com.mobile.standalone.service.AiTestCaseService aiTestCaseService;
    private final com.mobile.standalone.service.RequirementService requirementService;

    public List<ApiTestCollection> list(String project, String scope, Boolean includeArchived, String appVersion) {
        return mapper.findAll(project, scope, null, includeArchived, appVersion, null);
    }

    /**
     * 这个需求单在这个项目下已经有的测试集合。
     * <p>
     * 一单一集合：「本单上线」是按需求单整批归档的，接口散在好几个集合里那一步就归不干净。
     */
    public ApiTestCollection findTestCollection(String project, String requirementNo) {
        if (project == null || requirementNo == null || requirementNo.isBlank()) {
            return null;
        }
        return list(project, "TEST", false, null).stream()
                .filter(c -> requirementNo.trim().equalsIgnoreCase(c.getRequirementNo()))
                .findFirst()
                .orElse(null);
    }

    public ApiTestCollection get(String id) {
        return mapper.findById(id);
    }

    public List<String> caseIdsOf(String collectionId) {
        return mapper.caseIdsOf(collectionId, true);
    }

    public List<Map<String, Object>> casesOf(String collectionId) {
        return caseRows(caseMapper.findByFilters(null, collectionId, null, false, null, null, null, null));
    }

    private List<Map<String, Object>> caseRows(List<ApiTestCase> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, ApiEndpoint> endpoints = endpointService.getAll(
                rows.stream().map(ApiTestCase::getEndpointId).toList());
        for (ApiTestCase c : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("project", c.getProject());
            m.put("method", c.getMethod());
            m.put("path", c.getPath());
            m.put("enabled", c.getEnabled());
            m.put("testOnly", c.getTestOnly());
            ApiEndpoint ep = c.getEndpointId() == null ? null : endpoints.get(c.getEndpointId());
            m.put("controller", controllerOfCase(c, ep));
            EndpointLevel level = ApiEndpointService.levelOf(ep);
            m.put("level", level == null ? null : level.name());
            m.put("description", c.getDescription());
            m.put("environment", c.getEnvironment());
            m.put("requirementRef", c.getRequirementRef());
            m.put("urlId", c.getUrlId());
            m.put("query", c.getQuery());
            m.put("headers", c.getHeaders());
            m.put("body", c.getBody());
            m.put("assertions", c.getAssertions());
            m.put("extractJson", c.getExtractJson());
            m.put("fieldNotes", c.getFieldNotes());
            m.put("sortOrder", c.getSortOrder());
            m.put("endpointId", c.getEndpointId());
            m.put("caseKind", c.getCaseKind());
            m.put("sourceBranch", c.getSourceBranch());
            m.put("intent", c.getIntent());
            m.put("expectNote", c.getExpectNote());
            m.put("updatedAt", c.getUpdatedAt());
            out.add(m);
        }
        return out;
    }

    /**
     * 测试：仅 APP → 全部 catalog 项目 → 手动创建的集合。
     * 生产：APP / PVP / WEB → 项目（即 master 集合）→ 接口，项目下不再展示集合层。
     */
    public Map<String, Object> tree(String scope, String appVersion, boolean includeArchived) {
        String effectiveScope = scope == null || scope.isBlank() ? ApiTestCollection.SCOPE_LIVE : scope;
        if (ApiTestCollection.SCOPE_TEST.equals(effectiveScope)) {
            return buildTestTree(appVersion, includeArchived);
        }
        return buildLiveTree(includeArchived);
    }

    private Map<String, Object> buildTestTree(String appVersion, boolean includeArchived) {
        Map<String, Map<String, Map<String, Object>>> collectionsByProjectReq = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> collectionsByProjectBranch = new LinkedHashMap<>();
        indexTestCollections(collectionsByProjectReq, collectionsByProjectBranch,
                mapper.findAll(null, ApiTestCollection.SCOPE_TEST, null, includeArchived, null, null));
        Map<String, String> baseBranches = projectBaseBranches();
        collectionsByProjectBranch.forEach((code, byBranch) ->
                byBranch.keySet().removeIf(b -> BaselineBranches.isBaseline(b, baseBranches.get(code))));

        Map<String, List<Map<String, Object>>> branchesByProject = new LinkedHashMap<>();
        for (Map<String, Object> r : endpointService.summaryByBranch()) {
            String code = treeProjectCode(r.get("project"));
            if (code.isBlank()) {
                continue;
            }
            String branch = treeStr(r.get("branch"));
            if (branch.isBlank() || BaselineBranches.isBaseline(branch, baseBranches.get(code))) {
                continue;
            }
            Map<String, Object> item = branchLeafItem(code, branch);
            item.put("endpointTotal", num(r.get("total")));
            branchesByProject.computeIfAbsent(code, k -> new ArrayList<>()).add(item);
        }

        Map<String, List<Map<String, Object>>> reqsByProject = new LinkedHashMap<>();
        for (Map<String, Object> r : requirementEndpointService.summaryByProjectAndRequirement()) {
            String code = treeProjectCode(r.get("project"));
            if (code.isBlank()) {
                continue;
            }
            String reqNo = treeStr(r.get("requirementNo"));
            if (reqNo.isBlank()) {
                continue;
            }
            int total = num(r.get("total"));
            int verified = num(r.get("verified"));
            Map<String, Object> item = requirementLeafItem(code, reqNo, total, verified);
            reqsByProject.computeIfAbsent(code, k -> new ArrayList<>()).add(item);
        }

        Set<String> projectCodes = new LinkedHashSet<>();
        projectCodes.addAll(collectionsByProjectReq.keySet());
        projectCodes.addAll(collectionsByProjectBranch.keySet());
        projectCodes.addAll(branchesByProject.keySet());
        projectCodes.addAll(reqsByProject.keySet());

        Map<String, List<Map<String, Object>>> projectsByCategory = new LinkedHashMap<>();
        Map<String, String> categoryOverrides = projectCategoryOverrides();
        for (String code : projectCodes) {
            List<Map<String, Object>> branchItems = mergeBranchItems(
                    branchesByProject.getOrDefault(code, List.of()),
                    collectionsByProjectBranch.getOrDefault(code, Map.of()));
            enrichBranchInterfaces(branchItems);
            List<Map<String, Object>> reqItems = mergeRequirementItems(
                    reqsByProject.getOrDefault(code, List.of()),
                    collectionsByProjectReq.getOrDefault(code, Map.of()));
            enrichRequirementInterfaces(reqItems);

            List<Map<String, Object>> groups = new ArrayList<>();
            if (!branchItems.isEmpty()) {
                groups.add(treeGroup("branch-group", code, "分支涉及的接口用例", branchItems));
            }
            if (!reqItems.isEmpty()) {
                groups.add(treeGroup("req-group", code, "需求单接口", reqItems));
            }
            if (groups.isEmpty()) {
                continue;
            }

            Map<String, Object> proj = new LinkedHashMap<>();
            proj.put("code", code);
            proj.put("label", code);
            proj.put("collectionCount", branchItems.size() + reqItems.size());
            proj.put("groups", groups);
            projectsByCategory.computeIfAbsent(categoryOfProject(code, categoryOverrides), k -> new ArrayList<>())
                    .add(proj);
        }

        List<Map<String, Object>> categories = buildCategoryNodes(projectsByCategory);
        return Map.of(
                "treeMode", "TEST",
                "scope", ApiTestCollection.SCOPE_TEST,
                "appVersion", appVersion == null ? "" : appVersion,
                "categories", categories);
    }

    private void indexTestCollections(
            Map<String, Map<String, Map<String, Object>>> collectionsByProjectReq,
            Map<String, Map<String, Map<String, Object>>> collectionsByProjectBranch,
            List<ApiTestCollection> collections) {
        for (ApiTestCollection c : collections) {
            String code = treeProjectCode(c.getProject());
            if (code.isBlank()) {
                continue;
            }
            Map<String, Object> vo = collectionVo(c);
            if (c.getRequirementNo() != null && !c.getRequirementNo().isBlank()) {
                String req = c.getRequirementNo().trim();
                vo.put("label", req);
                vo.put("statusTag", collectionStatusTag(c));
                collectionsByProjectReq
                        .computeIfAbsent(code, k -> new LinkedHashMap<>())
                        .put(reqKey(req), vo);
            }
            if (c.getSourceBranch() != null && !c.getSourceBranch().isBlank()) {
                String branch = c.getSourceBranch().trim();
                vo.put("label", branch);
                vo.put("statusTag", "待测");
                collectionsByProjectBranch
                        .computeIfAbsent(code, k -> new LinkedHashMap<>())
                        .put(branch, vo);
            }
        }
    }

    private static List<Map<String, Object>> mergeBranchItems(
            List<Map<String, Object>> branchRows,
            Map<String, Map<String, Object>> collectionsByBranch) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : branchRows) {
            String branch = treeStr(row.get("sourceBranch"));
            if (branch.isBlank()) {
                continue;
            }
            merged.put(branch, new LinkedHashMap<>(row));
        }
        for (Map.Entry<String, Map<String, Object>> e : collectionsByBranch.entrySet()) {
            Map<String, Object> item = merged.computeIfAbsent(e.getKey(), k -> branchLeafItem(
                    treeStr(e.getValue().get("project")), e.getKey()));
            item.putAll(e.getValue());
            if (!item.containsKey("statusTag")) {
                item.put("statusTag", "待测");
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(treeStr(a.get("label")), treeStr(b.get("label"))));
        return out;
    }

    private static List<Map<String, Object>> mergeRequirementItems(
            List<Map<String, Object>> reqRows,
            Map<String, Map<String, Object>> collectionsByReq) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : reqRows) {
            String reqNo = treeStr(row.get("requirementNo"));
            if (reqNo.isBlank()) {
                continue;
            }
            merged.put(reqKey(reqNo), new LinkedHashMap<>(row));
        }
        for (Map.Entry<String, Map<String, Object>> e : collectionsByReq.entrySet()) {
            Map<String, Object> item = merged.computeIfAbsent(e.getKey(), k -> requirementLeafItem(
                    treeStr(e.getValue().get("project")),
                    treeStr(e.getValue().get("requirementNo")),
                    0,
                    0));
            item.putAll(e.getValue());
            if (!item.containsKey("statusTag")) {
                item.put("statusTag", collectionStatusTagFromVo(item));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(treeStr(b.get("label")), treeStr(a.get("label"))));
        return out;
    }

    private static Map<String, Object> branchLeafItem(String project, String branch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", branch);
        item.put("sourceBranch", branch);
        item.put("project", project);
        item.put("kind", "branch");
        item.put("statusTag", "待测");
        return item;
    }

    private static Map<String, Object> requirementLeafItem(String project, String reqNo, int total, int verified) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", reqNo);
        item.put("requirementNo", reqNo);
        item.put("project", project);
        item.put("kind", "requirement");
        item.put("endpointTotal", total);
        item.put("endpointVerified", verified);
        item.put("statusTag", total > 0 && verified >= total ? "已验证" : total > 0 ? "进行中" : "待测");
        return item;
    }

    private static String collectionStatusTagFromVo(Map<String, Object> vo) {
        Object passRate = vo.get("passRate");
        if (passRate instanceof Number n && n.doubleValue() >= 100) {
            return "已验证";
        }
        return vo.get("requirementNo") != null ? "进行中" : "待测";
    }

    private static String treeProjectCode(Object project) {
        if (project == null) {
            return "";
        }
        String raw = String.valueOf(project).trim();
        if (raw.isBlank()) {
            return "";
        }
        return ApiAppCatalog.canonicalCode(ApiAppCatalog.treeProjectKey(raw));
    }

    private static String treeStr(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String reqKey(String reqNo) {
        return reqNo == null ? "" : reqNo.trim().toUpperCase(Locale.ROOT);
    }

    /** 需求单集合下挂的接口预览，供用例集合树直接展示 */
    private void enrichRequirementInterfaces(List<Map<String, Object>> reqItems) {
        for (Map<String, Object> item : reqItems) {
            String reqNo = treeStr(item.get("requirementNo"));
            if (reqNo.isBlank()) {
                continue;
            }
            String colId = treeStr(item.get("id"));
            item.put("interfaces", interfacePreviewsForRequirement(reqNo, colId.isBlank() ? null : colId));
        }
    }

    private void enrichBranchInterfaces(List<Map<String, Object>> branchItems) {
        for (Map<String, Object> item : branchItems) {
            String project = treeStr(item.get("project"));
            String branch = treeStr(item.get("sourceBranch"));
            if (project.isBlank() || branch.isBlank()) {
                continue;
            }
            String colId = treeStr(item.get("id"));
            item.put("interfaces", interfacePreviewsForBranch(project, branch, colId.isBlank() ? null : colId));
        }
    }

    private List<Map<String, Object>> interfacePreviewsForRequirement(String requirementNo, String collectionId) {
        return mergeInterfacePreviews(requirementEndpointService.listByRequirement(requirementNo), collectionId);
    }

    private List<Map<String, Object>> interfacePreviewsForBranch(String project, String branch, String collectionId) {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        java.util.Set<String> ofBranch = endpointService.endpointIdsOfBranch(project, branch);
        Map<String, List<Map<String, Object>>> changes = endpointService.branchesOfEndpoints(ofBranch);
        for (var ep : endpointService.search(project, null, null, null)) {
            if (!ofBranch.contains(ep.getId()) && !branch.equals(treeStr(ep.getSourceBranch()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("httpMethod", ep.getHttpMethod());
            row.put("path", ep.getPath());
            row.put("summary", ep.getSummary());
            row.put("endpointId", ep.getId());
            Map<String, Object> own = changes.getOrDefault(ep.getId(), List.of()).stream()
                    .filter(c -> branch.equals(treeStr(c.get("branch"))))
                    .findFirst().orElse(null);
            if (own != null) {
                row.put("changeFlag", own.get("changeFlag"));
                row.put("changeNote", own.get("changeNote"));
            }
            endpoints.add(row);
        }
        return mergeInterfacePreviews(endpoints, collectionId);
    }

    private List<Map<String, Object>> mergeInterfacePreviews(List<Map<String, Object>> endpoints, String collectionId) {
        Map<String, List<Map<String, Object>>> casesByKey = new LinkedHashMap<>();
        if (collectionId != null && !collectionId.isBlank()) {
            for (Map<String, Object> c : casesOf(collectionId)) {
                String key = ifaceKey(treeStr(c.get("method")), treeStr(c.get("path")));
                if (key.isBlank()) {
                    continue;
                }
                casesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }
        LinkedHashMap<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> ep : endpoints) {
            String method = treeStr(ep.get("httpMethod"));
            String path = treeStr(ep.get("path"));
            if (path.isBlank()) {
                continue;
            }
            String key = ifaceKey(method, path);
            List<Map<String, Object>> cases = casesByKey.getOrDefault(key, List.of());
            Map<String, Object> preview = interfacePreviewRow(method, path, ep.get("summary"), cases);
            if (ep.get("id") != null) {
                preview.put("linkId", ep.get("id"));
            }
            if (ep.get("endpointId") != null) {
                preview.put("endpointId", ep.get("endpointId"));
            }
            if (ep.get("changeFlag") != null) {
                preview.put("changeFlag", ep.get("changeFlag"));
                preview.put("changeNote", ep.get("changeNote"));
            }
            grouped.put(key, preview);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : casesByKey.entrySet()) {
            if (grouped.containsKey(e.getKey()) || e.getValue().isEmpty()) {
                continue;
            }
            Map<String, Object> c0 = e.getValue().get(0);
            grouped.put(e.getKey(), interfacePreviewRow(treeStr(c0.get("method")), treeStr(c0.get("path")), null, e.getValue()));
        }
        List<Map<String, Object>> out = new ArrayList<>(grouped.values());
        out.sort((a, b) -> treeStr(a.get("path")).compareToIgnoreCase(treeStr(b.get("path"))));
        return out;
    }

    private static Map<String, Object> interfacePreviewRow(String method, String path, Object summary,
                                                           List<Map<String, Object>> cases) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("method", method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT));
        preview.put("path", path);
        if (summary != null) {
            preview.put("summary", summary);
        }
        int caseCount = cases == null ? 0 : cases.size();
        preview.put("caseCount", caseCount);
        long enabled = cases == null ? 0 : cases.stream().filter(c -> Boolean.TRUE.equals(c.get("enabled"))).count();
        preview.put("enabledCount", (int) enabled);
        preview.put("statusTag", interfaceStatusTag(cases));
        return preview;
    }

    private static String ifaceKey(String method, String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String m = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        return m + ":" + path.trim();
    }

    private static String interfaceStatusTag(List<Map<String, Object>> cases) {
        if (cases == null || cases.isEmpty()) {
            return "待测";
        }
        long enabled = cases.stream().filter(c -> Boolean.TRUE.equals(c.get("enabled"))).count();
        if (enabled == cases.size()) {
            return "已启用";
        }
        if (enabled > 0) {
            return "进行中";
        }
        return "待测";
    }

    private Map<String, Object> buildLiveTree(boolean includeArchived) {
        Map<String, List<Map<String, Object>>> projectsByCategory = new LinkedHashMap<>();
        Map<String, String> categoryOverrides = projectCategoryOverrides();

        Set<String> projectCodes = new LinkedHashSet<>();
        for (ApiTestCollection c : mapper.findAll(null, ApiTestCollection.SCOPE_LIVE, null, includeArchived,
                null, null)) {
            if (c.getProject() != null && !c.getProject().isBlank()) {
                projectCodes.add(ApiAppCatalog.treeProjectKey(c.getProject()));
            }
        }

        for (String code : projectCodes) {
            List<Map<String, Object>> cases = liveCasesOfProject(code, includeArchived);
            if (cases.isEmpty()) {
                continue;
            }
            Map<String, List<Map<String, Object>>> byController = new LinkedHashMap<>();
            for (Map<String, Object> row : cases) {
                String ctrl = String.valueOf(row.getOrDefault("controller", "UnknownController"));
                byController.computeIfAbsent(ctrl, k -> new ArrayList<>()).add(row);
            }

            Map<String, int[]> coreByController = coreCoverageByController(code);
            Map<String, List<Map<String, Object>>> changesByEndpoint = endpointService.branchesOfEndpoints(
                    cases.stream().map(r -> treeStr(r.get("endpointId"))).collect(java.util.stream.Collectors.toSet()));
            String baseBranch = projectBaseBranches().get(treeProjectCode(code));
            List<Map<String, Object>> controllers = new ArrayList<>();
            for (Map.Entry<String, List<Map<String, Object>>> ce : byController.entrySet()) {
                Map<String, Object> ctrlNode = new LinkedHashMap<>();
                ctrlNode.put("key", "ctrl:" + code + ":" + ce.getKey());
                ctrlNode.put("kind", "controller");
                ctrlNode.put("label", ce.getKey());
                ctrlNode.put("handler", ce.getKey());
                ctrlNode.put("caseCount", ce.getValue().size());
                int[] core = coreByController.getOrDefault(ce.getKey(), new int[2]);
                ctrlNode.put("coreTotal", core[0]);
                ctrlNode.put("coreCovered", core[1]);
                Set<String> words = new LinkedHashSet<>();
                for (Map<String, Object> row : ce.getValue()) {
                    if (row.get("path") != null) {
                        words.add(String.valueOf(row.get("path")));
                    }
                    if (row.get("name") != null) {
                        words.add(String.valueOf(row.get("name")));
                    }
                }
                ctrlNode.put("searchText", String.join("\n", words));
                ctrlNode.put("statusTag", "已上线");
                Set<String> changedIds = new LinkedHashSet<>();
                Set<String> changeBranches = new LinkedHashSet<>();
                for (Map<String, Object> row : ce.getValue()) {
                    String epId = treeStr(row.get("endpointId"));
                    for (Map<String, Object> ch : changesByEndpoint.getOrDefault(epId, List.of())) {
                        String br = treeStr(ch.get("branch"));
                        if (!br.isBlank() && !BaselineBranches.isBaseline(br, baseBranch)) {
                            changedIds.add(epId);
                            changeBranches.add(br);
                        }
                    }
                }
                if (!changedIds.isEmpty()) {
                    ctrlNode.put("changeCount", changedIds.size());
                    ctrlNode.put("changeBranches", new ArrayList<>(changeBranches));
                }
                // 执行报告页靠它建接口树，截断的话排在后面的接口永远看不到报告；同一接口多条用例只留一条
                List<Map<String, Object>> previews = new ArrayList<>();
                java.util.Set<String> seenIface = new java.util.HashSet<>();
                ce.getValue().stream()
                        .filter(row -> seenIface.add(row.get("method") + " " + row.get("path")))
                        .forEach(row -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("method", row.get("method"));
                    p.put("path", row.get("path"));
                    p.put("label", row.get("method") + " " + row.get("path"));
                    previews.add(p);
                });
                ctrlNode.put("previews", previews);
                controllers.add(ctrlNode);
            }
            controllers.sort((a, b) -> Integer.compare(
                    num(b.get("caseCount")), num(a.get("caseCount"))));

            Map<String, Object> proj = new LinkedHashMap<>();
            proj.put("code", code);
            proj.put("label", ApiProjectCatalog.projectDisplayLabel(code));
            proj.put("caseCount", cases.size());
            proj.put("runCollectionId", pickLiveRunCollectionId(code,
                    groupCollectionsByProject(mapper.findAll(code, ApiTestCollection.SCOPE_LIVE, null,
                            includeArchived, null, null)).get(code)));
            proj.put("controllers", controllers);
            projectsByCategory.computeIfAbsent(categoryOfProject(code, categoryOverrides), k -> new ArrayList<>())
                    .add(proj);
        }

        List<Map<String, Object>> categories = buildCategoryNodes(projectsByCategory);
        return Map.of(
                "treeMode", "LIVE",
                "scope", ApiTestCollection.SCOPE_LIVE,
                "appVersion", "",
                "categories", categories);
    }

    private List<Map<String, Object>> buildCategoryNodes(
            Map<String, List<Map<String, Object>>> projectsByCategory) {
        List<Map<String, Object>> categories = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>();
        for (Map<String, Object> def : projectService.listCategoryRegistry()) {
            String key = String.valueOf(def.get("key"));
            categories.add(categoryNode(key, String.valueOf(def.get("label")), projectsByCategory.get(key)));
            handled.add(key);
        }
        for (String key : projectsByCategory.keySet()) {
            if (handled.contains(key)) {
                continue;
            }
            categories.add(categoryNode(key, projectService.categoryLabel(key), projectsByCategory.get(key)));
        }
        return categories;
    }

    private static Map<String, Object> categoryNode(
            String key, String label, List<Map<String, Object>> projects) {
        if (projects == null || projects.isEmpty()) {
            projects = List.of();
        } else {
            projects = new ArrayList<>(projects);
            projects.sort((a, b) -> Integer.compare(
                    num(b.get("collectionCount") != null ? b.get("collectionCount") : b.get("caseCount")),
                    num(a.get("collectionCount") != null ? a.get("collectionCount") : a.get("caseCount"))));
        }
        int catTotal = projects.stream()
                .mapToInt(p -> num(p.get("collectionCount") != null ? p.get("collectionCount")
                        : p.get("caseCount")))
                .sum();
        Map<String, Object> catNode = new LinkedHashMap<>();
        catNode.put("key", key);
        catNode.put("label", label);
        catNode.put("total", catTotal);
        catNode.put("projects", projects);
        return catNode;
    }

    private static Map<String, Object> treeGroup(String kind, String project, String label,
                                                 List<Map<String, Object>> items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("key", kind + ":" + project);
        g.put("kind", kind);
        g.put("label", label);
        g.put("project", project);
        g.put("collectionCount", items.size());
        g.put("collections", items);
        return g;
    }

    private static String collectionStatusTag(ApiTestCollection c) {
        if (c.getPassRate() != null && c.getPassRate() >= 100) {
            return "已验证";
        }
        return "进行中";
    }

    /** Controller → [核心接口数, 其中有启用用例的数] */
    private Map<String, int[]> coreCoverageByController(String project) {
        Map<String, int[]> out = new HashMap<>();
        try {
            for (ApiEndpoint ep : endpointService.search(project, null, null, null)) {
                if (ep.isRetired() || "DEPRECATED".equals(ep.getStatus())
                        || (ep.getSourceBranch() != null && !ep.getSourceBranch().isBlank())
                        || ApiEndpointService.levelOf(ep) != EndpointLevel.CORE) {
                    continue;
                }
                int[] a = out.computeIfAbsent(controllerOfEndpoint(ep), k -> new int[2]);
                a[0]++;
                if (ep.getEnabledCaseCount() != null && ep.getEnabledCaseCount() > 0) {
                    a[1]++;
                }
            }
        } catch (Exception e) {
            log.debug("[collection] 核心接口统计跳过 {}: {}", project, e.getMessage());
        }
        return out;
    }

    private static String controllerOfCase(ApiTestCase c, ApiEndpoint ep) {
        String ctrl = controllerOfEndpoint(ep);
        if (!ctrl.isBlank()) {
            return ctrl;
        }
        String name = c.getName() == null ? "" : c.getName().trim();
        int idx = name.indexOf('（');
        if (idx > 0) {
            return name.substring(0, idx).trim();
        }
        return "UnknownController";
    }

    /** 扫描出来的 handler 有的只是方法名，Controller 名以源文件为准 */
    static String controllerOfEndpoint(com.mobile.standalone.entity.ApiEndpoint ep) {
        if (ep == null) {
            return "";
        }
        String file = ep.getSourceFile();
        if (file != null && !file.isBlank()) {
            String base = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
            return base.endsWith(".java") || base.endsWith(".kt") ? base.substring(0, base.lastIndexOf('.')) : base;
        }
        String h = ep.getHandler();
        return h == null || h.isBlank() ? "" : controllerName(h);
    }

    private static String controllerName(String handler) {
        if (handler == null || handler.isBlank()) {
            return "UnknownController";
        }
        String h = handler.trim();
        int dot = h.indexOf('.');
        return dot > 0 ? h.substring(0, dot) : h;
    }

    private Map<String, String> projectCategoryOverrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ApiProject p : projectMapper.findAll(null)) {
            if (p.getCategory() != null && !p.getCategory().isBlank()) {
                out.put(p.getCode(), p.getCategory().trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private Map<String, String> projectBaseBranches() {
        Map<String, String> out = new HashMap<>();
        try {
            for (ApiProject p : projectMapper.findAll(null)) {
                if (p.getBaseBranch() != null && !p.getBaseBranch().isBlank()) {
                    out.put(treeProjectCode(p.getCode()), p.getBaseBranch().trim());
                }
            }
        } catch (Exception e) {
            log.debug("[collection] 取项目基线分支失败: {}", e.getMessage());
        }
        return out;
    }

    private static String categoryOfProject(String code, Map<String, String> overrides) {
        return ApiProjectCatalog.resolveCategoryKey(code, overrides.get(code));
    }

    private static int num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, List<Map<String, Object>>> groupCollectionsByProject(List<ApiTestCollection> all) {
        Map<String, List<Map<String, Object>>> collectionsByProject = new LinkedHashMap<>();
        for (ApiTestCollection c : all) {
            String key = ApiAppCatalog.treeProjectKey(c.getProject());
            collectionsByProject.computeIfAbsent(key, k -> new ArrayList<>()).add(collectionVo(c));
        }
        return collectionsByProject;
    }

    /** 执行 master 回归时用的集合 id（优先名与项目展示名一致的 LIVE 集合） */
    private String pickLiveRunCollectionId(String projectCode, List<Map<String, Object>> collections) {
        if (collections == null || collections.isEmpty()) {
            return null;
        }
        String label = ApiProjectCatalog.projectDisplayLabel(projectCode);
        for (Map<String, Object> col : collections) {
            if (label.equals(String.valueOf(col.get("name")))) {
                return String.valueOf(col.get("id"));
            }
        }
        return String.valueOf(collections.get(0).get("id"));
    }

    /** 生产视图：项目下全部 LIVE 用例（跨集合合并，树里项目即接口列表） */
    public List<Map<String, Object>> liveCasesOfProject(String project, boolean includeArchived) {
        if (project == null || project.isBlank()) {
            return List.of();
        }
        String code = ApiAppCatalog.treeProjectKey(project.trim());
        List<ApiTestCollection> cols = mapper.findAll(code, ApiTestCollection.SCOPE_LIVE, null, includeArchived,
                null, null);
        Set<String> seen = new LinkedHashSet<>();
        List<ApiTestCase> merged = new ArrayList<>();
        for (ApiTestCollection col : cols) {
            for (ApiTestCase c : caseMapper.findByFilters(null, col.getId(), null, false, null, null, null, null)) {
                if (seen.add(c.getId())) {
                    merged.add(c);
                }
            }
        }
        return caseRows(merged);
    }

    /**
     * 生产视图右侧：某 Controller 下按接口分组、分页。
     * 每个接口带上它（筛选后）的用例，前端点开接口才渲染。
     */
    public Map<String, Object> liveInterfacePage(String project, boolean includeArchived, String controller,
                                                 String keyword, String kind, int page, int size) {
        List<Map<String, Object>> rows = filterLiveRows(liveCasesOfProject(project, includeArchived),
                controller, keyword, kind);
        List<Map<String, Object>> groups = groupByInterface(rows);
        int safeSize = Math.max(1, Math.min(size, 200));
        int total = groups.size();
        int pages = Math.max(1, (total + safeSize - 1) / safeSize);
        int safePage = Math.max(1, Math.min(page, pages));
        int from = (safePage - 1) * safeSize;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("caseTotal", rows.size());
        out.put("page", safePage);
        out.put("size", safeSize);
        List<Map<String, Object>> items = groups.subList(Math.min(from, total), Math.min(from + safeSize, total));
        attachBranchChanges(items);
        out.put("items", items);
        return out;
    }

    /** 全量里的接口被哪些在测分支改了：页面上打标记，悬停看是哪个分支、改了什么 */
    private void attachBranchChanges(List<Map<String, Object>> items) {
        List<String> ids = items.stream().map(g -> treeStr(g.get("endpointId"))).filter(s -> !s.isBlank()).toList();
        Map<String, List<Map<String, Object>>> byEndpoint = endpointService.branchesOfEndpoints(ids);
        Map<String, String> baseBranches = projectBaseBranches();
        for (Map<String, Object> g : items) {
            List<Map<String, Object>> changes = byEndpoint.get(treeStr(g.get("endpointId")));
            if (changes == null || changes.isEmpty()) {
                continue;
            }
            String code = treeProjectCode(g.get("project"));
            List<Map<String, Object>> own = changes.stream()
                    .filter(c -> !BaselineBranches.isBaseline(treeStr(c.get("branch")), baseBranches.get(code)))
                    .toList();
            if (!own.isEmpty()) {
                g.put("branchChanges", own);
            }
        }
    }

    static List<Map<String, Object>> filterLiveRows(List<Map<String, Object>> rows, String controller,
                                                    String keyword, String kind) {
        String ctrl = controller == null ? "" : controller.trim();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        ApiCaseKind k = kind == null || kind.isBlank() ? null : ApiCaseKind.of(kind);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!ctrl.isEmpty() && !ctrl.equals(String.valueOf(r.get("controller")))) {
                continue;
            }
            if (k != null && ApiCaseKind.of(r.get("caseKind") == null ? null : String.valueOf(r.get("caseKind"))) != k) {
                continue;
            }
            if (!kw.isEmpty() && !(lower(r.get("path")).contains(kw) || lower(r.get("name")).contains(kw)
                    || lower(r.get("controller")).contains(kw))) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    /** 核心用例在前、仅测试其次；接口按其首条用例的顺序排，核心接口整体置顶 */
    static List<Map<String, Object>> groupByInterface(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(java.util.Comparator.comparingInt(ApiTestCollectionService::casePriority));
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : sorted) {
            String method = r.get("method") == null ? "GET" : String.valueOf(r.get("method")).toUpperCase(Locale.ROOT);
            String path = r.get("path") == null ? "" : String.valueOf(r.get("path"));
            Map<String, Object> g = groups.computeIfAbsent(method + " " + path, key -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("method", method);
                m.put("path", path);
                m.put("project", r.get("project"));
                m.put("endpointId", null);
                m.put("title", interfaceTitle(r));
                m.put("level", null);
                m.put("cases", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) g.get("cases");
            cases.add(r);
            if (g.get("endpointId") == null && r.get("endpointId") != null) {
                g.put("endpointId", r.get("endpointId"));
            }
            if ("CORE".equals(r.get("level"))) {
                g.put("level", "CORE");
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(groups.values());
        out.sort(java.util.Comparator.comparingInt(g -> "CORE".equals(g.get("level")) ? 0 : 1));
        return out;
    }

    private static int casePriority(Map<String, Object> r) {
        if ("CORE".equals(r.get("level"))) {
            return 0;
        }
        return Boolean.TRUE.equals(r.get("testOnly")) ? 1 : 2;
    }

    /** 用例名约定是「接口名 · 场景」 */
    private static String interfaceTitle(Map<String, Object> r) {
        String name = r.get("name") == null ? "" : String.valueOf(r.get("name"));
        int i = name.indexOf(" · ");
        if (i > 0) {
            return name.substring(0, i);
        }
        return !name.isEmpty() ? name : (r.get("path") == null ? "—" : String.valueOf(r.get("path")));
    }

    private static String lower(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    /** 生成用例级联：APP 全部项目 → 其下测试集合（与用例集合 · 测试环境一致） */
    public Map<String, Object> pickableTree(String requirementNo, String appVersion) {
        List<ApiTestCollection> all = mapper.findAll(null, null, null, false, null, null);
        Map<String, List<ApiTestCollection>> byProject = new LinkedHashMap<>();
        for (ApiTestCollection c : all) {
            if (!isPickableTestCollection(c)) {
                continue;
            }
            String key = ApiAppCatalog.treeProjectKey(c.getProject());
            byProject.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        List<Map<String, Object>> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String slot : ApiProjectCatalog.appProjectSlots()) {
            if (seen.add(slot)) {
                options.add(projectPickableNode(slot, byProject.get(slot), requirementNo, appVersion));
            }
        }
        for (Map.Entry<String, List<ApiTestCollection>> e : byProject.entrySet()) {
            if (seen.add(e.getKey())) {
                options.add(projectPickableNode(e.getKey(), e.getValue(), requirementNo, appVersion));
            }
        }
        options.sort((a, b) -> rankProjectNode(a, b, requirementNo, appVersion));
        return Map.of("options", options);
    }

    private Map<String, Object> projectPickableNode(String projectCode, List<ApiTestCollection> cols,
                                                  String requirementNo, String appVersion) {
        List<ApiTestCollection> sorted = cols == null ? new ArrayList<>() : new ArrayList<>(cols);
        sorted.sort((a, b) -> comparePickable(a, b, requirementNo, appVersion));
        List<Map<String, Object>> children = new ArrayList<>();
        for (ApiTestCollection c : sorted) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("value", c.getId());
            leaf.put("label", formatPickableLabel(c));
            children.add(leaf);
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("value", projectCode);
        node.put("label", ApiProjectCatalog.projectDisplayLabel(projectCode));
        node.put("children", children);
        node.put("disabled", children.isEmpty());
        return node;
    }

    private static String formatPickableLabel(ApiTestCollection c) {
        String name = c.getName() == null ? "" : c.getName();
        int n = c.getCaseCount() == null ? 0 : c.getCaseCount();
        if (c.getRequirementNo() != null && !c.getRequirementNo().isBlank()
                && !name.toUpperCase(Locale.ROOT).contains(c.getRequirementNo().trim().toUpperCase(Locale.ROOT))) {
            return name + " · " + c.getRequirementNo().trim() + "（" + n + " 条）";
        }
        return name + "（" + n + " 条）";
    }

    private static int rankProjectNode(Map<String, Object> a, Map<String, Object> b,
                                       String requirementNo, String appVersion) {
        int ra = projectNodeRank(a, requirementNo, appVersion);
        int rb = projectNodeRank(b, requirementNo, appVersion);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return String.valueOf(a.get("label")).compareToIgnoreCase(String.valueOf(b.get("label")));
    }

    @SuppressWarnings("unchecked")
    private static int projectNodeRank(Map<String, Object> node, String requirementNo, String appVersion) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        if (children == null || children.isEmpty()) {
            return 8;
        }
        int best = 9;
        for (Map<String, Object> ch : children) {
            String label = String.valueOf(ch.get("label"));
            if (requirementNo != null && !requirementNo.isBlank()
                    && label.toUpperCase(Locale.ROOT).contains(requirementNo.trim().toUpperCase(Locale.ROOT))) {
                best = Math.min(best, 0);
            } else if (appVersion != null && !appVersion.isBlank() && label.contains(appVersion)) {
                best = Math.min(best, 1);
            } else {
                best = Math.min(best, 2);
            }
        }
        return best;
    }

    /** 测试集合：scope=TEST，或留空但有单号/版本/分支标记（排除 LIVE） */
    static boolean isPickableTestCollection(ApiTestCollection c) {
        if (c == null || c.getProject() == null || c.getProject().isBlank()) {
            return false;
        }
        if (ApiTestCollection.LIFECYCLE_ARCHIVED.equals(c.getLifecycle())) {
            return false;
        }
        String scope = c.getScope();
        if (ApiTestCollection.SCOPE_LIVE.equals(scope)) {
            return false;
        }
        if (ApiTestCollection.SCOPE_TEST.equals(scope)) {
            return true;
        }
        if (scope == null || scope.isBlank()) {
            return (c.getRequirementNo() != null && !c.getRequirementNo().isBlank())
                    || (c.getAppVersion() != null && !c.getAppVersion().isBlank())
                    || (c.getSourceBranch() != null && !c.getSourceBranch().isBlank());
        }
        return false;
    }

    /** 生成用例下拉：同项目测试集合（兼容历史 project 编码，不按版本硬筛） */
    public List<Map<String, Object>> listPickable(String project, String requirementNo, String appVersion) {
        if (project == null || project.isBlank()) {
            return List.of();
        }
        String canon = ApiAppCatalog.treeProjectKey(project.trim());
        List<ApiTestCollection> all = mapper.findAll(null, null, null, false, null, null);
        List<ApiTestCollection> matched = new ArrayList<>();
        for (ApiTestCollection c : all) {
            if (!isPickableTestCollection(c)) {
                continue;
            }
            if (!sameProject(c.getProject(), canon)) {
                continue;
            }
            matched.add(c);
        }
        matched.sort((a, b) -> comparePickable(a, b, requirementNo, appVersion));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiTestCollection c : matched) {
            out.add(collectionVo(c));
        }
        return out;
    }

    private static boolean sameProject(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return ApiAppCatalog.treeProjectKey(a).equalsIgnoreCase(ApiAppCatalog.treeProjectKey(b));
    }

    private static int comparePickable(ApiTestCollection a, ApiTestCollection b,
                                       String requirementNo, String appVersion) {
        int ra = rankPickable(a, requirementNo, appVersion);
        int rb = rankPickable(b, requirementNo, appVersion);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return a.getName().compareToIgnoreCase(b.getName());
    }

    /** 0=最匹配（同单号），其次同版本，其余靠后 */
    private static int rankPickable(ApiTestCollection c, String requirementNo, String appVersion) {
        if (requirementNo != null && !requirementNo.isBlank()
                && requirementNo.trim().equalsIgnoreCase(c.getRequirementNo())) {
            return 0;
        }
        if (appVersion != null && !appVersion.isBlank()
                && appVersion.trim().equals(c.getAppVersion())) {
            return 1;
        }
        if (c.getAppVersion() == null || c.getAppVersion().isBlank()) {
            return 2;
        }
        return 3;
    }

    /** master 回归：每个项目一个默认 LIVE 集合，名如 APP-SECKILL */
    @Transactional
    public ApiTestCollection ensureDefaultLiveCollection(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("项目编码必填");
        }
        String code = ApiAppCatalog.treeProjectKey(project.trim());
        String name = ApiProjectCatalog.projectDisplayLabel(code);
        ApiTestCollection hit = mapper.findByName(code, name);
        if (hit != null && isLiveCollection(hit)) {
            return hit;
        }
        ApiTestCollection col = new ApiTestCollection();
        col.setProject(code);
        col.setName(name);
        col.setDescription("master 回归集合");
        col.setScope(ApiTestCollection.SCOPE_LIVE);
        col.setLifecycle(ApiTestCollection.LIFECYCLE_ACTIVE);
        col.setEnabled(true);
        col.setSortOrder(10);
        return create(col);
    }

    /**
     * 测试集合：有单号按单号找/建（APP-xxxx-接口），无单号按分支找/建。
     */
    @Transactional
    public ApiTestCollection findOrCreateTestCollection(String project, String requirementNo,
                                                        String appVersion, String sourceBranch) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("项目编码必填");
        }
        String code = ApiAppCatalog.treeProjectKey(project.trim());
        ApiTestCollection hit = findExistingTestCollection(code, requirementNo, appVersion, sourceBranch);
        if (hit != null) {
            return hit;
        }
        ApiTestCollection col = new ApiTestCollection();
        col.setProject(code);
        col.setScope(ApiTestCollection.SCOPE_TEST);
        col.setLifecycle(ApiTestCollection.LIFECYCLE_ACTIVE);
        col.setRequirementNo(requirementNo != null && !requirementNo.isBlank() ? requirementNo.trim() : null);
        col.setAppVersion(appVersion != null && !appVersion.isBlank() ? appVersion.trim() : null);
        col.setSourceBranch(sourceBranch != null && !sourceBranch.isBlank() ? sourceBranch.trim() : null);
        col.setName(buildTestCollectionName(requirementNo, sourceBranch));
        col.setDescription("测试迭代集合；本单/本分支测完上线后归档");
        col.setEnabled(true);
        col.setSortOrder(100);
        return create(col);
    }

    ApiTestCollection findExistingTestCollection(String project, String requirementNo,
                                                         String appVersion, String sourceBranch) {
        List<ApiTestCollection> rows = list(project, ApiTestCollection.SCOPE_TEST, false, appVersion);
        if (requirementNo != null && !requirementNo.isBlank()) {
            String req = requirementNo.trim();
            return rows.stream()
                    .filter(c -> req.equalsIgnoreCase(c.getRequirementNo()))
                    .findFirst()
                    .orElse(null);
        }
        if (sourceBranch != null && !sourceBranch.isBlank()) {
            String branch = sourceBranch.trim();
            return rows.stream()
                    .filter(c -> branch.equals(c.getSourceBranch()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    static String buildTestCollectionName(String requirementNo, String sourceBranch) {
        if (requirementNo != null && !requirementNo.isBlank()) {
            return requirementNo.trim() + "-接口";
        }
        if (sourceBranch != null && !sourceBranch.isBlank()) {
            return sourceBranch.trim() + " · 分支对比";
        }
        return "未归类";
    }

    private static boolean isLiveCollection(ApiTestCollection c) {
        return c.getScope() == null
                || c.getScope().isBlank()
                || ApiTestCollection.SCOPE_LIVE.equals(c.getScope());
    }

    private Map<String, Object> collectionVo(ApiTestCollection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("project", c.getProject());
        m.put("name", c.getName());
        m.put("description", c.getDescription());
        m.put("enabled", c.getEnabled());
        m.put("scope", c.getScope());
        m.put("lifecycle", c.getLifecycle());
        m.put("requirementNo", c.getRequirementNo());
        m.put("appVersion", c.getAppVersion());
        m.put("sourceBranch", c.getSourceBranch());
        m.put("caseCount", c.getCaseCount() == null ? 0 : c.getCaseCount());
        m.put("passRate", c.getPassRate());
        m.put("lastRunAt", c.getLastRunAt());
        m.put("lastRunId", c.getLastRunId());
        return m;
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
        defaults(body);
        body.setProject(ApiAppCatalog.treeProjectKey(body.getProject()));
        body.setId("atcol-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        body.setName(body.getName().trim());
        LocalDateTime now = LocalDateTime.now();
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        mapper.insert(body);
        return mapper.findById(body.getId());
    }

    private void defaults(ApiTestCollection body) {
        if (body.getEnabled() == null) {
            body.setEnabled(true);
        }
        if (body.getSortOrder() == null) {
            body.setSortOrder(100);
        }
        if (body.getLifecycle() == null || body.getLifecycle().isBlank()) {
            body.setLifecycle(ApiTestCollection.LIFECYCLE_ACTIVE);
        }
        if (body.getScope() == null || body.getScope().isBlank()) {
            body.setScope(body.getRequirementNo() != null && !body.getRequirementNo().isBlank()
                    ? ApiTestCollection.SCOPE_TEST
                    : ApiTestCollection.SCOPE_LIVE);
        }
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
        if (body.getScope() == null) {
            body.setScope(existing.getScope());
        }
        if (body.getLifecycle() == null) {
            body.setLifecycle(existing.getLifecycle());
        }
        if (body.getRequirementNo() == null) {
            body.setRequirementNo(existing.getRequirementNo());
        }
        if (body.getAppVersion() == null) {
            body.setAppVersion(existing.getAppVersion());
        }
        body.setUpdatedAt(LocalDateTime.now());
        mapper.update(body);
        return mapper.findById(id);
    }

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

    /** 本单已上线：归档测试集合 + 需求涉及接口 */
    /**
     * 本单已上线：一个动作收口整条链。
     * <p>
     * 原先只做前两件事，于是上线之后黑盒用例还停在 TESTING、需求登记还是进行中，
     * 人得再去两个页面各点一次——漏一处链路就断了。这里一并收掉。
     * <p>
     * 分支从集合上取：测试集合建的时候记了 sourceBranch，上线时把它固化到需求登记上，
     * 「这个需求走的哪个分支」从此有个唯一的落点。
     */
    @Transactional
    public Map<String, Object> releaseRequirement(String requirementNo) {
        if (requirementNo == null || requirementNo.isBlank()) {
            throw new IllegalArgumentException("需求单号必填");
        }
        String no = requirementNo.trim();
        String branch = branchOfRequirement(no);

        int cols = mapper.archiveByRequirement(no);
        int eps = requirementEndpointService.archiveByRequirement(no);
        int promoted = 0;
        if (branch != null && !branch.isBlank()) {
            promoted = endpointService.promoteBranchToMaster(branch);
        }
        int blackBox = aiTestCaseService.archiveByRequirement(no);
        boolean marked = requirementService.markShipped(no, branch);

        log.info("[api-test] 需求 {} 已标记上线：归档 {} 个集合、{} 条涉及接口、{} 条黑盒用例、并入 master {} 条；登记状态更新={} 分支={}",
                no, cols, eps, blackBox, promoted, marked, branch == null ? "(未记录)" : branch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requirementNo", no);
        out.put("collectionsArchived", cols);
        out.put("endpointsArchived", eps);
        out.put("blackBoxCasesArchived", blackBox);
        out.put("requirementMarked", marked);
        out.put("sourceBranch", branch);
        out.put("endpointsPromotedToMaster", promoted);
        return out;
    }

    /**
     * 分支已上线：老接口换成分支版本、新增接口进全量，分支下的用例挪进项目的全量集合并转成回归用例，
     * 分支集合归档。
     */
    @Transactional
    public Map<String, Object> releaseBranch(String project, String branch) {
        if (project == null || project.isBlank() || branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("项目和分支都必填");
        }
        String code = treeProjectCode(project);
        String br = branch.trim();
        ApiProject p = projectMapper.findByCode(project.trim());
        if (BaselineBranches.isBaseline(br, p == null ? null : p.getBaseBranch())) {
            throw new IllegalArgumentException("「" + br + "」是基线分支，没有上线这一步");
        }

        Set<String> rawProjects = new LinkedHashSet<>();
        rawProjects.add(project.trim());
        endpointService.projectsOfBranch(br).stream()
                .filter(raw -> code.equals(treeProjectCode(raw)))
                .forEach(rawProjects::add);
        int updated = 0;
        int added = 0;
        int deprecated = 0;
        for (String raw : rawProjects) {
            ApiEndpointService.PromoteResult r = endpointService.promoteBranchToMaster(raw, br);
            updated += r.updated();
            added += r.added();
            deprecated += r.deprecated();
        }
        ApiEndpointService.PromoteResult promoted = new ApiEndpointService.PromoteResult(updated, added, deprecated);

        LinkedHashSet<String> caseIds = new LinkedHashSet<>();
        List<ApiTestCollection> branchCols = mapper.findAll(null, ApiTestCollection.SCOPE_TEST, null, false, null, null)
                .stream()
                .filter(c -> br.equals(treeStr(c.getSourceBranch())) && code.equals(treeProjectCode(c.getProject())))
                .toList();
        for (ApiTestCollection c : branchCols) {
            caseIds.addAll(mapper.caseIdsOf(c.getId(), false));
        }
        for (String raw : rawProjects) {
            for (ApiTestCase c : caseMapper.findByProject(raw)) {
                if (br.equals(treeStr(c.getSourceBranch()))) {
                    caseIds.add(c.getId());
                }
            }
        }
        int moved = 0;
        if (!caseIds.isEmpty()) {
            ApiTestCollection live = ensureDefaultLiveCollection(project.trim());
            moved = caseMapper.promoteToRegression(live.getId(), new ArrayList<>(caseIds));
        }
        int archived = 0;
        for (ApiTestCollection c : branchCols) {
            archived += mapper.archiveById(c.getId());
        }
        log.info("[api-test] 分支 {}（{}）已上线：接口更新 {}、新增 {}、废弃 {}；用例进全量 {}；归档集合 {}",
                br, code, promoted.updated(), promoted.added(), promoted.deprecated(), moved, archived);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", code);
        out.put("branch", br);
        out.put("endpointsUpdated", promoted.updated());
        out.put("endpointsAdded", promoted.added());
        out.put("endpointsDeprecated", promoted.deprecated());
        out.put("casesMoved", moved);
        out.put("collectionsArchived", archived);
        return out;
    }

    /** 该需求的测试集合上记的分支；多个集合取第一个非空的 */
    private String branchOfRequirement(String requirementNo) {
        try {
            for (ApiTestCollection c : mapper.findAll(null, null, null, true, null, null)) {
                if (requirementNo.equalsIgnoreCase(c.getRequirementNo())
                        && c.getSourceBranch() != null && !c.getSourceBranch().isBlank()) {
                    return c.getSourceBranch().trim();
                }
            }
        } catch (Exception e) {
            log.debug("[api-test] 取需求分支失败 {}: {}", requirementNo, e.getMessage());
        }
        return null;
    }

    @Transactional
    public boolean archiveCollection(String id) {
        return mapper.archiveById(id) > 0;
    }

    @Transactional
    public Map<String, Integer> autoGroup(String project) {
        String code = ApiAppCatalog.treeProjectKey(project.trim());
        String name = ApiProjectCatalog.projectDisplayLabel(code);
        boolean existed = mapper.findByName(code, name) != null;
        ApiTestCollection col = ensureDefaultLiveCollection(project);
        List<ApiTestCase> all = caseMapper.findByProject(project);
        List<String> unassigned = new ArrayList<>();
        for (ApiTestCase c : all) {
            if (c.getCollectionId() == null || c.getCollectionId().isBlank()) {
                unassigned.add(c.getId());
            }
        }
        int assigned = assign(col.getId(), unassigned);
        log.info("[api-test] master 归组 project={} 集合 {}，归组 {} 条用例", project, col.getName(), assigned);
        return Map.of("created", existed ? 0 : 1, "assigned", assigned);
    }
}
