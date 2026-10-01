package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import com.mobile.standalone.service.capture.PacketCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测试环境抓包 → 接口清单 → 全量用例。
 * <p>
 * 不经过需求单：需求单只在接口对比分析时挂。这条路是「先把测试环境平时用到的接口
 * 和用例都建起来」，生成的用例一律 {@code testOnly}，线上回归不会跑到它们。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureCatalogService {

    /** 这里生成的用例打这个标记，去重只认它，人手写的同接口用例不算重复 */
    static final String CREATED_BY = "抓包";

    /** 认不出归属的接口先放这里，人在接口管理里归类；不建 api_projects 记录，免得混进项目管理 */
    public static final String INBOX = "capture-inbox";

    private final PacketCaptureService captureService;
    private final ApiEndpointService endpointService;
    private final ApiEndpointMapper endpointMapper;
    private final ApiProjectService projectService;
    private final ApiTestCaseService caseService;
    private final ApiTestCaseMapper caseMapper;
    private final ApiTestCollectionService collectionService;

    public record FlowRef(Integer seq, String method, String path, String query) {
    }

    /**
     * 收进接口清单，并存下这次的请求参数、请求头、请求体和响应样本。
     *
     * @param fallbackProject 路径和清单都认不出归属时才用它
     */
    public List<Map<String, Object>> save(String sessionId, String fallbackProject, List<FlowRef> flows) {
        String session = sessionId == null || sessionId.isBlank() ? "last" : sessionId;
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowRef f : flows) {
            if (f.path() == null || f.path().isBlank()) {
                continue;
            }
            String method = f.method() == null || f.method().isBlank() ? "GET" : f.method().toUpperCase();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", f.seq());
            row.put("method", method);
            row.put("path", f.path());
            String project = resolveProject(fallbackProject, method, f.path());
            if (project == null) {
                project = INBOX;
            } else {
                projectService.ensureExists(project, ApiProjectCatalog.projectDisplayLabel(project),
                        "抓包收录时自动补建", 100);
            }
            row.put("project", project);
            String id = endpointService.ensureEndpointFor(project, method, f.path(), null, null);
            if (id == null) {
                row.put("message", "收进清单失败");
                out.add(row);
                continue;
            }
            JsonNode detail = f.seq() == null ? null : captureService.body(session, f.seq());
            if (detail != null) {
                // 先存响应样本再存请求：前者会重写 capture_meta，后者是往里合并
                endpointService.applyCaptureDetail(id,
                        detail.path("reqBody").asText(null), detail.path("respBody").asText(null));
            }
            endpointService.applyCaptureRequest(project, method, f.path(), f.query(), headersOf(detail));
            ApiEndpoint e = endpointService.get(id);
            row.put("endpointId", id);
            EndpointLevel level = ApiEndpointService.levelOf(e);
            row.put("level", level == null ? null : level.name());
            row.put("caseCount", e == null || e.getCaseCount() == null ? 0 : e.getCaseCount());
            out.add(row);
        }
        return out;
    }

    /**
     * 人工归类：把接口挪到指定项目。
     * <p>
     * 目标项目已有同一个接口时合并进去（抓包数据以这次为准），再删掉这条——
     * 留两条的话同一个接口在清单里出现两次，用例也分散在两边。
     */
    @Transactional
    public Map<String, Object> classify(List<String> ids, String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("要归到哪个项目？");
        }
        String target = ApiAppCatalog.treeProjectKey(project.trim());
        if (INBOX.equals(target)) {
            throw new IllegalArgumentException("不能归到「待归类」");
        }
        if (projectService.get(target) == null) {
            throw new IllegalArgumentException("项目不存在：" + target);
        }
        int moved = 0;
        int merged = 0;
        for (String id : ids) {
            ApiEndpoint e = endpointMapper.findById(id);
            if (e == null || target.equals(e.getProject())) {
                continue;
            }
            ApiEndpoint same = endpointMapper.findByKey(target, e.getHttpMethod(), e.getPath());
            String keepId;
            if (same == null) {
                endpointMapper.updateProject(id, target);
                keepId = id;
                moved++;
            } else {
                Map<String, Object> meta = new LinkedHashMap<>(endpointService.captureMetaOf(same));
                meta.putAll(endpointService.captureMetaOf(e));
                if (!meta.isEmpty()) {
                    same.setCaptureMeta(EndpointAssertionContext.metaJson(meta));
                }
                if (same.getFieldsSnapshot() == null && e.getFieldsSnapshot() != null) {
                    same.setFieldsSnapshot(e.getFieldsSnapshot());
                }
                if ((same.getSummary() == null || same.getSummary().isBlank()) && e.getSummary() != null) {
                    same.setSummary(e.getSummary());
                }
                endpointMapper.update(same);
                keepId = same.getId();
                merged++;
            }
            for (ApiTestCase c : caseMapper.findByEndpointId(id)) {
                c.setEndpointId(keepId);
                c.setProject(target);
                caseMapper.update(c);
            }
            if (!keepId.equals(id)) {
                endpointMapper.deleteById(id);
            }
        }
        return Map.of("moved", moved, "merged", merged, "project", target);
    }

    /** 路径前缀 → 清单里唯一同名接口 → 页面上选的项目 */
    String resolveProject(String fallbackProject, String method, String path) {
        String byPath = RequirementEndpointProjectResolver.inferFromPath(path);
        if (byPath != null && !byPath.isBlank()) {
            return ApiAppCatalog.treeProjectKey(byPath);
        }
        try {
            Set<String> owners = endpointMapper.findByMethodPath(method, path).stream()
                    .map(ApiEndpoint::getProject)
                    .filter(p -> p != null && !p.isBlank() && !INBOX.equals(p))
                    .collect(Collectors.toSet());
            if (owners.size() == 1) {
                return owners.iterator().next();
            }
        } catch (Exception ex) {
            log.debug("[capture-catalog] 按路径查归属失败 {} {}: {}", method, path, ex.getMessage());
        }
        return fallbackProject == null || fallbackProject.isBlank()
                ? null : ApiAppCatalog.treeProjectKey(fallbackProject.trim());
    }

    private static Map<String, String> headersOf(JsonNode detail) {
        if (detail == null || !detail.has("reqHeaders")) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        detail.get("reqHeaders").fields()
                .forEachRemaining(x -> out.put(x.getKey(), x.getValue().asText("")));
        return out;
    }

    /**
     * 按抓包样本给接口生成用例，归到项目的全量集合。
     * <p>
     * 只建不跑；环境写 TEST、{@code testOnly=true}——抓包抓的是测试环境，
     * 拿这些用例去打生产就是拿没验过的请求碰线上数据。
     */
    public Map<String, Object> generateLiveCases(List<String> endpointIds) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, List<String>> byProject = new LinkedHashMap<>();
        int created = 0;
        int reused = 0;
        int failed = 0;
        for (String id : endpointIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("endpointId", id);
            try {
                ApiEndpoint e = endpointService.get(id);
                if (e == null) {
                    throw new IllegalArgumentException("接口清单里没有这条");
                }
                if (INBOX.equals(e.getProject())) {
                    throw new IllegalArgumentException("还没归类，先在接口管理里归到项目");
                }
                item.put("method", e.getHttpMethod());
                item.put("path", e.getPath());
                ApiTestCase existing = existingGenerated(id);
                ApiTestCase saved = existing != null ? existing : caseService.createCase(buildCase(e));
                byProject.computeIfAbsent(e.getProject(), k -> new ArrayList<>()).add(saved.getId());
                item.put("caseId", saved.getId());
                item.put("caseName", saved.getName());
                item.put("status", existing != null ? "REUSED" : "CREATED");
                if (existing != null) {
                    reused++;
                } else {
                    created++;
                }
            } catch (RuntimeException ex) {
                failed++;
                item.put("status", "FAILED");
                item.put("message", ex.getMessage() == null ? "生成失败" : ex.getMessage());
            }
            items.add(item);
        }
        List<Map<String, Object>> collections = new ArrayList<>();
        byProject.forEach((project, caseIds) -> {
            ApiTestCollection col = collectionService.ensureDefaultLiveCollection(project);
            collectionService.assign(col.getId(), caseIds);
            collections.add(Map.of("id", col.getId(), "name", col.getName(), "project", col.getProject()));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("reused", reused);
        out.put("failed", failed);
        out.put("collections", collections);
        out.put("items", items);
        return out;
    }

    private ApiTestCase buildCase(ApiEndpoint e) {
        var proj = projectService.get(e.getProject());
        var ctx = EndpointAssertionContext.of(e, endpointService);
        ApiCaseSkeleton.Draft draft = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                e.getHttpMethod(), e.getPath(), e.getSummary(),
                proj == null ? null : proj.getSuccessCodePath(),
                proj == null ? null : proj.getSuccessCodeValue(),
                ctx.responseFields(), ctx.captureSamples(), ctx.nullableFields()));
        ApiTestCase c = new ApiTestCase();
        c.setProject(e.getProject());
        c.setName(draft.name());
        String desc = "由测试环境抓包生成 · " + e.getHttpMethod() + " " + e.getPath();
        if (ctx.docReference() != null) {
            desc = desc + "\n" + ctx.docReference();
        }
        c.setDescription(desc);
        c.setMethod(e.getHttpMethod());
        c.setPath(e.getPath());
        c.setHeaders(RequirementCaseGenerator.headersJson(ctx.captureHeaders()));
        c.setQuery(RequirementCaseGenerator.toQueryString(ctx.captureQuery()));
        if (!"GET".equalsIgnoreCase(e.getHttpMethod())) {
            c.setBody(EndpointAssertionContext.reqBodyOf(endpointService.captureMetaOf(e)));
        }
        c.setAssertions(draft.assertions());
        c.setEnabled(draft.enabled());
        c.setEnvironment("TEST");
        c.setTestOnly(true);
        c.setEndpointId(e.getId());
        c.setCreatedBy(CREATED_BY);
        return c;
    }

    private ApiTestCase existingGenerated(String endpointId) {
        try {
            return caseMapper.findByEndpointId(endpointId).stream()
                    .filter(x -> CREATED_BY.equals(x.getCreatedBy()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("[capture-catalog] 查已有用例失败 {}: {}", endpointId, ex.getMessage());
            return null;
        }
    }
}
