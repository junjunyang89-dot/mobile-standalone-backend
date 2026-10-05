package com.mobile.standalone.service.releasebatch;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.entity.ApiTestCollection;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.ApiChangeReportService;
import com.mobile.standalone.service.ApiChangeRunService;
import com.mobile.standalone.service.ProjectRepoSync;
import com.mobile.standalone.service.ReleaseDocService;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.apitest.AiCaseGenerator;
import com.mobile.standalone.service.apitest.ApiEndpointService;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.apitest.ApiTestCollectionService;
import com.mobile.standalone.service.apitest.GitSnapshotService;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Batch;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatCheck;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按版本批量做接口对比：拉单 → 认项目分支 → 建分支用例集合 → 逐单分析后端 → 出测试评估报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseBatchService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Pattern TICKET_LINE = Pattern.compile("^\\s*\\[?([A-Z][A-Z0-9]+-\\d+)\\]?\\s*[:：\\-—|]?\\s*(.*)$");
    private static final int NOTES_MAX = 6000;

    private final JdbcTemplate jdbc;
    private final ReleaseDocService releaseDoc;
    private final ApiProjectMapper projectMapper;
    private final ApiTestCollectionService collectionService;
    private final ApiChangeRunService runService;
    private final ApiChangeReportService reportService;
    private final GitSnapshotService snapshots;
    private final ProjectRepoSync repoSync;
    private final AiCaseGenerator aiCases;
    private final ApiEndpointService endpointService;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    @PostConstruct
    public void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS api_change_batches (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        version VARCHAR(128) NULL,
                        source VARCHAR(16) NULL,
                        ticket_count INT DEFAULT 0,
                        payload LONGTEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception e) {
            log.warn("[api-change-batches] 建表失败: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 建批次

    public Batch createFromWiki(String version) {
        List<ReleaseDocService.IssueDetail> issues = releaseDoc.listIssueDetails(version);
        Batch b = newBatch(version, "WIKI");
        List<ApiProject> projects = projects();
        for (ReleaseDocService.IssueDetail d : issues) {
            Ticket t = new Ticket();
            fillFromJira(t, d);
            t.setTargets(resolveTargets(t.getNotes(), projects));
            b.getTickets().add(t);
        }
        b.getLogs().add(now() + " 从 Wiki 拉到 " + issues.size() + " 个单");
        return insert(b);
    }

    /**
     * 贴 JIRA 单地址（或单号），一行一个也行、混在一段话里也行。逐个拉 JIRA 详情、从备注认项目分支。
     * 版本不填时用单号当名字。全部拉失败（多半是没连内网）直接报错；部分失败的单照样建，标题里写明原因。
     */
    public Batch createFromIssues(String version, String text) {
        List<String> keys = issueKeys(text);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("没认出 JIRA 单号：贴单子地址（…/browse/APP-1234）或直接写 APP-1234");
        }
        List<ApiProject> projects = projects();
        List<Ticket> tickets = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String key : keys) {
            Ticket t = new Ticket();
            try {
                fillFromJira(t, releaseDoc.fetchIssueDetail(key));
                t.setTargets(resolveTargets(t.getNotes(), projects));
            } catch (Exception e) {
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failed.add(key + "：" + why);
                t.setKey(key);
                t.setSummary("拉 JIRA 失败：" + why);
            }
            tickets.add(t);
        }
        if (failed.size() == keys.size()) {
            throw new IllegalArgumentException("拉 JIRA 失败（要连公司内网）：" + failed.get(0));
        }
        String name = !isBlank(version) ? version
                : keys.size() == 1 ? keys.get(0) : keys.get(0) + " 等 " + keys.size() + " 单";
        Batch b = newBatch(name, "JIRA");
        b.getTickets().addAll(tickets);
        b.getLogs().add(now() + " 按 JIRA 单拉到 " + (keys.size() - failed.size()) + " 个单"
                + (failed.isEmpty() ? "" : "，失败 " + failed.size() + " 个：" + String.join("；", failed)));
        return insert(b);
    }

    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]+-\\d+)\\b");

    static List<String> issueKeys(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Matcher m = ISSUE_KEY.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** 拿不到内网时：把 Wiki 表格 / JIRA 备注整段粘进来，以单号开头的行开始一个新单 */
    public Batch createFromText(String version, String text) {
        List<Ticket> tickets = parseTickets(text);
        if (tickets.isEmpty()) {
            throw new IllegalArgumentException("没认出单号：每个单另起一行，以 APP-1234 这样的单号开头");
        }
        String first = tickets.get(0).getKey();
        String name = !isBlank(version) ? version
                : tickets.size() == 1 ? first : first + " 等 " + tickets.size() + " 单";
        Batch b = newBatch(name, "MANUAL");
        List<ApiProject> projects = projects();
        for (Ticket t : tickets) {
            t.setTargets(resolveTargets(t.getNotes(), projects));
        }
        b.getTickets().addAll(tickets);
        b.getLogs().add(now() + " 手动粘贴 " + tickets.size() + " 个单");
        return insert(b);
    }

    /**
     * 完全离线：不拉 Wiki / JIRA、不拉远程代码，直接拿本地两份目录比。
     * 单号可不填；不是真实单号时不挂需求单、不建分支用例。
     */
    public Batch createFromLocal(String version, String issueKey, String summary, List<Target> rows) {
        List<ApiProject> projects = projects();
        List<Target> targets = new ArrayList<>();
        for (Target r : rows == null ? List.<Target>of() : rows) {
            if (isBlank(r.getProjectCode()) && isBlank(r.getBaseDir()) && isBlank(r.getHeadDir())) {
                continue;
            }
            if (isBlank(r.getProjectCode()) || isBlank(r.getBaseDir()) || isBlank(r.getHeadDir())) {
                throw new IllegalArgumentException("每一行的项目、基线目录、待测目录都要填");
            }
            ApiProject p = projects.stream().filter(x -> x.getCode().equals(r.getProjectCode().trim()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("项目不存在: " + r.getProjectCode()));
            Target t = new Target();
            t.setRawProject(p.getCode());
            t.setBranch(isBlank(r.getBranch()) ? null : r.getBranch().trim());
            t.setBaseBranch(isBlank(r.getBaseBranch()) ? "master" : r.getBaseBranch().trim());
            t.setBaseDir(r.getBaseDir().trim());
            t.setHeadDir(r.getHeadDir().trim());
            applyProject(t, p, "MANUAL");
            targets.add(t);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("至少填一个项目和两个目录");
        }
        String key = isBlank(issueKey) ? null : issueKey.trim().toUpperCase(Locale.ROOT);
        if (key != null && !isIssueKey(key)) {
            throw new IllegalArgumentException("单号格式不对，应为 APP-1234 这种；不知道单号就留空");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
        Batch b = newBatch(isBlank(version) ? "本地对比 " + stamp : version, "LOCAL");
        Ticket t = new Ticket();
        t.setKey(key == null ? "本地-" + stamp : key);
        t.setSummary(isBlank(summary) ? "本地目录对比" : summary.trim());
        t.setTargets(targets);
        b.getTickets().add(t);
        b.getLogs().add(now() + " 本地目录对比 " + targets.size() + " 个项目");
        return insert(b);
    }

    static boolean isIssueKey(String key) {
        return key != null && key.matches("[A-Z][A-Z0-9]+-\\d+");
    }

    static List<Ticket> parseTickets(String text) {
        Map<String, Ticket> out = new LinkedHashMap<>();
        Ticket cur = null;
        StringBuilder notes = null;
        for (String line : (text == null ? "" : text).split("\\R")) {
            Matcher m = TICKET_LINE.matcher(line);
            if (m.matches()) {
                if (cur != null) {
                    cur.setNotes(merge(cur.getNotes(), notes.toString()));
                }
                String key = m.group(1);
                cur = out.computeIfAbsent(key, k -> {
                    Ticket t = new Ticket();
                    t.setKey(k);
                    return t;
                });
                String rest = m.group(2).trim();
                notes = new StringBuilder();
                if (!rest.isEmpty()) {
                    // 从 Wiki 表格复制过来是 tab 分隔：第一格是标题，后面是经办人 / 状态 / 日期
                    String title = rest.split("\t")[0].trim();
                    if (cur.getSummary() == null && !title.isEmpty()) {
                        cur.setSummary(title.length() > 120 ? title.substring(0, 120) : title);
                    }
                    notes.append(rest).append('\n');
                }
                continue;
            }
            if (cur != null) {
                notes.append(line).append('\n');
            }
        }
        if (cur != null) {
            cur.setNotes(merge(cur.getNotes(), notes.toString()));
        }
        return new ArrayList<>(out.values());
    }

    private static String merge(String a, String b) {
        String x = (a == null ? "" : a) + (b == null ? "" : b);
        return x.length() > NOTES_MAX ? x.substring(0, NOTES_MAX) : x;
    }

    // ---------------------------------------------------------------- 查 / 删

    public List<Map<String, Object>> list(int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return jdbc.queryForList("SELECT id, version, source, ticket_count AS ticketCount, created_at AS createdAt, "
                + "updated_at AS updatedAt FROM api_change_batches ORDER BY id DESC LIMIT " + lim);
    }

    public Batch get(Long id) {
        List<String> rows = jdbc.queryForList("SELECT payload FROM api_change_batches WHERE id = ?", String.class, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批次不存在: " + id);
        }
        Batch b = read(rows.get(0));
        b.setId(id);
        return b;
    }

    static Batch read(String payload) {
        try {
            return JSON.readValue(payload, Batch.class);
        } catch (Exception e) {
            throw new IllegalStateException("批次数据解析失败: " + e.getMessage());
        }
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM api_change_batches WHERE id = ?", id);
    }

    // ---------------------------------------------------------------- 改单子

    /** 重新拉一个单的 JIRA 信息并重新认分支；人工改过的项目不丢 */
    public Batch refreshTicket(Long id, String key) {
        ReleaseDocService.IssueDetail d = releaseDoc.fetchIssueDetail(key);
        return update(id, b -> {
            Ticket t = requireTicket(b, key);
            List<Target> old = t.getTargets();
            fillFromJira(t, d);
            List<Target> fresh = resolveTargets(t.getNotes(), projects());
            for (Target n : fresh) {
                for (Target o : old) {
                    if (same(o.getRawProject(), n.getRawProject()) && same(o.getBranch(), n.getBranch())) {
                        if ("MANUAL".equals(o.getMatchHow())) {
                            copyMatch(o, n);
                        }
                        n.setBaseDir(o.getBaseDir());
                        n.setHeadDir(o.getHeadDir());
                        n.setBaseBranch(o.getBaseBranch());
                        n.setCollectionId(o.getCollectionId());
                    }
                }
            }
            for (Target o : old) {
                if ("BRANCH".equals(o.getMatchHow())
                        && fresh.stream().noneMatch(n -> same(n.getProjectCode(), o.getProjectCode()))) {
                    fresh.add(o);
                }
            }
            t.setTargets(fresh);
            b.getLogs().add(now() + " 刷新 " + key);
        });
    }

    /**
     * 人工改一个单的项目 / 分支 / 本地目录。传进来的是整张列表：
     * projectCode 填了就按它认（MANUAL），没填按 rawProject 重新匹配。
     */
    public Batch saveTargets(Long id, String key, List<Target> edits) {
        List<ApiProject> projects = projects();
        return update(id, b -> {
            Ticket t = requireTicket(b, key);
            List<Target> out = new ArrayList<>();
            for (Target e : edits == null ? List.<Target>of() : edits) {
                if (isBlank(e.getRawProject()) && isBlank(e.getProjectCode())) {
                    continue;
                }
                Target n = new Target();
                n.setRawProject(isBlank(e.getRawProject()) ? e.getProjectCode() : e.getRawProject().trim());
                n.setBranch(e.getBranch() == null ? "" : e.getBranch().trim());
                n.setBaseBranch(trimToNull(e.getBaseBranch()));
                n.setNote(e.getNote());
                n.setBaseDir(trimToNull(e.getBaseDir()));
                n.setHeadDir(trimToNull(e.getHeadDir()));
                n.setCollectionId(e.getCollectionId());
                Target old = t.getTargets().stream()
                        .filter(o -> same(o.getRawProject(), n.getRawProject()) && same(o.getBranch(), n.getBranch()))
                        .findFirst().orElse(null);
                if (!isBlank(e.getProjectCode())) {
                    ApiProject p = projects.stream().filter(x -> x.getCode().equals(e.getProjectCode().trim()))
                            .findFirst().orElse(null);
                    if (p == null) {
                        throw new IllegalArgumentException("项目不存在: " + e.getProjectCode());
                    }
                    boolean unchanged = old != null && p.getCode().equals(old.getProjectCode());
                    applyProject(n, p, unchanged ? old.getMatchHow() : "MANUAL");
                } else {
                    applyMatch(n, ProjectMatcher.match(n.getRawProject(), projects));
                }
                if (old != null && same(old.getProjectCode(), n.getProjectCode()) && same(old.getBaseBranch(), n.getBaseBranch())) {
                    n.setReportId(old.getReportId());
                    n.setReportSummary(old.getReportSummary());
                    n.setSyncMessage(old.getSyncMessage());
                    n.setError(old.getError());
                    n.setFront(old.getFront());
                    n.setCodeMissing(old.getCodeMissing());
                }
                out.add(n);
            }
            for (Target o : t.getTargets()) {
                if ("BRANCH".equals(o.getMatchHow()) && o.getProjectCode() != null
                        && out.stream().noneMatch(n -> same(n.getProjectCode(), o.getProjectCode()))) {
                    t.getSkipBranchProjects().add(o.getProjectCode());
                }
            }
            t.setTargets(out);
            rebuildAssessment(b, t);
        });
    }

    /**
     * 测试指引升级后老单子缺新内容：拿已存的对比结果重算一遍评估，不读代码、不重跑分析，很快。
     * 跨项目关系、兼容检查这些要读代码的部分保持上次分析的结果。
     */
    public Batch rebuildTicket(Long id, String key) {
        return update(id, b -> {
            Ticket t = requireTicket(b, key);
            if (t.getAnalyzedAt() == null && t.getAssessment() == null) {
                throw new IllegalStateException("这一单还没分析过");
            }
            rebuildAssessment(b, t);
        });
    }

    public Batch saveManual(Long id, String key, Map<String, String> manual) {
        return update(id, b -> {
            Ticket t = requireTicket(b, key);
            if (t.getAssessment() == null) {
                rebuildAssessment(b, t);
            }
            t.getAssessment().getManual().clear();
            if (manual != null) {
                manual.forEach((k, v) -> {
                    if (k != null && v != null && !v.isBlank()) {
                        t.getAssessment().getManual().put(k, v);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 建分支用例集合

    /** 每单 × 每个后端项目建一个「分支用例」集合（已有就复用）。一个后端都没有的单跳过并说明 */
    public Map<String, Object> createCollections(Long id) {
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Batch saved = update(id, b -> {
            for (Ticket t : b.getTickets()) {
                if (!isIssueKey(t.getKey())) {
                    skipped.add(t.getKey() + "：没有单号，不建分支用例");
                    continue;
                }
                List<Target> backends = t.getTargets().stream()
                        .filter(x -> x.getProjectCode() != null && ApiProjectKind.isBackend(x.getKind())).toList();
                if (backends.isEmpty()) {
                    skipped.add(t.getKey() + "：没有认出后端项目");
                    continue;
                }
                for (Target x : backends) {
                    try {
                        ApiTestCollection c = collectionService.findOrCreateTestCollection(
                                x.getProjectCode(), t.getKey(), b.getVersion(), blankToNull(x.getBranch()));
                        boolean isNew = x.getCollectionId() == null || !x.getCollectionId().equals(c.getId());
                        x.setCollectionId(c.getId());
                        if (isNew) {
                            created.add(t.getKey() + " · " + TestAssessmentBuilder.nameOf(x) + " → " + c.getName());
                        }
                    } catch (Exception e) {
                        skipped.add(t.getKey() + " · " + TestAssessmentBuilder.nameOf(x) + "：" + e.getMessage());
                    }
                }
            }
            b.getLogs().add(now() + " 建分支用例集合：新建 / 关联 " + created.size() + "，跳过 " + skipped.size());
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("batch", saved);
        return out;
    }

    // ---------------------------------------------------------------- AI 生成用例到分支

    /**
     * 让 AI 给这一单某个后端项目改动的接口生成用例，放进这一单 + 分支的「分支用例」集合。
     * 读的是待测那份代码（本地目录 / 分支快照），不是测试环境分支——要测的就是这次的新逻辑。
     *
     * @param sigs 「POST /x」这样的接口；空 = 这个项目除下线外的全部改动接口
     */
    public AiCaseGenerator.Job startAiCases(Long id, String key, String projectCode, List<String> sigs,
                                            boolean tryRun, boolean runWrites) {
        Batch b = get(id);
        Ticket t = requireTicket(b, key);
        Target x = t.getTargets().stream()
                .filter(o -> o.isAnalyzable() && projectCode != null && projectCode.equals(o.getProjectCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("这一单里没有后端项目 " + projectCode));
        if (x.getReportId() == null) {
            throw new IllegalStateException("这个项目还没分析成功，先点「分析这一单」");
        }
        boolean local = !isBlank(x.getBaseDir()) && !isBlank(x.getHeadDir());
        String requirement = isIssueKey(t.getKey()) ? t.getKey() : null;
        String branch = blankToNull(x.getBranch());
        if (requirement == null && branch == null) {
            throw new IllegalArgumentException("没有单号也没有分支名，建不了分支用例集合：点「修正项目 / 分支」把分支名填上");
        }
        // 和分析时挂接口清单用的名字一致，否则查不到这次分析登记的接口
        String catalogBranch = branch != null ? branch : "目录:" + x.getProjectCode();

        List<String> wanted = sigs == null ? List.of() : sigs.stream().filter(s -> !isBlank(s)).map(String::trim).toList();
        if (wanted.isEmpty() && t.getAssessment() != null) {
            wanted = t.getAssessment().getEndpointCards().stream()
                    .filter(c -> TestAssessmentBuilder.nameOf(x).equals(c.getProject()))
                    .filter(c -> !"OFFLINE".equals(c.getVerdict()))
                    .map(c -> c.getMethod() + " " + c.getPath()).toList();
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("这个项目没有要生成用例的改动接口");
        }
        Set<String> want = new HashSet<>();
        wanted.forEach(s -> want.add(s.toUpperCase(Locale.ROOT)));
        List<String> ids = new ArrayList<>();
        for (com.mobile.standalone.entity.ApiEndpoint e
                : endpointService.getAll(endpointService.endpointIdsOfBranch(x.getProjectCode(), catalogBranch)).values()) {
            String m = e.getHttpMethod() == null ? "" : e.getHttpMethod().toUpperCase(Locale.ROOT);
            if (want.contains((m + " " + e.getPath()).toUpperCase(Locale.ROOT))
                    || e.getCodePath() != null && want.contains((m + " " + e.getCodePath()).toUpperCase(Locale.ROOT))) {
                ids.add(e.getId());
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("接口清单里没找到这些接口（分析时挂接口清单可能失败了），重新分析一次再生成");
        }

        String dir;
        if (local) {
            dir = x.getHeadDir().trim();
        } else {
            CodeDirs code = resolveCode(x, null, false);
            if (code.missing() != null) {
                throw new IllegalStateException("拿不到分支代码：" + code.missing());
            }
            dir = code.head().toString();
        }
        ApiTestCollection col = collectionService.findOrCreateTestCollection(
                x.getProjectCode(), requirement, b.getVersion(), branch);
        AiCaseGenerator.Job job = aiCases.start(
                new AiCaseGenerator.Request(x.getProjectCode(), ids, false, tryRun, runWrites, dir, false, null),
                new AiCaseGenerator.BranchTarget(col.getId(), catalogBranch, requirement));
        update(id, nb -> {
            Ticket cur = requireTicket(nb, key);
            cur.getTargets().stream().filter(o -> same(o.getProjectCode(), x.getProjectCode()) && same(o.getBranch(), x.getBranch()))
                    .forEach(o -> {
                        o.setCollectionId(col.getId());
                        o.setAiJobId(job.getId());
                    });
            nb.getLogs().add(now() + " " + key + " · " + TestAssessmentBuilder.nameOf(x) + " AI 生成用例 " + ids.size() + " 个接口 → " + col.getName());
        });
        return job;
    }

    // ---------------------------------------------------------------- 分析

    /**
     * 分析一个单涉及的后端项目，然后出评估报告。页面逐单调用，好显示进度。
     *
     * @param baseRef 基线分支；空 = 项目设置里的基线（默认 master）
     * @param force   false 时同一批里已分析过的「项目 + 分支」直接复用
     */
    public Batch analyzeTicket(Long id, String key, String baseRef, boolean force) {
        Batch snapshot = get(id);
        if (!"LOCAL".equals(snapshot.getSource()) && addBranchProjects(id, key)) {
            snapshot = get(id);
        }
        Ticket t = requireTicket(snapshot, key);
        Map<String, Target> results = new LinkedHashMap<>();
        Set<String> synced = new HashSet<>();
        for (Target x : t.getTargets()) {
            Target r = copyOf(x);
            if (x.isAnalyzable()) {
                Target cached = force ? null : findAnalyzed(snapshot, x);
                if (cached != null) {
                    r.setReportId(cached.getReportId());
                    r.setReportSummary(cached.getReportSummary());
                    r.setSyncMessage(cached.getSyncMessage());
                    r.setError(null);
                } else {
                    runOne(r, t.getKey(), baseRef);
                    if (isBlank(r.getBaseDir()) || isBlank(r.getHeadDir())) {
                        synced.add(r.getProjectCode());
                    }
                }
            }
            results.put(targetKey(x), r);
        }
        List<Path> backendHeads = new ArrayList<>();
        CrossProjectLinker.Result linked = linkProjects(new ArrayList<>(results.values()), baseRef, synced, backendHeads);
        Map<String, Integer> mentions = backendHeads.isEmpty() ? null
                : CodeWordSearch.countFiles(backendHeads, TicketCodeCheck.wordsToSearch(t));
        CompatCheck compat = probeCallers(results.values());
        return update(id, b -> {
            Ticket cur = requireTicket(b, key);
            for (Target x : cur.getTargets()) {
                Target r = results.get(targetKey(x));
                if (r != null) {
                    x.setReportId(r.getReportId());
                    x.setReportSummary(r.getReportSummary());
                    x.setSyncMessage(r.getSyncMessage());
                    x.setError(r.getError());
                    x.setFront(r.getFront());
                    x.setCodeMissing(r.getCodeMissing());
                }
            }
            cur.setNodes(linked.nodes());
            cur.setLinks(linked.links());
            cur.setCodeMentions(mentions);
            cur.setCompat(compat);
            cur.setAnalyzedAt(LocalDateTime.now());
            rebuildAssessment(b, cur);
        });
    }

    /**
     * 拿这一单的分支名（和单号）去项目管理里其它仓库找同名分支，找到的项目加进这一单（matchHow=BRANCH），
     * 后面的跨项目分析、AI 读代码就都能带上它。人工删过的不再加回来。
     *
     * @return 有没有新加项目
     */
    private boolean addBranchProjects(Long id, String key) {
        Ticket t = requireTicket(get(id), key);
        Set<String> branches = new java.util.LinkedHashSet<>();
        Set<String> have = new HashSet<>(t.getSkipBranchProjects());
        for (Target x : t.getTargets()) {
            String br = blankToNull(x.getBranch());
            if (br != null) {
                branches.add(br.startsWith("origin/") ? br.substring("origin/".length()) : br);
            }
            if (x.getProjectCode() != null) {
                have.add(x.getProjectCode());
            }
        }
        String issue = isIssueKey(t.getKey()) ? t.getKey() : null;
        List<ApiProject> others = projects().stream().filter(p -> !have.contains(p.getCode())).toList();
        BranchFinder.Result found = BranchFinder.find(others, branches, issue);
        if (found.checked() == 0) {
            return false;
        }
        List<String> added = new ArrayList<>();
        update(id, b -> {
            Ticket cur = requireTicket(b, key);
            for (BranchFinder.Hit h : found.hits()) {
                if (cur.getTargets().stream().anyMatch(o -> same(o.getProjectCode(), h.project().getCode()) && same(o.getBranch(), h.branch()))) {
                    continue;
                }
                Target n = new Target();
                n.setRawProject(h.project().getCode());
                n.setBranch(h.branch());
                n.setNote("JIRA 备注没写，按分支在仓库里找到");
                applyProject(n, h.project(), "BRANCH");
                cur.getTargets().add(n);
                added.add(TestAssessmentBuilder.nameOf(n) + "@" + h.branch());
            }
            b.getLogs().add(now() + " " + key + " 按分支找项目：查了 " + found.checked() + " 个仓库"
                    + (added.isEmpty() ? "，没有新项目" : "，新加 " + String.join("、", added))
                    + (found.failed().isEmpty() ? "" : "；" + found.failed().size() + " 个查不了（如 " + found.failed().get(0) + "）"));
        });
        return !added.isEmpty();
    }

    /**
     * 给 AI 读代码用：这一单每个项目待测那份代码的目录（本地目录 / 分支快照）。拿不到的写明原因。
     */
    public List<Map<String, Object>> codeDirs(Long id, String key) {
        Ticket t = requireTicket(get(id), key);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Target x : t.getTargets()) {
            if (x.getProjectCode() == null || !seen.add(targetKey(x))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectCode", x.getProjectCode());
            m.put("project", TestAssessmentBuilder.nameOf(x));
            m.put("kind", x.getKind());
            m.put("branch", blankToNull(x.getBranch()));
            try {
                CodeDirs code = resolveCode(x, null, true);
                if (code.missing() != null) {
                    m.put("missing", code.missing());
                } else {
                    m.put("dir", code.head().toString());
                }
            } catch (Exception e) {
                m.put("missing", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 拿到每个项目两边的代码（本地目录直接用；分支就从仓库导出快照），
     * 对比前端 / 客户端改动，扫调用点和后端接口清单，再算项目之间的关系。
     * 结果写回 {@code targets} 里每一项的 front / codeMissing。
     */
    private CrossProjectLinker.Result linkProjects(List<Target> targets, String baseRef, Set<String> synced,
                                                   List<Path> backendHeads) {
        List<CrossProjectLinker.Project> projects = new ArrayList<>();
        for (Target r : targets) {
            Report report = r.getReportId() == null || r.getError() != null ? null : safeLoad(r.getReportId());
            List<CallSite> calls = List.of();
            List<ApiEndpoint> inventory = List.of();
            r.setFront(null);
            r.setCodeMissing(null);
            try {
                CodeDirs code = resolveCode(r, baseRef, synced.contains(r.getProjectCode()));
                if (code.missing() != null) {
                    r.setCodeMissing(code.missing());
                } else {
                    CallSiteScanner.Scope scope = scopeOf(r);
                    r.setFront(FrontDiffScanner.diff(code.base(), code.head(), scope, TestAssessmentBuilder.nameOf(r)));
                    calls = CallSiteScanner.scanRepo(code.head(), scope);
                    if (scope == CallSiteScanner.Scope.BACKEND) {
                        inventory = EndpointInventory.scan(code.head());
                        backendHeads.add(code.head());
                    }
                }
            } catch (Exception e) {
                log.warn("[api-change-batches] {} 读代码失败: {}", r.getProjectCode(), e.getMessage());
                r.setCodeMissing("读代码失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            projects.add(new CrossProjectLinker.Project(r, report, calls, inventory,
                    CrossProjectLinker.changedFiles(report, r.getFront())));
        }
        return CrossProjectLinker.link(projects);
    }

    /**
     * 后端有不兼容改动时，拿项目管理里每个前端 / 客户端仓库的 master（= 线上版本）看它受不受影响。
     * 没有不兼容改动返回 null，不去拉一堆仓库。
     */
    private CompatCheck probeCallers(Collection<Target> targets) {
        List<CallerCompatProbe.Endpoint> eps = new ArrayList<>();
        for (Target r : targets) {
            if (!r.isAnalyzable() || r.getReportId() == null || r.getError() != null) {
                continue;
            }
            Report rep = safeLoad(r.getReportId());
            if (rep != null) {
                rep.getEndpoints().forEach(e -> eps.add(new CallerCompatProbe.Endpoint(TestAssessmentBuilder.nameOf(r), e)));
            }
        }
        if (!CallerCompatProbe.anyBreak(eps)) {
            return null;
        }
        Set<String> inTicket = new HashSet<>();
        targets.forEach(x -> {
            if (x.getProjectCode() != null) {
                inTicket.add(x.getProjectCode());
            }
        });
        List<CallerCompatProbe.Caller> callers = new ArrayList<>();
        for (ApiProject p : projects()) {
            String kind = ApiProjectKind.normalize(p.getKind());
            if (Boolean.FALSE.equals(p.getEnabled())
                    || !ApiProjectKind.FRONTEND.equals(kind) && !ApiProjectKind.CLIENT.equals(kind)) {
                continue;
            }
            callers.add(masterOf(p, kind, inTicket.contains(p.getCode())));
        }
        return CallerCompatProbe.probe(eps, callers);
    }

    private CallerCompatProbe.Caller masterOf(ApiProject p, String kind, boolean inTicket) {
        String name = isBlank(p.getName()) ? p.getCode() : p.getName();
        try {
            if (isBlank(p.getRepoPath()) && isBlank(p.getGitUrl())) {
                return new CallerCompatProbe.Caller(p.getCode(), name, kind, inTicket, null, "项目管理里没配置仓库地址");
            }
            repoSync.syncQuietly(p.getCode(), ApiChangeRunService.SYNC_FRESH_MS);
            ApiProject fresh = projectMapper.findByCode(p.getCode());
            if (fresh == null || isBlank(fresh.getRepoPath()) || !Files.isDirectory(Path.of(fresh.getRepoPath()))) {
                return new CallerCompatProbe.Caller(p.getCode(), name, kind, inTicket, null, "本地还没有这个仓库");
            }
            Path repo = Path.of(fresh.getRepoPath());
            String base = snapshots.baseRef(repo, fresh.getBaseBranch());
            return new CallerCompatProbe.Caller(p.getCode(), name, kind, inTicket,
                    snapshots.snapshot(repo, base, false, false).dir(), null);
        } catch (Exception e) {
            log.warn("[api-change-batches] {} 取 master 代码失败: {}", p.getCode(), e.getMessage());
            return new CallerCompatProbe.Caller(p.getCode(), name, kind, inTicket, null,
                    "取 master 代码失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 这一单里某个项目两边的代码目录，给 AI 翻译逻辑读完整方法用。
     *
     * @param project 逻辑改动上记的项目名（{@link TestAssessmentBuilder#nameOf}），也认项目编码
     */
    CodeDirs codeOf(Long id, String key, String project) {
        Ticket t = requireTicket(get(id), key);
        Target x = t.getTargets().stream()
                .filter(o -> o.getProjectCode() != null
                        && (same(TestAssessmentBuilder.nameOf(o), project) || same(o.getProjectCode(), project)))
                .min(java.util.Comparator.comparing(o -> o.isAnalyzable() ? 0 : 1))
                .orElse(null);
        if (x == null) {
            return CodeDirs.missing("这一单里没有项目 " + project);
        }
        try {
            return resolveCode(x, null, true);
        } catch (Exception e) {
            return CodeDirs.missing(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    Batch saveLogicExplain(Long id, String key, String groupKey, ReleaseBatchDtos.LogicExplain e) {
        return update(id, b -> requireTicket(b, key).getLogicExplains().put(groupKey, e));
    }

    record CodeDirs(Path base, Path head, String missing) {
        static CodeDirs missing(String why) {
            return new CodeDirs(null, null, why);
        }
    }

    private CodeDirs resolveCode(Target x, String baseRef, boolean alreadySynced) {
        if (x.getProjectCode() == null) {
            return CodeDirs.missing("项目管理里没找到这个项目");
        }
        if (!isBlank(x.getBaseDir()) && !isBlank(x.getHeadDir())) {
            Path base = Path.of(x.getBaseDir().trim());
            Path head = Path.of(x.getHeadDir().trim());
            if (!Files.isDirectory(base) || !Files.isDirectory(head)) {
                return CodeDirs.missing("本地目录不存在：" + (Files.isDirectory(base) ? head : base));
            }
            return new CodeDirs(base, head, null);
        }
        if (isBlank(x.getBranch())) {
            return CodeDirs.missing("没填分支，也没填本地目录");
        }
        ApiProject p = projectMapper.findByCode(x.getProjectCode());
        if (p == null || isBlank(p.getRepoPath()) && isBlank(p.getGitUrl())) {
            return CodeDirs.missing("项目管理里没配置仓库地址");
        }
        String pullMsg = null;
        if (!alreadySynced) {
            Map<String, Object> pulled = repoSync.syncQuietly(p.getCode(), ApiChangeRunService.SYNC_FRESH_MS);
            if (Boolean.FALSE.equals(pulled.get("ok"))) {
                pullMsg = String.valueOf(pulled.get("message"));
            }
            p = projectMapper.findByCode(p.getCode());
        }
        if (p == null || isBlank(p.getRepoPath()) || !Files.isDirectory(Path.of(p.getRepoPath()))) {
            return CodeDirs.missing("本地还没有这个仓库" + (pullMsg == null ? "" : "（" + pullMsg + "）"));
        }
        Path repo = Path.of(p.getRepoPath());
        String branch = x.getBranch().trim();
        String headRef = snapshots.resolve(repo, "origin/" + branch) != null ? "origin/" + branch
                : snapshots.resolve(repo, branch) != null ? branch : null;
        if (headRef == null) {
            return CodeDirs.missing("仓库里找不到分支 " + branch + (pullMsg == null ? "" : "（" + pullMsg + "）"));
        }
        String own = baseOf(x, baseRef);
        String base = snapshots.baseRef(repo, own != null ? own : p.getBaseBranch());
        String forkPoint = snapshots.mergeBase(repo, base, headRef);
        GitSnapshotService.Snapshot b = snapshots.snapshot(repo, forkPoint != null ? forkPoint : base, false, false);
        GitSnapshotService.Snapshot h = snapshots.snapshot(repo, headRef, false, false);
        return new CodeDirs(b.dir(), h.dir(), null);
    }

    private static CallSiteScanner.Scope scopeOf(Target x) {
        if (ApiProjectKind.CLIENT.equals(x.getKind())) {
            return CallSiteScanner.Scope.CLIENT;
        }
        return ApiProjectKind.FRONTEND.equals(x.getKind()) ? CallSiteScanner.Scope.FRONTEND : CallSiteScanner.Scope.BACKEND;
    }

    private Report safeLoad(Long reportId) {
        try {
            return reportService.loadReport(reportId);
        } catch (Exception e) {
            return null;
        }
    }

    private void runOne(Target r, String issueKey, String baseRef) {
        List<String> keys = isIssueKey(issueKey) ? List.of(issueKey) : List.of();
        try {
            Report report;
            if (!isBlank(r.getBaseDir()) && !isBlank(r.getHeadDir())) {
                report = runService.analyzeDirs(r.getProjectCode(), r.getBaseDir(), r.getHeadDir(),
                        blankToNull(r.getBaseBranch()), blankToNull(r.getBranch()), keys, true);
                r.setSyncMessage("本地目录对比：" + r.getBaseDir() + " → " + r.getHeadDir());
            } else {
                if (isBlank(r.getBranch())) {
                    throw new IllegalArgumentException("没有分支名，填上分支或本地目录再分析");
                }
                report = runService.analyzeGit(r.getProjectCode(), baseOf(r, baseRef), r.getBranch(), keys, true);
                Map<String, Object> sync = report.getRepoSync();
                r.setSyncMessage(sync == null ? null : String.valueOf(sync.get("message")));
            }
            r.setReportId(report.getReportId());
            r.setReportSummary(ApiChangeReportService.buildSummary(report));
            r.setError(null);
        } catch (Exception e) {
            log.warn("[api-change-batches] {} {} 分析失败: {}", issueKey, r.getProjectCode(), e.getMessage());
            r.setError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            r.setReportId(null);
            r.setReportSummary(null);
        }
    }

    private void rebuildAssessment(Batch b, Ticket t) {
        List<TestAssessmentBuilder.ProjectReport> reports = new ArrayList<>();
        for (Target x : t.getTargets()) {
            if (!x.isAnalyzable() || (x.getReportId() == null && x.getError() == null)) {
                continue;
            }
            reports.add(new TestAssessmentBuilder.ProjectReport(x,
                    x.getReportId() == null ? null : reportService.loadReport(x.getReportId())));
        }
        Map<String, String> manual = t.getAssessment() == null ? Map.of() : t.getAssessment().getManual();
        Assessment a = TestAssessmentBuilder.build(b.getVersion(), t, reports,
                LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        a.getManual().putAll(manual);
        t.setAssessment(a);
        t.getLogicExplains().entrySet().removeIf(e -> !java.util.Objects.equals(e.getValue().getFingerprint(),
                LogicExplainService.fingerprint(LogicExplainService.itemsOf(a, e.getKey()))));
    }

    private static Target findAnalyzed(Batch b, Target x) {
        for (Ticket t : b.getTickets()) {
            for (Target o : t.getTargets()) {
                if (o.getReportId() != null && o.getError() == null && targetKey(o).equals(targetKey(x))) {
                    return o;
                }
            }
        }
        return null;
    }

    static String targetKey(Target x) {
        return x.getProjectCode() + "|" + (x.getBranch() == null ? "" : x.getBranch().trim())
                + "|" + (x.getBaseBranch() == null ? "" : x.getBaseBranch().trim())
                + "|" + (x.getBaseDir() == null ? "" : x.getBaseDir()) + "|" + (x.getHeadDir() == null ? "" : x.getHeadDir());
    }

    /** 这个项目自己填了基线就用它，没填用整批传进来的，都没有走项目设置 */
    private static String baseOf(Target x, String baseRef) {
        return !isBlank(x.getBaseBranch()) ? x.getBaseBranch().trim() : blankToNull(baseRef);
    }

    // ---------------------------------------------------------------- 认项目

    static List<Target> resolveTargets(String notes, List<ApiProject> projects) {
        List<Target> out = new ArrayList<>();
        for (BranchNoteParser.BranchRef r : BranchNoteParser.parse(notes)) {
            Target t = new Target();
            t.setRawProject(r.project());
            t.setBranch(r.branch());
            t.setNote(r.note());
            applyMatch(t, ProjectMatcher.match(r.project(), projects));
            out.add(t);
        }
        return out;
    }

    private static void applyMatch(Target t, ProjectMatcher.Match m) {
        if (m == null) {
            t.setProjectCode(null);
            t.setProjectName(null);
            t.setKind(null);
            t.setMatchHow(null);
            t.setAnalyzable(false);
            return;
        }
        applyProject(t, m.project(), m.how());
    }

    private static void applyProject(Target t, ApiProject p, String how) {
        t.setProjectCode(p.getCode());
        t.setProjectName(p.getName());
        t.setKind(ApiProjectKind.of(p));
        t.setMatchHow(how);
        t.setAnalyzable(ApiProjectKind.scansEndpoints(p));
    }

    private static void copyMatch(Target from, Target to) {
        to.setProjectCode(from.getProjectCode());
        to.setProjectName(from.getProjectName());
        to.setKind(from.getKind());
        to.setMatchHow(from.getMatchHow());
        to.setAnalyzable(from.isAnalyzable());
    }

    private static Target copyOf(Target x) {
        return JSON.convertValue(x, Target.class);
    }

    private void fillFromJira(Ticket t, ReleaseDocService.IssueDetail d) {
        t.setKey(d.key());
        t.setUrl(d.url());
        t.setSummary(d.summary());
        t.setStatus(d.status());
        t.setAssignee(d.assignee());
        t.setQaAssignee(d.qaAssignee());
        String text = d.text() == null ? "" : d.text();
        t.setNotes(text.length() > NOTES_MAX ? text.substring(0, NOTES_MAX) : text);
        t.setDbChanges(blankToNull(d.dbChanges()));
        t.setConfigChanges(blankToNull(d.configChanges()));
    }

    private List<ApiProject> projects() {
        return projectMapper.findAll(false);
    }

    // ---------------------------------------------------------------- 落库

    private Batch newBatch(String version, String source) {
        if (isBlank(version)) {
            throw new IllegalArgumentException("请填写版本，如 10.9 国内H5");
        }
        Batch b = new Batch();
        b.setVersion(version.trim());
        b.setSource(source);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(b.getCreatedAt());
        return b;
    }

    private Batch insert(Batch b) {
        String payload = write(b);
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO api_change_batches (version, source, ticket_count, payload, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, b.getVersion());
            ps.setString(2, b.getSource());
            ps.setInt(3, b.getTickets().size());
            ps.setString(4, payload);
            ps.setTimestamp(5, Timestamp.valueOf(b.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.valueOf(b.getUpdatedAt()));
            return ps;
        }, kh);
        b.setId(kh.getKey() == null ? null : kh.getKey().longValue());
        return b;
    }

    private interface Edit {
        void apply(Batch b);
    }

    private Batch update(Long id, Edit edit) {
        synchronized (locks.computeIfAbsent(id, k -> new Object())) {
            Batch b = get(id);
            edit.apply(b);
            b.setUpdatedAt(LocalDateTime.now());
            if (b.getLogs().size() > 200) {
                b.setLogs(new ArrayList<>(b.getLogs().subList(b.getLogs().size() - 200, b.getLogs().size())));
            }
            jdbc.update("UPDATE api_change_batches SET payload = ?, ticket_count = ?, updated_at = ? WHERE id = ?",
                    write(b), b.getTickets().size(), Timestamp.valueOf(b.getUpdatedAt()), id);
            return b;
        }
    }

    static String write(Batch b) {
        try {
            return JSON.writeValueAsString(b);
        } catch (Exception e) {
            throw new IllegalStateException("批次序列化失败: " + e.getMessage());
        }
    }

    private static Ticket requireTicket(Batch b, String key) {
        return b.getTickets().stream().filter(t -> t.getKey().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("批次里没有单 " + key));
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a.trim().toLowerCase(Locale.ROOT)).equals(b == null ? "" : b.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String blankToNull(String s) {
        return isBlank(s) || "无".equals(s.trim()) ? null : s.trim();
    }
}
