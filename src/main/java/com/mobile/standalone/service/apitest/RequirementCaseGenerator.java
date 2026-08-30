package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.service.ApiProjectService;
import com.mobile.standalone.service.ApiTestCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从「需求单涉及的接口」直接生成用例，并归到集合里。
 * <p>
 * 抓包抓到的接口原来只是需求单下面的几行字：知道它被调了，但没有任何东西在验它。
 * 从这里一步到位——收进接口清单 → 生成用例骨架 → 归到集合，
 * 中间任何一步断了，「涉及接口 4 个 / 覆盖 0%」就永远是 0。
 * <p>
 * <b>默认一单一集合</b>：不让人每次从二十几个项目的树里翻。
 * 这不只是省事——「本单上线」是按需求单整批归档的
 * （{@link ApiTestCollectionService#releaseRequirement}），
 * 接口散在好几个集合里，那一步就归不干净，测完的东西会继续挂在测试视图里。
 * 想放到别的集合，显式传 {@code collectionId} 覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementCaseGenerator {

    private final ApiEndpointService endpointService;
    private final ApiProjectService projectService;
    private final ApiTestCaseService caseService;
    private final ApiTestCollectionService collectionService;
    private final RequirementEndpointService requirementEndpointService;
    private final com.mobile.standalone.mapper.ApiTestCaseMapper caseMapper;

    /** 这个生成器造出来的用例都打这个标记，去重时靠它认 */
    private static final String CREATED_BY = "涉及接口";

    /**
     * 这个接口下已经生成过的用例。
     * <p>
     * 只认<b>本生成器造的</b>：人手写的同路径用例不算重复——那是人有意写的第二条，
     * 拿它顶掉自动生成的，等于悄悄改了人的东西。
     */
    private ApiTestCase existingGenerated(String endpointId) {
        try {
            return caseMapper.findByEndpointId(endpointId).stream()
                    .filter(x -> CREATED_BY.equals(x.getCreatedBy()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // 查不了就当没有：宁可多生成一条，也不要因为查询失败让生成整个失败
            log.debug("[req-case] 查已有用例失败 {}: {}", endpointId, e.getMessage());
            return null;
        }
    }

    /**
     * @param collectionId 指定集合；留空按需求单自动找/建
     * @param appVersion   版本迭代标签，建集合时带上，如「9.3 海外客户端」
     * @param sourceBranch 分支对比来的填分支；和 requirementNo 二选一
     */
    public record Request(String requirementEndpointId, String collectionId,
                          String appVersion, String sourceBranch) {
    }

    /** @param variantCount 按文档约束额外造的边界用例条数 */
    public record Result(String caseId, String caseName, boolean caseEnabled, String note,
                         String collectionId, String collectionName, boolean collectionCreated,
                         int variantCount) {
    }

    @Transactional
    public Result generate(Request req) {
        var link = requirementEndpointService.get(req.requirementEndpointId());
        if (link == null) {
            throw new IllegalArgumentException("这条涉及接口不存在，可能已经被移除了");
        }
        String project = link.project();
        if (project == null || project.isBlank()) {
            // 清单按项目分，没有项目的接口生成了用例也查不出来、跑不起来
            throw new IllegalArgumentException(
                    "这条接口还没归项目。在抓包页选好项目重挂一次，或用「补上」重新添加");
        }
        project = ApiAppCatalog.treeProjectKey(project);
        projectService.ensureExists(
                project,
                ApiProjectCatalog.projectDisplayLabel(project),
                "由涉及接口生成用例时自动补建",
                100);

        // 1) 收进接口清单。挂的时候已经收过一次，这里是兜底：
        //    老数据（项目字段是后加的）走到这儿时 endpoint_id 还是空的
        String endpointId = link.endpointId();
        if (endpointId == null || endpointId.isBlank()) {
            endpointId = endpointService.ensureEndpointFor(
                    project, link.httpMethod(), link.path(), null, link.summary());
            if (endpointId == null) {
                throw new IllegalStateException("接口收进清单失败，请检查项目是否存在");
            }
            requirementEndpointService.bindEndpoint(link.id(), endpointId);
        }

        ApiEndpoint e = endpointService.get(endpointId);
        if (e == null) {
            throw new IllegalStateException("接口清单里找不到这条记录：" + endpointId);
        }

        // 2) 生成用例骨架（断言只认抓包实测 + 代码对比字段）
        var proj = projectService.get(project);
        var ctx = EndpointAssertionContext.of(e, endpointService);
        ApiCaseSkeleton.Draft draft = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                e.getHttpMethod(), e.getPath(), e.getSummary(),
                proj == null ? null : proj.getSuccessCodePath(),
                proj == null ? null : proj.getSuccessCodeValue(),
                ctx.responseFields(), ctx.captureSamples(), ctx.nullableFields()));

        ApiTestCase c = new ApiTestCase();
        c.setProject(project);
        c.setName(draft.name());
        String desc = "由抓包/涉及接口生成 · " + link.requirementNo()
                + " · " + e.getHttpMethod() + " " + e.getPath();
        if (ctx.docReference() != null) {
            desc = desc + "\n" + ctx.docReference();
        }
        c.setDescription(desc);
        c.setRequirementRef(link.requirementNo());
        c.setMethod(e.getHttpMethod());
        c.setPath(e.getPath());
        // 用抓包的真实请求，而不是一个只有 Accept 的空壳。
        // 网关要 appId、platform、appversion，缺一个就直接回「Service is busy」——
        // 而人看到的是一条红用例，会以为是接口坏了或者断言写错了
        c.setHeaders(headersJson(ctx.captureHeaders()));
        c.setQuery(toQueryString(ctx.captureQuery()));
        c.setAssertions(draft.assertions());
        c.setEnabled(draft.enabled());
        // 抓包是在测试环境抓的，生成的用例也只该在测试环境跑。
        // 落成 ANY 的话它会跟着线上回归一起跑，那是拿没验过的用例去打生产
        c.setEnvironment("TEST");
        c.setEndpointId(endpointId);
        c.setCreatedBy(CREATED_BY);

        // 已经生成过就复用，不再插一条。
        // 点两次「生成用例」得到两条一模一样的用例，是纯粹的垃圾：
        // 跑起来重复、覆盖率虚高，而人根本分不出该编辑哪一条
        ApiTestCase existing = existingGenerated(endpointId);
        boolean caseReused = existing != null;
        ApiTestCase saved = caseReused ? existing : caseService.createCase(c);

        // 3) 归集合
        boolean created = false;
        ApiTestCollection col = null;
        if (req.collectionId() != null && !req.collectionId().isBlank()) {
            col = collectionService.get(req.collectionId());
            if (col == null) {
                throw new IllegalArgumentException("集合不存在：" + req.collectionId());
            }
        } else {
            // 归一化后再查：findOrCreateTestCollection 内部用的是 treeProjectKey，
            // 这里不归一化的话，两边查的是不同的项目码——
            // 结果是明明复用了已有集合，却告诉人「新建了一个」
            String key = ApiAppCatalog.treeProjectKey(project);
            created = collectionService.findExistingTestCollection(
                    key, link.requirementNo(), req.appVersion(), req.sourceBranch()) == null;
            col = collectionService.findOrCreateTestCollection(
                    project, link.requirementNo(), req.appVersion(), req.sourceBranch());
        }
        // 4) 文档驱动的边界用例
        List<String> variantIds = designVariants(ctx, e, project, endpointId, link, saved.getName());

        List<String> all = new ArrayList<>();
        all.add(saved.getId());
        all.addAll(variantIds);
        collectionService.assign(col.getId(), all);

        log.info("[req-case] {} 生成用例 {} 并归入集合 {}（{}）",
                link.requirementNo(), saved.getId(), col.getName(), created ? "新建" : "已有");
        // 「只生成了一条」是最容易被当成故障的现象。说清楚是因为没文档，
        // 人才知道下一步该干什么，而不是以为功能坏了
        String note = caseReused
                ? "这个接口已经生成过用例了，直接用的那一条（没有重复生成）"
                : draft.note();
        if (variantIds.isEmpty() && ctx.docParams().isEmpty()) {
            note = note + "\n只有 1 条正常用例：这个接口还没有接口文档。"
                    + "抓包只能告诉你「这次是怎么调的」，造不出边界——"
                    + "传一份 .docx 进来，就能自动生成「不传必填」「非法值回落」「越界」这些用例。";
        }
        return new Result(saved.getId(), saved.getName(), Boolean.TRUE.equals(saved.getEnabled()),
                note, col.getId(), col.getName(), created, variantIds.size());
    }

    /**
     * 按文档里的参数约束造边界用例。
     * <p>
     * 没导文档就一条都不造——那时唯一的信息来源是抓包，
     * 而抓包只能告诉你「这次是怎么调的」，造不出边界。
     * <p>
     * 造出来的一律<b>停用</b>：期望结果各接口不一样（有的 PARAM_ERROR、有的回空数组），
     * 平台猜一个填进去，第一次跑就是红的——红得没有道理最消耗信任。
     * 文档原话贴在说明里，人扫一眼补上断言就行。
     */
    private List<String> designVariants(EndpointAssertionContext.Sources ctx, ApiEndpoint e,
                                        String project, String endpointId,
                                        RequirementEndpointService.LinkRow link, String baseName) {
        List<String> ids = new ArrayList<>();
        var variants = DocCaseDesigner.design(ctx.docParams(), ctx.captureQuery());
        if (variants.isEmpty()) {
            return ids;
        }
        for (var v : variants) {
            String name = baseName + " · " + v.name();
            if (existsByName(endpointId, name)) {
                continue;
            }
            ApiTestCase c = new ApiTestCase();
            c.setProject(project);
            c.setName(name);
            c.setDescription("按接口文档的参数约束生成 · " + link.requirementNo()
                    + "\n预期：" + v.expectNote()
                    + "\n（断言要你补——期望结果各接口不一样，平台猜一个只会白红一次）");
            c.setRequirementRef(link.requirementNo());
            c.setMethod(e.getHttpMethod());
            c.setPath(e.getPath());
            c.setQuery(toQueryString(v.query()));
            c.setHeaders(headersJson(ctx.captureHeaders()));
            // 只断状态码：具体该断什么由人按上面的「预期」补
            c.setAssertions("[{\"type\":\"status\",\"equals\":200}]");
            c.setEnabled(false);
            c.setEnvironment("TEST");
            c.setEndpointId(endpointId);
            c.setCreatedBy(CREATED_BY);
            try {
                ids.add(caseService.createCase(c).getId());
            } catch (RuntimeException ex) {
                log.debug("[req-case] 边界用例创建失败 {}: {}", name, ex.getMessage());
            }
        }
        return ids;
    }

    private boolean existsByName(String endpointId, String name) {
        try {
            return caseMapper.findByEndpointId(endpointId).stream()
                    .anyMatch(x -> name.equals(x.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 请求头 JSON。
     * <p>
     * <b>不写 token</b>：抓包时的 token 会过期，写死进用例的话，过期那天所有用例
     * 集体变红，而红的原因和接口毫无关系。token 归环境
     * （{@code api_test_envs.default_token}），执行时自动注入，一处改、全部生效。
     */
    static String headersJson(java.util.Map<String, String> headers) {
        java.util.Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        if (headers != null) {
            h.putAll(headers);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(h);
        } catch (Exception e) {
            return "{\"Accept\":\"application/json\"}";
        }
    }

    /**
     * query 存成 <b>JSON 对象</b>，不是 {@code a=1&b=2}。
     * <p>
     * 这一列的既定格式就是 JSON——写成查询串的话，执行时会被当 JSON 解析，
     * 报出来的是「Unrecognized token 'steamId'」，
     * 看的人完全猜不到是格式错了，只会以为接口坏了。
     */
    public static String toQueryString(java.util.Map<String, String> q) {
        if (q == null || q.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(q);
        } catch (Exception e) {
            return null;
        }
    }

    /** 给前端选集合用：同项目下还在用的测试集合 */
    public List<Map<String, Object>> pickableCollections(String project, String requirementNo, String appVersion) {
        return collectionService.listPickable(project, requirementNo, appVersion);
    }
}
