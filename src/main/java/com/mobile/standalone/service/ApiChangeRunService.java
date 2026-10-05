package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import com.mobile.standalone.service.apichange.ConcurrencyRiskScanner;
import com.mobile.standalone.service.apichange.EndpointDependencyResolver;
import com.mobile.standalone.service.apitest.BranchIssueKey;
import com.mobile.standalone.service.apitest.EndpointChangeFlag;
import com.mobile.standalone.service.apitest.RequirementEndpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个项目跑一次接口对比的完整流水线：拉代码 → 比对 → 挂覆盖 / 表结构 / 并发风险 / 必测清单 → 落库 → 进接口清单 → 挂需求单。
 * <p>
 * 「接口对比分析」页和按版本批量分析共用这一条，两边各写一遍迟早漂。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiChangeRunService {

    private final ApiChangeAnalyzer analyzer;
    private final ApiProjectMapper apiProjectMapper;
    private final com.mobile.standalone.mapper.ApiTestCaseMapper apiTestCaseMapper;
    private final com.mobile.standalone.mapper.ApiTableSchemaMapper apiTableSchemaMapper;
    private final ApiChangeReportService apiChangeReportService;
    private final com.mobile.standalone.service.apitest.ApiEndpointService apiEndpointService;
    private final RequirementEndpointService requirementEndpointService;
    private final ProjectRepoSync repoSync;
    private final com.mobile.standalone.service.apitest.DbConnectionService dbConnections;
    private final com.mobile.standalone.service.apitest.DbAssertRunner dbAssert;

    /** 选项目时拉过一次，紧接着点分析不用再拉 */
    public static final long SYNC_FRESH_MS = 120_000L;

    /**
     * 按分支比。{@code extraRequirementKeys}：分支名里没带单号（agent/2026-09-24/rating_switch）时，
     * 由调用方告诉这次改动属于哪个单。
     *
     * @throws IllegalArgumentException 项目 / 分支不对；拉代码失败时把原因拼在后面
     */
    public ApiChangeAnalyzer.Report analyzeGit(String projectCode, String baseRef, String headRef,
                                               List<String> extraRequirementKeys) {
        return analyzeGit(projectCode, baseRef, headRef, extraRequirementKeys, false);
    }

    /**
     * @param forBatch true = 接口对比分析2：开深度分析，报告标成 BATCH，不进接口对比分析页的历史列表
     */
    public ApiChangeAnalyzer.Report analyzeGit(String projectCode, String baseRef, String headRef,
                                               List<String> extraRequirementKeys, boolean forBatch) {
        ApiProject p = requireProject(projectCode);
        String base = baseRef != null && !baseRef.isBlank() ? baseRef : p.getBaseBranch();
        Map<String, Object> pulled = repoSync.syncQuietly(p.getCode(), SYNC_FRESH_MS);
        p = apiProjectMapper.findByCode(p.getCode());
        try {
            String head = headRef == null || headRef.isBlank()
                    ? analyzer.currentBranchOf(p.getRepoPath()) : headRef.trim();
            String baseShown = base == null || base.isBlank() ? "master" : base.trim();
            ApiChangeAnalyzer.Report report = analyzer.analyze(
                    p.getName() == null ? p.getCode() : p.getName(),
                    p.getRepoPath(),
                    analyzer.preferRemote(p.getRepoPath(), baseShown),
                    analyzer.preferRemote(p.getRepoPath(), head),
                    forBatch);
            // 实际比的是 origin/xxx，展示和后续挂清单、挂需求仍用人选的分支名，否则同一分支会出现两份
            report.setBaseRef(baseShown);
            report.setHeadRef(head);
            report.setRepoSync(pulled);
            finish(report, p.getCode(), extraRequirementKeys, forBatch);
            return report;
        } catch (IllegalArgumentException e) {
            String msg = String.valueOf(e.getMessage());
            if (Boolean.FALSE.equals(pulled.get("ok"))) {
                msg = msg + "（" + pulled.get("message") + "）";
            }
            throw new IllegalArgumentException(msg, e);
        }
    }

    /** 目录对目录：拿不到内网、只能把两个分支各拷一份到本地时用 */
    public ApiChangeAnalyzer.Report analyzeDirs(String projectCode, String baseDir, String headDir,
                                                String headLabel, List<String> extraRequirementKeys) {
        return analyzeDirs(projectCode, baseDir, headDir, headLabel, extraRequirementKeys, false);
    }

    /** @param forBatch 见 {@link #analyzeGit(String, String, String, List, boolean)} */
    public ApiChangeAnalyzer.Report analyzeDirs(String projectCode, String baseDir, String headDir,
                                                String headLabel, List<String> extraRequirementKeys, boolean forBatch) {
        return analyzeDirs(projectCode, baseDir, headDir, null, headLabel, extraRequirementKeys, forBatch);
    }

    /** @param baseLabel 基线目录是哪个分支的代码，只用来显示 */
    public ApiChangeAnalyzer.Report analyzeDirs(String projectCode, String baseDir, String headDir, String baseLabel,
                                                String headLabel, List<String> extraRequirementKeys, boolean forBatch) {
        requireProject(projectCode);
        ApiChangeAnalyzer.Report report = analyzer.analyzeDirs(projectCode, baseDir, headDir, forBatch);
        if (baseLabel != null && !baseLabel.isBlank()) {
            report.setBaseRef(baseLabel.trim());
        }
        // 目录对比没有分支名。用分支名或目录标识当来源，比留空强——
        // 留空的话这批接口在测试树上挂不到任何一个节点下面，等于看不见
        if (headLabel != null && !headLabel.isBlank()) {
            report.setHeadRef(headLabel.trim());
        } else if (report.getHeadRef() == null || report.getHeadRef().isBlank()) {
            report.setHeadRef("目录:" + projectCode);
        }
        finish(report, projectCode, extraRequirementKeys, forBatch);
        return report;
    }

    private ApiProject requireProject(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            throw new IllegalArgumentException("请选择项目");
        }
        ApiProject p = apiProjectMapper.findByCode(projectCode.trim());
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + projectCode);
        }
        return p;
    }

    private void finish(ApiChangeAnalyzer.Report report, String projectCode, List<String> extraKeys, boolean forBatch) {
        markCoverage(report, projectCode);
        attachTableSchemas(report, projectCode);
        attachConcurrencyRisks(report);
        report.setMustTest(com.mobile.standalone.service.apichange.MustTestGuide.of(report));
        apiChangeReportService.saveSnapshot(projectCode, report, forBatch ? ApiChangeReportService.SOURCE_BATCH : null);
        Map<String, Object> sync = syncToEndpointCatalog(report, projectCode);
        if (sync != null) {
            report.setCatalogSync(sync);
        }
        Map<String, Object> reqLink = linkToRequirement(report, projectCode, extraKeys);
        if (reqLink != null) {
            report.setRequirementLink(reqLink);
        }
    }

    /**
     * 把这份报告同步进接口清单。
     * <p>
     * 跟着分析自动做，不做成一个要人记得点的按钮：你的流程是「拉分支 → 录入接口清单」，
     * 中间多一次点击，迟早会有一版忘了点，而忘了的表现是清单看着干净、实际是上一版的。
     * <p>
     * 同步失败不影响报告本身——分析结果是主产物，清单是派生的。
     */
    private Map<String, Object> syncToEndpointCatalog(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null) {
            return null;
        }
        if (projectCode == null || projectCode.isBlank()) {
            return Map.of("skipped", true,
                    "reason", "这次没选项目，接口没有进接口清单。"
                            + "接口清单是按项目分的，选一个项目再分析一次就会自动录入");
        }
        ApiProject proj = apiProjectMapper.findByCode(projectCode);
        if (com.mobile.standalone.service.apitest.BaselineBranches.isBaseline(
                report.getHeadRef(), proj == null ? null : proj.getBaseBranch())) {
            return Map.of("skipped", true,
                    "reason", "「" + report.getHeadRef() + "」是基线 / 测试环境分支，不是需求分支，"
                            + "这次的改动没有挂到分支用例上。要录入请选开发提测的需求分支再分析");
        }
        try {
            List<com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange> changes = new ArrayList<>();
            for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
                EndpointChangeFlag.Input in = flagInput(e);
                changes.add(new com.mobile.standalone.service.apitest.ApiEndpointService.EndpointChange(
                        e.getHttpMethod(), e.getPath(), e.getHandler(), e.getSummary(), e.getFile(),
                        e.getChangeType(),
                        EndpointChangeFlag.of(in),
                        EndpointChangeFlag.note(in, e.getRisk()),
                        topLevelFieldNames(e),
                        e.getTables()));
            }
            var r = apiEndpointService.syncFromChangeReport(projectCode, report.getHeadRef(), changes);
            return Map.of("created", r.created(), "updated", r.updated(), "deprecated", r.deprecated());
        } catch (Exception ex) {
            log.warn("[api-changes] 同步接口清单失败（不影响本次报告）: {}", ex.getMessage());
            return Map.of("skipped", true,
                    "reason", "同步接口清单失败：" + (ex.getMessage() == null ? "未知原因" : ex.getMessage()));
        }
    }

    private static EndpointChangeFlag.Input flagInput(ApiChangeAnalyzer.EndpointChange e) {
        return new EndpointChangeFlag.Input(
                e.getChangeType(), e.getRisk(),
                e.getParams() == null ? 0 : e.getParams().size(),
                e.getFields() == null ? 0 : e.getFields().size(),
                e.getBodyFields() == null ? 0 : e.getBodyFields().size(),
                e.getChangedImpls() == null ? 0 : e.getChangedImpls().size());
    }

    /**
     * 给报告里的表挂上建表语句与测试注意事项。
     * <p>
     * 只报「涉及了 xxx 表」对测试没帮助——人还得自己翻 DDL、自己想边界。
     * 而建表语句里其实已经写着大半测试点（唯一索引、可空、大整数精度）。
     */
    private void attachTableSchemas(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null || report.getTables().isEmpty()) {
            return;
        }
        java.util.Map<String, com.mobile.standalone.entity.ApiTableSchema> manual = new java.util.HashMap<>();
        try {
            for (com.mobile.standalone.entity.ApiTableSchema x : apiTableSchemaMapper.findByProject(projectCode)) {
                if (x.getTableName() != null) {
                    manual.put(x.getTableName().toLowerCase(java.util.Locale.ROOT), x);
                }
            }
        } catch (Exception ignored) {
            // 手动录入只是兜底，表没建也不影响从库里读
        }
        java.util.Map<String, String[]> live = readLiveDdl(report, projectCode);
        for (ApiChangeAnalyzer.TableChange t : report.getTables()) {
            String key = tableKey(t.getTable());
            String[] hit = live.get(key);
            com.mobile.standalone.entity.ApiTableSchema sc = manual.get(key);
            String ddl = null;
            if (hit != null) {
                ddl = hit[0];
                t.setDdlSource("测试库 " + hit[1]);
            } else if (sc != null && sc.getDdl() != null && !sc.getDdl().isBlank()) {
                ddl = sc.getDdl();
                t.setDdlSource("手动录入");
            }
            if (sc != null) {
                t.setRemark(sc.getRemark());
            }
            if (ddl != null) {
                t.setDdlText(ddl);
                t.setTestHints(com.mobile.standalone.service.apichange.TableTestHints.derive(ddl));
            }
        }
    }

    /**
     * 从项目在项目管理里关联的测试库现读建表语句：表名小写 → [DDL, 库名]。
     * 读不到的原因写进 schemaNote，页面上要说清是「没关联库」还是「连不上」，而不是只显示一排空。
     */
    private java.util.Map<String, String[]> readLiveDdl(ApiChangeAnalyzer.Report report, String projectCode) {
        java.util.Map<String, String[]> out = new java.util.HashMap<>();
        List<String> tables = new ArrayList<>();
        for (ApiChangeAnalyzer.TableChange t : report.getTables()) {
            if (t.getTable() != null && !t.getTable().isBlank()) {
                tables.add(t.getTable());
            }
        }
        if (tables.isEmpty()) {
            return out;
        }
        List<com.mobile.standalone.entity.ApiDbConnection> conns;
        try {
            conns = dbConnections.candidates("TEST", projectCode);
        } catch (Exception e) {
            report.setSchemaNote("读项目关联的测试库失败：" + e.getMessage());
            return out;
        }
        if (conns.isEmpty()) {
            report.setSchemaNote("这个项目还没关联测试库，表结构读不到。去「项目管理」点「关联库」勾上，下次分析会自动读");
            return out;
        }
        boolean shared = conns.stream().allMatch(com.mobile.standalone.entity.ApiDbConnection::shared);
        java.util.Map<String, List<String>> failedByError = new java.util.LinkedHashMap<>();
        int tried = 0;
        int linkFailsInRow = 0;
        boolean anyOk = false;
        for (com.mobile.standalone.entity.ApiDbConnection c : conns) {
            List<String> rest = tables.stream().filter(n -> !out.containsKey(tableKey(n))).toList();
            if (rest.isEmpty()) {
                break;
            }
            // 通用库都在同一个内网：连着几个都网络不通就别再挨个等超时了
            if (shared && !anyOk && linkFailsInRow >= SHARED_GIVE_UP) {
                break;
            }
            tried++;
            var r = dbAssert.showCreateTables(
                    com.mobile.standalone.service.apitest.DbAssertRunner.Target.of(c), rest);
            r.ddl().forEach((k, v) -> out.putIfAbsent(k, new String[]{v, c.getName()}));
            if (r.error() != null) {
                String err = shortDbError(r.error());
                failedByError.computeIfAbsent(err, k -> new ArrayList<>()).add(c.getName());
                linkFailsInRow = err.startsWith("Communications link failure") ? linkFailsInRow + 1 : 0;
            } else {
                anyOk = true;
                linkFailsInRow = 0;
            }
        }
        List<String> missing = tables.stream().filter(n -> !out.containsKey(tableKey(n))).distinct().toList();
        if (shared) {
            String note = sharedDbNote(conns.size(), tried, failedByError, missing);
            if (note != null) {
                report.setSchemaNote(note);
            }
            return out;
        }
        List<String> parts = new ArrayList<>();
        if (!failedByError.isEmpty()) {
            parts.add(foldDbFailures(failedByError));
        }
        if (!missing.isEmpty()) {
            parts.add("关联的库里没找到 " + String.join("、", missing) + "，可能在没关联的库里");
        }
        if (!parts.isEmpty()) {
            report.setSchemaNote(String.join("。", parts));
        }
        return out;
    }

    static final int SHARED_GIVE_UP = 3;

    /**
     * 项目没关联库、退回到通用库时的说明。通用库和这个项目没关系，库名不列（列出来测试会以为是项目的库）；
     * 只说试了几个、为什么没读到、该去关联库。都以「这个项目还没关联测试库」开头，前端按这个归到环境问题。
     */
    static String sharedDbNote(int total, int tried, java.util.Map<String, List<String>> failedByError, List<String> missing) {
        if (missing.isEmpty()) {
            return null;
        }
        String fix = "去「项目管理」给这个项目点「关联库」勾上，下次只查关联的库";
        int failed = failedByError.values().stream().mapToInt(List::size).sum();
        if (failed == 0) {
            return "这个项目还没关联测试库，在 " + total + " 个通用库里都没找到 " + String.join("、", missing) + "。" + fix;
        }
        String why = String.join("；", failedByError.keySet());
        String skipped = tried < total ? "，其余 " + (total - tried) + " 个没再试" : "";
        String head = failed == tried
                ? "这个项目还没关联测试库，试了 " + tried + " 个通用库都连不上（" + why + "）" + skipped
                : "这个项目还没关联测试库，通用库里没找到 " + String.join("、", missing)
                        + "（" + failed + " 个连不上：" + why + skipped + "）";
        return head + "。" + fix;
    }

    /** 一批库常因同一个原因（没连内网）全连不上：同样的报错只说一次，库名全列（前端折叠展示） */
    static String foldDbFailures(java.util.Map<String, List<String>> byError) {
        int total = byError.values().stream().mapToInt(List::size).sum();
        List<String> groups = new ArrayList<>();
        for (var e : byError.entrySet()) {
            groups.add(String.join("、", e.getValue()) + "：" + e.getKey());
        }
        return total + " 个库连不上（" + String.join("；", groups) + "）";
    }

    static String shortDbError(String error) {
        String s = error.strip();
        int nl = s.indexOf('\n');
        if (nl > 0) {
            s = s.substring(0, nl).strip();
        }
        if (s.contains("Communications link failure")) {
            return "Communications link failure，网络不通（没连内网 / VPN？）";
        }
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String tableKey(String table) {
        String s = table == null ? "" : table.trim().replace("`", "");
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 算出哪些接口需要并发测试。
     * <p>
     * 必须排在 {@link #attachTableSchemas} <b>之后</b>：并发风险的依据是建表语句
     * （唯一索引、计数列），而 DDL 是那一步才挂上去的。放前面的话拿不到 DDL，
     * 扫描器会因为「没有依据」把所有表都跳过，静默返回空——看着像没风险，其实是没分析。
     */
    private void attachConcurrencyRisks(ApiChangeAnalyzer.Report report) {
        if (report == null || report.getEndpoints().isEmpty()) {
            return;
        }
        java.util.Map<String, String> ddlByTable = new java.util.HashMap<>();
        for (ApiChangeAnalyzer.TableChange t : report.getTables()) {
            if (t.getTable() != null && t.getDdlText() != null && !t.getDdlText().isBlank()) {
                ddlByTable.put(t.getTable().toLowerCase(java.util.Locale.ROOT), t.getDdlText());
            }
        }
        List<ConcurrencyRiskScanner.Risk> risks = new ArrayList<>();
        for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
            com.mobile.standalone.service.apichange.ApiEndpoint ep =
                    new com.mobile.standalone.service.apichange.ApiEndpoint(
                            e.getHttpMethod(), e.getPath(), e.getHandler(),
                            null, e.getSummary(), e.getPayloadType(), List.of(), java.util.Set.of());
            List<String> keys = new ArrayList<>();
            if (e.getRedisKeys() != null) {
                for (EndpointDependencyResolver.RedisKeyRef k : e.getRedisKeys()) {
                    if (k != null && k.getValue() != null && !k.getValue().isBlank()) {
                        keys.add(k.getValue());
                    }
                }
            }
            risks.addAll(ConcurrencyRiskScanner.scan(ep, e.getTables(), ddlByTable, keys));
        }
        // RISK 排在 INFO 前面：这份清单人只会从上往下扫，顺序就是优先级
        risks.sort((a, b) -> Integer.compare(
                "RISK".equals(a.level()) ? 0 : 1, "RISK".equals(b.level()) ? 0 : 1));
        report.setConcurrencyRisks(risks);
    }

    /**
     * 给报告里的每个接口标上已有几条接口用例。
     * <p>
     * 不标的话，报告看完人还得自己回忆「这接口我有用例吗」——那正是它该替人做的事。
     * 标了之后「未覆盖」那几条就是本次要补的用例清单。
     */
    private void markCoverage(ApiChangeAnalyzer.Report report, String projectCode) {
        if (report == null || report.getEndpoints().isEmpty()) {
            return;
        }
        List<com.mobile.standalone.entity.ApiTestCase> cases;
        try {
            cases = apiTestCaseMapper.findByFilters(projectCode, null, null, null, null, null, null, null);
        } catch (Exception e) {
            // 用例表没建或查不到不该让整份报告失败，覆盖率只是锦上添花
            return;
        }
        if (cases == null || cases.isEmpty()) {
            return;
        }
        for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
            String path = e.getPath() == null ? "" : e.getPath();
            int n = 0;
            for (com.mobile.standalone.entity.ApiTestCase c : cases) {
                String cp = c.getPath() == null ? "" : c.getPath();
                // 用例里的 path 可能带 baseUrl 前缀，也可能只写相对段，两头都兜一下
                if (!cp.isBlank() && (cp.equals(path) || cp.endsWith(path) || path.endsWith(cp))) {
                    n++;
                }
            }
            e.setTestCases(n);
        }
    }

    /**
     * 把这份报告里变更的接口，挂到需求单上：分支名里带的单号 + 调用方指定的单号。
     * <p>
     * 认不出单号（master、目录对比且没指定）时安静跳过——那种情况下本来就没有「哪个需求」这回事。
     */
    private Map<String, Object> linkToRequirement(ApiChangeAnalyzer.Report report, String projectCode,
                                                  List<String> extraKeys) {
        if (report == null || report.getEndpoints().isEmpty()) {
            return null;
        }
        Set<String> keys = new LinkedHashSet<>(BranchIssueKey.allIn(report.getHeadRef()));
        if (extraKeys != null) {
            extraKeys.stream().filter(k -> k != null && !k.isBlank()).map(String::trim).forEach(keys::add);
        }
        if (keys.isEmpty()) {
            return null;
        }
        try {
            List<RequirementEndpointService.Link> links = new ArrayList<>();
            for (ApiChangeAnalyzer.EndpointChange e : report.getEndpoints()) {
                EndpointChangeFlag.Input in = flagInput(e);
                links.add(new RequirementEndpointService.Link(
                        null, e.getHttpMethod(), e.getPath(), e.getSummary(),
                        EndpointChangeFlag.of(in), EndpointChangeFlag.note(in, e.getRisk())));
            }
            int total = 0;
            for (String key : keys) {
                total += requirementEndpointService.link(key, projectCode, report.getHeadRef(), links);
            }
            return Map.of("requirements", List.copyOf(keys), "linked", total);
        } catch (Exception ex) {
            log.warn("[api-changes] 关联需求单失败（不影响本次报告）: {}", ex.getMessage());
            return null;
        }
    }

    /** 返回体的顶层字段名，「生成用例」按它出断言 */
    private static List<String> topLevelFieldNames(ApiChangeAnalyzer.EndpointChange e) {
        List<String> out = new ArrayList<>();
        if (e.getResponseFields() == null) {
            return out;
        }
        for (var node : e.getResponseFields()) {
            if (node != null && node.getName() != null && !node.getName().isBlank()) {
                out.add(node.getName().trim());
            }
        }
        return out;
    }
}
