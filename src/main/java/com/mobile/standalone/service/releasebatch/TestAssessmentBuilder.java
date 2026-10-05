package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.DtoChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FileChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ApiRiskScanner;
import com.mobile.standalone.service.apichange.ConcurrencyRiskScanner;
import com.mobile.standalone.service.apichange.ConfigPresence;
import com.mobile.standalone.service.apichange.DdlChange;
import com.mobile.standalone.service.apichange.EndpointDependencyResolver;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.apichange.MustTestGuide;
import com.mobile.standalone.service.apichange.TableTestHints;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Check;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatCheck;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatHit;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndpointDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FileRow;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FrontChange;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Link;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 一个单子的《代码 Diff 对比分析与测试评估报告》：从这一单涉及的各后端项目的对比报告里拼出来。
 * <p>
 * 能从代码里确证的才自动填 / 自动勾，每个勾都带依据；旧逻辑 / 新逻辑这类代码说不清的留给人填。
 */
public final class TestAssessmentBuilder {

    private TestAssessmentBuilder() {
    }

    /** 一个后端项目的对比结果 */
    public record ProjectReport(Target target, Report report) {
    }

    private static final Pattern BUGFIX = Pattern.compile("(?i)(修复|bug|fix|问题|异常|无法|失败|报错|崩溃|闪退)");
    private static final Pattern FEATURE = Pattern.compile("(新增|新功能|上线.*功能|增加)");
    private static final Pattern OPTIMIZE = Pattern.compile("(优化|改版|调整|重构|升级)");
    private static final Pattern PERF = Pattern.compile("(性能|耗时|卡顿|慢查询|加载慢|QPS|并发量)");
    private static final Pattern PUBLIC_PATH = Pattern.compile("(?i)/(common|util|utils|base|core|config|support|framework|aspect|interceptor|filter)/");
    private static final Pattern MQ_PATH = Pattern.compile("(?i)(kafka|rocketmq|rabbit|mq|consumer|producer|listener)");
    private static final Pattern REMOTE_PATH = Pattern.compile("(?i)(client|feign|resttemplate|httputil|remote|gateway|sdk)");
    private static final int MAX_LIST = 60;

    public static Assessment build(String version, Ticket t, List<ProjectReport> reports, String today) {
        Assessment a = new Assessment();
        List<ProjectReport> ok = reports == null ? List.of()
                : reports.stream().filter(r -> r.report() != null).toList();

        // ---------------- 基础信息 ----------------
        a.setVersion(version);
        a.setIssueKey(t.getKey());
        a.setSummary(t.getSummary());
        a.setDeveloper(blankTo(t.getAssignee(), ""));
        a.setAnalyst(blankTo(t.getQaAssignee(), ""));
        a.setDate(today);
        a.setProjectName(String.join("、", distinct(ok.stream().map(r -> nameOf(r.target())).toList())));
        List<String> modules = new ArrayList<>();
        for (Target x : t.getTargets()) {
            modules.add(nameOf(x) + "（" + kindLabel(x) + "）");
        }
        a.setModules(String.join("、", distinct(modules)));

        List<Item<EndpointChange>> eps = new ArrayList<>();
        List<Item<TableChange>> tables = new ArrayList<>();
        List<Item<OtherChange>> others = new ArrayList<>();
        List<Item<OtherChange>> trivial = new ArrayList<>();
        List<Item<OtherChange>> inBase = new ArrayList<>();
        List<String> mergedProjects = new ArrayList<>();
        List<Item<DtoChange>> orphans = new ArrayList<>();
        List<Item<DtoChange>> entityOrphans = new ArrayList<>();
        List<Item<ConfigPresence>> configs = new ArrayList<>();
        List<String> unused = new ArrayList<>();
        List<ApiRiskScanner.Finding> findings = new ArrayList<>();
        List<ConcurrencyRiskScanner.Risk> conc = new ArrayList<>();
        List<Item<MustTestGuide.Item>> must = new ArrayList<>();
        List<Item<MethodLogicDiff.Change>> logicCs = new ArrayList<>();
        for (ProjectReport pr : ok) {
            String pn = nameOf(pr.target());
            Report r = pr.report();
            r.getEndpoints().forEach(e -> eps.add(new Item<>(pn, e)));
            r.getLogicChanges().forEach(c -> logicCs.add(new Item<>(pn, c)));
            r.getTables().forEach(x -> tables.add(new Item<>(pn, x)));
            for (OtherChange o : r.getOtherChanges()) {
                // 「基线里已经一样」是没得比，不是比过了没变——不能进「无需回归」
                List<Item<OtherChange>> into = o.getTrivial() == null ? others
                        : ApiChangeAnalyzer.ALREADY_IN_BASE.equals(o.getTrivial()) ? inBase : trivial;
                into.add(new Item<>(pn, o));
            }
            if (r.isAlreadyInBase()) {
                mergedProjects.add(pn);
            }
            Set<String> addedFiles = new LinkedHashSet<>();
            r.getFiles().stream().filter(f -> "ADDED".equals(f.getStatus())).forEach(f -> addedFiles.add(f.getPath()));
            for (DtoChange d : r.getOrphanDtos()) {
                if (addedFiles.contains(d.getFile())) {
                    continue;
                }
                (isTableEntity(d) ? entityOrphans : orphans).add(new Item<>(pn, d));
            }
            r.getConfigPresence().forEach(c -> configs.add(new Item<>(pn, c)));
            unused.addAll(r.getUnusedDtos().stream().map(f -> pn + "：" + fileName(f)).toList());
            findings.addAll(r.getFindings());
            conc.addAll(r.getConcurrencyRisks());
            if (r.getMustTest() != null) {
                r.getMustTest().items().forEach(i -> must.add(new Item<>(pn, i)));
            }
            for (FileChange f : r.getFiles()) {
                FileRow row = new FileRow();
                row.setProject(pn);
                row.setPath(f.getPath());
                row.setStatus(f.getStatus());
                a.getFiles().add(row);
            }
        }
        List<Target> frontChanged = new ArrayList<>();
        for (Target x : t.getTargets()) {
            FrontChange fc = x.getFront();
            if (fc == null || fc.getChangedFiles() == 0) {
                continue;
            }
            frontChanged.add(x);
            for (FileRow f : fc.getFiles()) {
                FileRow row = new FileRow();
                row.setProject(frontLabel(x));
                row.setPath(f.getPath());
                row.setStatus(f.getStatus());
                a.getFiles().add(row);
            }
        }
        List<Link> links = t.getLinks() == null ? List.of() : t.getLinks();
        List<Link> riskLinks = links.stream().filter(l -> CrossProjectLinker.RISK.equals(l.getLevel())).toList();
        List<Link> jointLinks = links.stream().filter(l -> CrossProjectLinker.JOINT.equals(l.getLevel())).toList();
        boolean hasDdl = tables.stream().anyMatch(x -> x.v().isDdl());
        boolean ticketSql = hasText(t.getDbChanges());
        boolean ticketConfig = hasText(t.getConfigChanges());
        List<Target> fronts = t.getTargets().stream().filter(x -> ApiProjectKind.FRONTEND.equals(x.getKind())).toList();
        List<Target> clients = t.getTargets().stream().filter(x -> ApiProjectKind.CLIENT.equals(x.getKind())).toList();
        List<Target> unmatched = t.getTargets().stream().filter(x -> x.getProjectCode() == null).toList();
        List<Item<EndpointChange>> breaking = eps.stream().filter(x -> "BREAKING".equals(x.v().getRisk())).toList();
        List<Item<EndpointChange>> removed = eps.stream().filter(x -> "REMOVED".equals(x.v().getChangeType())).toList();
        List<Item<EndpointChange>> added = eps.stream().filter(x -> "ADDED".equals(x.v().getChangeType())).toList();
        List<Item<EndpointChange>> impl = eps.stream().filter(x -> "IMPL".equals(x.v().getRisk())).toList();
        List<Item<EndpointChange>> shape = eps.stream().filter(x -> !x.v().getParams().isEmpty()
                || !x.v().getFields().isEmpty() || !x.v().getBodyFields().isEmpty()).toList();
        List<Item<OtherChange>> unlinkedServices = others.stream()
                .filter(x -> x.v().getKind() != null && x.v().getKind().startsWith("SERVICE")).toList();
        List<EndpointDependencyResolver.RedisKeyRef> changedKeys = new ArrayList<>();
        eps.forEach(x -> x.v().getRedisKeys().stream().filter(k -> k != null && k.isValueChanged()).forEach(changedKeys::add));
        String summary = blankTo(t.getSummary(), "");

        // 变更类型
        a.getChangeTypes().add(new Check("新增功能", !added.isEmpty() || FEATURE.matcher(summary).find(),
                !added.isEmpty() ? "新增接口 " + added.size() + " 个" : FEATURE.matcher(summary).find() ? "单子标题写了新增" : "没有新增接口"));
        a.getChangeTypes().add(new Check("Bug 修复", BUGFIX.matcher(summary).find(),
                BUGFIX.matcher(summary).find() ? "单子标题像是修问题" : "标题不像修 Bug"));
        boolean onlyTrivial = eps.isEmpty() && others.isEmpty() && tables.isEmpty() && orphans.isEmpty() && !ok.isEmpty()
                && mergedProjects.isEmpty();
        a.getChangeTypes().add(new Check("代码重构", onlyTrivial || summary.contains("重构"),
                onlyTrivial ? "代码有改动但都不影响逻辑（挪位置 / 日志 / 注释）" : summary.contains("重构") ? "标题写了重构" : "有业务逻辑改动"));
        long changedMethods = logicCs.stream().filter(x -> "METHOD_CHANGED".equals(x.v().getKind())).count();
        boolean logic = !impl.isEmpty() || !unlinkedServices.isEmpty() || changedMethods > 0 || OPTIMIZE.matcher(summary).find();
        a.getChangeTypes().add(new Check("逻辑优化", logic,
                changedMethods > 0 ? changedMethods + " 个方法的逻辑改了"
                        : !impl.isEmpty() ? impl.size() + " 个接口签名没变但实现改了"
                        : !unlinkedServices.isEmpty() ? "业务实现类有改动" : logic ? "标题写了优化 / 调整" : "没看到实现改动"));
        a.getChangeTypes().add(new Check("数据库变更", hasDdl || ticketSql,
                hasDdl ? "代码里有 DDL" : ticketSql ? "JIRA 备注里有 SQL" : "没看到表结构变更"));
        a.getChangeTypes().add(new Check("接口调整", !shape.isEmpty() || !added.isEmpty() || !removed.isEmpty(),
                !shape.isEmpty() ? shape.size() + " 个接口入参 / 出参有变" : !added.isEmpty() || !removed.isEmpty()
                        ? "有接口新增或下线" : "接口签名没变"));

        // ---------------- 需求单 ↔ 代码 对账 ----------------
        a.getTicketChecks().addAll(TicketCodeCheck.check(t, reports, t.getCodeMentions()));

        // ---------------- 一、改动范围 ----------------
        Set<String> classes = new LinkedHashSet<>();
        for (FileRow f : a.getFiles()) {
            if (f.getPath().endsWith(".java") && !JavaApiSourceParser.isTestPath(f.getPath())) {
                classes.add(fileName(f.getPath()).replace(".java", ""));
            }
        }
        for (Item<MethodLogicDiff.Change> x : logicCs) {
            String k = x.v().getKind();
            if ("METHOD_CHANGED".equals(k) || "METHOD_ADDED".equals(k)) {
                classes.remove(x.v().getClassName());
                classes.add(x.v().getClassName() + "." + x.v().getMember() + "()" + ("METHOD_ADDED".equals(k) ? " 新增" : ""));
            }
        }
        a.getClasses().addAll(limit(new ArrayList<>(classes)));
        for (Target x : t.getTargets()) {
            boolean client = ApiProjectKind.CLIENT.equals(x.getKind());
            List<String> into = client ? a.getClients() : a.getFrontends();
            FrontChange fc = x.getFront();
            if (fc != null && fc.getChangedFiles() > 0) {
                List<String> pages = fc.getPages().stream().map(TestAssessmentBuilder::shortPath).limit(8).toList();
                into.add(frontLabel(x) + "：" + (pages.isEmpty() ? FrontDiffScanner.summary(fc) : String.join("、", pages)
                        + (fc.getPages().size() > pages.size() ? " 等 " + fc.getPages().size() + " 个" : "")));
            } else if (fronts.contains(x) || clients.contains(x)) {
                into.add(nameOf(x) + " · " + x.getBranch() + (x.getCodeMissing() != null ? "（缺代码，没对比）" : ""));
            }
        }
        for (Item<EndpointChange> x : eps) {
            EndpointChange e = x.v();
            a.getEndpoints().add(sig(e) + summaryOf(e) + "（" + x.project() + "，" + changeWord(e) + "）");
        }
        for (Item<TableChange> x : tables) {
            a.getTables().add(x.v().getTable() + (x.v().isDdl() ? "（结构有变）" : "（读写 SQL 有改动）"));
        }

        // ---------------- 二、变更内容详情 ----------------
        for (Item<EndpointChange> x : impl) {
            a.getLogicChanges().add(sig(x.v()) + "：实现改了（" + String.join("、", x.v().getChangedImpls()) + "）");
        }
        for (Item<OtherChange> x : others) {
            a.getLogicChanges().add(x.project() + " " + x.v().getFileName() + "：" + x.v().getHint());
        }
        for (Item<EndpointChange> x : eps) {
            a.getApiChanges().add(diffOf(x.project(), x.v()));
        }
        for (Item<TableChange> x : tables) {
            TableChange tc = x.v();
            if (tc.getDdlDetails() != null && !tc.getDdlDetails().isEmpty()) {
                for (DdlChange d : tc.getDdlDetails()) {
                    a.getDbChanges().add(tc.getTable() + "：" + nz(d.action()) + " " + nz(d.column()) + " " + nz(d.detail()));
                }
            } else {
                a.getDbChanges().add(tc.getTable() + (tc.isDdl() ? "：有 DDL" : "：SQL 有改动，结构没变"));
            }
        }
        if (ticketSql) {
            a.setSql(t.getDbChanges().trim());
        }
        for (EndpointDependencyResolver.RedisKeyRef k : distinctKeys(changedKeys)) {
            a.getOtherChanges().add("缓存 key " + nz(k.getConstant()) + "：" + nz(k.getOldValue()) + " → " + nz(k.getValue()));
        }
        for (Item<OtherChange> x : others) {
            String kind = nz(x.v().getKind());
            if (kind.equals("CACHE") || kind.equals("CONFIG") || kind.equals("SCHEDULE") || kind.equals("SERVICE_SCHEDULED")) {
                a.getOtherChanges().add(kindWord(kind) + "：" + x.project() + " " + x.v().getFileName());
            }
        }
        if (ticketConfig) {
            a.getOtherChanges().add("JIRA 备注里的配置：" + oneLine(t.getConfigChanges()));
        }
        for (FileRow f : a.getFiles()) {
            if (JavaApiSourceParser.isTestPath(f.getPath()) || !f.getPath().endsWith(".java")) {
                continue;
            }
            String n = fileName(f.getPath());
            if (MQ_PATH.matcher(n).find()) {
                a.getOtherChanges().add("消息队列相关：" + f.getProject() + " " + n);
            } else if (REMOTE_PATH.matcher(n).find()) {
                a.getOtherChanges().add("第三方 / 远程调用相关：" + f.getProject() + " " + n);
            }
        }

        // ---------------- 三、依赖与关联模块 ----------------
        List<String> publicItems = new ArrayList<>();
        orphans.forEach(x -> publicItems.add("公共返回体 " + x.v().getType() + "（" + x.project() + "）"));
        unlinkedServices.forEach(x -> publicItems.add("业务实现 " + x.v().getFileName() + "（" + x.project() + "，没连到具体接口）"));
        for (FileRow f : a.getFiles()) {
            if (PUBLIC_PATH.matcher(f.getPath()).find() && !JavaApiSourceParser.isTestPath(f.getPath())) {
                publicItems.add("公共目录下的 " + fileName(f.getPath()) + "（" + f.getProject() + "）");
            }
        }
        a.getPublicItems().addAll(limit(distinct(publicItems)));
        a.setPublicChange(new Check("涉及公共方法 / 公共组件", !publicItems.isEmpty(),
                publicItems.isEmpty() ? "改动都能挂到具体接口上" : "调用这些的业务模块都要考虑回归"));
        Set<String> related = new LinkedHashSet<>();
        eps.forEach(x -> related.addAll(x.v().getUsedServices()));
        a.getRelatedModules().addAll(limit(new ArrayList<>(related)));
        for (Target x : t.getTargets()) {
            if (!x.isAnalyzable() && !frontChanged.contains(x)) {
                a.getRelatedModules().add("同单还改了 " + nameOf(x) + "（" + kindLabel(x) + "，" + x.getBranch() + "）");
            }
        }
        for (List<Link> g : groupLinks(links, l -> l.getFromNode() != null && l.getToNode() != null)) {
            Link l = g.get(0);
            a.getRelatedModules().add(l.getFromName() + " → " + l.getToName() + "：" + groupTargets(g) + "（" + l.getTitle() + "）");
        }
        for (Item<EndpointChange> x : eps) {
            for (FieldChange f : x.v().getFields()) {
                if ("REMOVED".equals(f.getChangeType())) {
                    a.getCompatNotes().add(sig(x.v()) + " 返回少了 " + f.getName() + "：老数据 / 老客户端解析要验");
                } else if ("TYPE_CHANGED".equals(f.getChangeType())) {
                    a.getCompatNotes().add(sig(x.v()) + " 返回 " + f.getName() + " " + f.getOldType() + " → " + f.getNewType()
                            + (f.getWireNote() == null ? "" : "：" + f.getWireNote()));
                }
            }
        }
        if (!changedKeys.isEmpty()) {
            a.getCompatNotes().add("缓存 key 变了：上线后新老缓存并存，老数据可能读不到或读到旧格式");
        }
        if (hasDdl) {
            a.getCompatNotes().add("表结构有变：历史数据的新字段是默认值 / NULL，接口要能处理");
        }
        boolean migration = hasDdl || (ticketSql && Pattern.compile("(?i)\\b(insert|update|delete|alter|create)\\b")
                .matcher(t.getDbChanges()).find());
        a.setNeedMigration(new Check("需要数据迁移或初始化刷数脚本", migration,
                migration ? (ticketSql ? "JIRA 备注里有 SQL" : "代码里有 DDL") : "没看到 SQL / DDL"));
        if (ticketSql) {
            a.getMigrationNotes().add("备注里的 SQL 先在测试库执行，确认执行顺序（先 DDL 再发代码）");
        }
        tables.stream().filter(x -> x.v().isDdl()).forEach(x -> a.getMigrationNotes().add("表 " + x.v().getTable() + " 结构有变"));

        // ---------------- 四、风险点 ----------------
        for (ConcurrencyRiskScanner.Risk r : conc) {
            a.getLogicRisks().add(r.httpMethod() + " " + r.path() + "：" + r.title()
                    + (r.howToTest() == null ? "" : "（" + r.howToTest() + "）"));
        }
        for (Item<EndpointChange> x : eps) {
            for (ParamChange p : x.v().getParams()) {
                if ("ADDED".equals(p.getChangeType())) {
                    a.getLogicRisks().add(sig(x.v()) + " 新入参 " + p.getName() + (p.isRequired() ? "（必填）" : "（可选）")
                            + "：空值、缺参、边界值");
                }
            }
        }
        findings.stream().filter(f -> !"SECURITY".equals(f.kind()))
                .forEach(f -> a.getLogicRisks().add("设计：" + f.title() + "（" + nz(f.target()) + "）"));
        CompatCheck compat = t.getCompat();
        a.setCompat(compat);
        List<CompatHit> compatRisk = compat == null ? List.of()
                : compat.getHits().stream().filter(h -> "RISK".equals(h.getLevel())).toList();
        if (compat == null) {
            breaking.forEach(x -> a.getCompatRisks().add(sig(x.v()) + " 不兼容变更，旧版本 APP 会受影响"));
        }
        for (CompatHit h : compatRisk) {
            a.getCompatRisks().add(compatWho(h) + " 调 " + h.getEndpoint() + "：" + h.getProblem()
                    + ("FOUND".equals(h.getFieldFound()) ? "（代码里在读，" + String.join("、", h.getFieldRefs()) + "）" : ""));
        }
        removed.forEach(x -> a.getCompatRisks().add(sig(x.v()) + " 下线了，老包还在调会直接失败"));
        if (!changedKeys.isEmpty()) {
            a.getCompatRisks().add("缓存 key 变了，有脏读 / 读不到的风险");
        }
        if (!clients.isEmpty()) {
            a.getCompatRisks().add("同单有客户端改动（" + String.join("、", clients.stream().map(TestAssessmentBuilder::nameOf).toList())
                    + "），新接口要兼容线上老版本 APP");
        }
        riskLinks.forEach(l -> a.getCompatRisks().add(l.getFromName() + " → " + l.getToName() + " " + linkTarget(l) + "：" + l.getTitle()));
        orphans.forEach(x -> a.getHiddenRisks().add("公共返回体 " + x.v().getType() + " 改了，用到它的接口都会受影响"));
        entityOrphans.forEach(x -> a.getHiddenRisks().add("表实体 " + x.v().getType() + " 字段有变（"
                + String.join("、", x.v().getFields().stream().map(f -> f.getName()).toList())
                + "）：对应的表要有这些列，读写这张表的地方都受影响"));
        unlinkedServices.forEach(x -> a.getHiddenRisks().add(x.v().getFileName() + " 改了但没连到接口，可能被别处调用"));
        others.stream().filter(x -> "MAPPER".equals(x.v().getKind()))
                .forEach(x -> a.getHiddenRisks().add("数据访问 " + x.v().getFileName() + " 改了，查询条件或字段映射可能变"));
        findings.stream().filter(f -> "SECURITY".equals(f.kind()))
                .forEach(f -> a.getOtherRisks().add("安全：" + f.title() + "（" + nz(f.target()) + "）"));
        if (ticketConfig || others.stream().anyMatch(x -> "CONFIG".equals(x.v().getKind()))) {
            a.getOtherRisks().add("有配置改动，确认测试环境配置已同步");
        }
        for (ProjectReport pr : reports == null ? List.<ProjectReport>of() : reports) {
            Target x = pr.target();
            if (x.getSyncMessage() != null && x.getSyncMessage().startsWith("拉取最新代码失败")) {
                a.getOtherRisks().add(nameOf(x) + "：" + x.getSyncMessage());
            }
            if (x.getNote() != null && x.getNote().contains("顺序")) {
                a.getOtherRisks().add(nameOf(x) + "：" + x.getNote());
            }
        }

        // ---------------- 五、测试建议 ----------------
        for (Item<MustTestGuide.Item> x : must) {
            MustTestGuide.Item it = x.v();
            if (!"ENDPOINT".equals(it.kind())) {
                if ("TABLE".equals(it.kind())) {
                    it.notes().forEach(n -> a.getBoundaryCases().add(it.title() + "：" + n));
                }
                continue;
            }
            String head = it.httpMethod() + " " + it.path() + "：";
            boolean normal = false;
            for (String n : it.notes()) {
                switch (bucket(n)) {
                    case "normal" -> {
                        a.getNormalCases().add(head + n);
                        normal = true;
                    }
                    case "boundary" -> a.getBoundaryCases().add(head + n);
                    case "error" -> a.getErrorCases().add(head + n);
                    default -> {
                    }
                }
            }
            if (!normal && !"确认下线".equals(it.title())) {
                a.getNormalCases().add(head + "真实入参调一次，断言业务码成功和关键字段");
            }
            a.getMustRegress().add("【" + it.priority() + "】" + it.httpMethod() + " " + it.path() + " — " + it.title());
        }
        for (CompatHit h : compatRisk) {
            a.getMustRegress().add(0, "【老版本兼容】" + compatWho(h) + " → " + h.getEndpoint() + "（" + h.getProblem() + "）");
        }
        for (Link l : jointLinks) {
            a.getMustRegress().add(0, "【联调】" + l.getFromName() + " → " + l.getToName() + " " + linkTarget(l));
            a.getNormalCases().add(l.getFromName() + " 页面上走一遍 " + linkTarget(l) + "：请求参数和页面展示都对得上后端");
        }
        for (List<Link> g : groupLinks(links, l -> CrossProjectLinker.INFO.equals(l.getLevel())
                && l.getFromNode() != null && l.getMethod() != null)) {
            Link l = g.get(0);
            a.getRelatedRegress().add(l.getFromName() + " 调 " + groupTargets(g) + " 的页面（" + l.getTitle() + "）");
        }
        if (!eps.isEmpty()) {
            a.getErrorCases().add("缺必填 / 类型错误 / 未登录：错误码和文案要能看懂");
        }
        Set<String> changedSigs = new LinkedHashSet<>();
        eps.forEach(x -> changedSigs.add(sig(x.v())));
        for (Item<TableChange> x : tables) {
            for (String u : x.v().getUsedByEndpoints()) {
                if (!changedSigs.contains(u.trim())) {
                    a.getRelatedRegress().add(u.trim() + "（读写表 " + x.v().getTable() + "）");
                }
            }
        }
        fronts.forEach(x -> a.getRelatedRegress().add("前端 " + nameOf(x) + " 对应页面（" + x.getBranch() + "）"));
        clients.forEach(x -> a.getRelatedRegress().add("客户端 " + nameOf(x) + " 对应功能（" + x.getBranch() + "）"));
        trivial.forEach(x -> a.getNoRegress().add(x.project() + " " + x.v().getFileName() + "：" + x.v().getTrivial()));
        unused.forEach(u -> a.getNoRegress().add(u + "：没被任何代码引用"));
        a.setRelatedRegress(limit(distinct(a.getRelatedRegress())));

        boolean diff = eps.stream().anyMatch(x -> !x.v().getFields().isEmpty() || "IMPL".equals(x.v().getRisk()));
        boolean perf = PERF.matcher(summary).find();
        a.getMethods().add(new Check("造数据测试", !tables.isEmpty() || ticketSql,
                !tables.isEmpty() ? "涉及表 " + tables.size() + " 张" : ticketSql ? "备注里有 SQL" : "没涉及表"));
        a.getMethods().add(new Check("接口 Diff 测试", diff,
                diff ? "返回字段变了或实现变了，用老请求对比新老返回" : "返回结构和实现都没变"));
        a.getMethods().add(new Check("并发测试", !conc.isEmpty(),
                conc.isEmpty() ? "没扫到并发写风险" : conc.size() + " 处并发风险"));
        a.getMethods().add(new Check("兼容性测试", !breaking.isEmpty() || !removed.isEmpty() || !clients.isEmpty() || !compatRisk.isEmpty(),
                !compatRisk.isEmpty() ? compatRisk.size() + " 处线上版本的前端 / 客户端会受影响"
                        : !breaking.isEmpty() || !removed.isEmpty() ? "有不兼容 / 下线的接口" : !clients.isEmpty() ? "同单有客户端改动" : "没有不兼容变更"));
        a.getMethods().add(new Check("数据库/缓存校验", !tables.isEmpty() || eps.stream().anyMatch(x -> !x.v().getRedisKeys().isEmpty()),
                !tables.isEmpty() ? "要查表确认落库" : "接口走缓存，结果要和库对一下"));
        a.getMethods().add(new Check("性能测试", perf, perf ? "标题提到性能 / 耗时" : "没看到性能相关诉求"));
        for (Item<TableChange> x : tables) {
            for (TableTestHints.Hint h : x.v().getTestHints()) {
                a.getTestData().add("表 " + x.v().getTable() + "：" + h.title());
            }
            if (x.v().getTestHints().isEmpty()) {
                a.getTestData().add("表 " + x.v().getTable() + "：准备正常 / 边界 / 空数据各一份");
            }
        }
        for (Item<EndpointChange> x : eps) {
            for (ParamChange p : x.v().getParams()) {
                if ("ADDED".equals(p.getChangeType())) {
                    a.getTestData().add(sig(x.v()) + " 新入参 " + p.getName() + " 的有效值、空值、非法值");
                }
            }
        }
        if (ticketSql) {
            a.getTestData().add("先执行 JIRA 备注里的 SQL");
        }
        a.setTestData(limit(distinct(a.getTestData())));
        for (Item<EndpointChange> x : eps) {
            EndpointChange e = x.v();
            if ("REMOVED".equals(e.getChangeType())) {
                if (e.getTestCases() > 0) {
                    a.getAutomationRefs().add(sig(e) + "：已有 " + e.getTestCases() + " 条用例，接口下线后要停用");
                }
            } else if (e.getTestCases() > 0) {
                if (!e.getFields().isEmpty() || !e.getParams().isEmpty() || !e.getBodyFields().isEmpty()) {
                    a.getAutomationRefs().add(sig(e) + "：已有 " + e.getTestCases() + " 条用例，按新入参 / 新字段更新断言");
                }
            } else {
                a.getAutomationRefs().add(sig(e) + "：还没有用例，要新增（放进分支用例集合 " + t.getKey() + "）");
            }
        }
        a.setAutomation(new Check("需要更新自动化脚本", !a.getAutomationRefs().isEmpty(),
                a.getAutomationRefs().isEmpty() ? "改动接口的已有用例不受影响" : a.getAutomationRefs().size() + " 个接口要补 / 改用例"));
        for (Item<EndpointChange> x : eps) {
            if (!x.v().getFields().isEmpty() || "IMPL".equals(x.v().getRisk())) {
                a.getBaselineRefs().add(sig(x.v()) + ("IMPL".equals(x.v().getRisk()) ? "：实现变了，返回值要重新留基线" : "：返回字段变了"));
            }
        }
        a.setBaseline(new Check("需要新增 / 更新 Diff 测试基线", !a.getBaselineRefs().isEmpty(),
                a.getBaselineRefs().isEmpty() ? "返回结构没变" : a.getBaselineRefs().size() + " 个接口"));

        // ---------------- 方法级逻辑改动：分进变更详情 / 风险 / 测试场景 / 待确认 ----------------
        for (Item<MethodLogicDiff.Change> x : logicCs) {
            MethodLogicDiff.Change c = x.v();
            a.getLogicDetails().add(logicItem(x.project(), c));
            String where = where(x);
            String what = c.getAfter() != null ? c.getAfter() : c.getBefore();
            switch (c.getKind()) {
                case "METHOD_CHANGED" -> {
                    if (c.getHint() != null && c.getHint().contains("没认出")) {
                        a.getQuestions().add(where + " " + c.getTitle() + "，没认出是哪类改动：对照二.1 里的变更行，找开发问清楚新旧行为差在哪");
                    }
                }
                case "CONDITION" -> {
                    if (c.getAfter() == null) {
                        a.getLogicRisks().add(where + " 去掉了判断「" + c.getBefore() + "」");
                        a.getQuestions().add(where + " 去掉了判断「" + c.getBefore() + "」：是需求要求的吗？原来被它拦住的数据现在怎么处理");
                    }
                    a.getBoundaryCases().add(where + "：" + c.getHint());
                }
                case "FORMULA" -> {
                    a.getLogicRisks().add(where + " 计算方式改了：已有数据按新公式算，排序 / 展示可能跳变");
                    a.getNormalCases().add(where + "：" + c.getHint());
                }
                case "CALC" -> a.getNormalCases().add(where + " " + c.getTitle() + "：" + c.getHint());
                case "LITERAL" -> a.getBoundaryCases().add(where + "：数值 " + nz(c.getBefore()) + " → " + nz(c.getAfter()) + "，" + c.getHint());
                case "EXCEPTION" -> a.getErrorCases().add(where + "：" + c.getTitle() + " " + oneLine(what) + "——" + c.getHint());
                case "WRITE" -> a.getNormalCases().add(where + "：" + c.getTitle() + " " + oneLine(what) + "——" + c.getHint());
                case "CACHE" -> {
                    a.getHiddenRisks().add(where + " " + c.getTitle() + (what == null ? "" : "：" + oneLine(what)));
                    a.getNormalCases().add(where + "：" + c.getHint());
                }
                case "MQ", "REMOTE" -> {
                    a.getHiddenRisks().add(where + " " + c.getTitle() + "：" + oneLine(what));
                    a.getErrorCases().add(where + "：" + c.getHint());
                }
                case "TRANSACTION", "ASYNC" -> {
                    a.getLogicRisks().add(where + " " + c.getTitle());
                    a.getErrorCases().add(where + "：" + c.getHint());
                }
                case "SCHEDULE" -> {
                    a.getHiddenRisks().add(where + " " + c.getTitle() + (c.getAfter() == null ? "" : "：" + c.getAfter()));
                    a.getNormalCases().add(where + "：" + c.getHint());
                }
                case "CONFIG" -> {
                    a.getOtherRisks().add(c.getTitle() + "（" + x.project() + " " + c.getClassName() + "）");
                    a.getTestData().add(c.getTitle() + "：" + c.getHint());
                }
                case "CONSTANT" -> a.getCompatRisks().add(where + " " + c.getTitle() + "：" + nz(c.getBefore()) + " → " + nz(c.getAfter())
                        + "（" + c.getHint() + "）");
                case "METHOD_ADDED" -> a.getNormalCases().add(where + "：" + c.getHint());
                case "METHOD_REMOVED" -> a.getHiddenRisks().add(where + " " + c.getTitle() + "：" + c.getHint());
                default -> {
                }
            }
        }
        a.setTestData(limit(distinct(a.getTestData())));
        a.setNormalCases(limit(distinct(a.getNormalCases())));
        a.setBoundaryCases(limit(distinct(a.getBoundaryCases())));
        a.setErrorCases(limit(distinct(a.getErrorCases())));

        // ---------------- 六、待确认 ----------------
        for (TicketCodeCheck.Item ck : a.getTicketChecks()) {
            if (TicketCodeCheck.CODE_ONLY.equals(ck.status())) {
                a.getQuestions().add("【单子没写 · " + ck.topic() + "】" + oneLine(ck.code()) + "——" + ck.hint());
            } else if (TicketCodeCheck.TICKET_ONLY.equals(ck.status())) {
                a.getQuestions().add("【代码没看到 · " + ck.topic() + "】" + oneLine(ck.ticket()) + "——" + ck.hint());
            }
        }
        unmatched.forEach(x -> a.getQuestions().add("备注里的项目「" + x.getRawProject() + "」在项目管理里没找到，确认是哪个仓库（分支 "
                + x.getBranch() + "）"));
        t.getTargets().stream().filter(x -> "FUZZY".equals(x.getMatchHow()))
                .forEach(x -> a.getQuestions().add("「" + x.getRawProject() + "」按 " + nameOf(x) + " 处理了，确认没认错"));
        Set<String> asked = new LinkedHashSet<>();
        for (CompatHit h : compatRisk) {
            if (asked.add(h.getEndpoint() + "|" + h.getProblem())) {
                List<String> who = distinct(compatRisk.stream().filter(o -> o.getEndpoint().equals(h.getEndpoint())
                        && o.getProblem().equals(h.getProblem())).map(TestAssessmentBuilder::compatWho).toList());
                a.getQuestions().add(h.getEndpoint() + " " + h.getProblem() + "：" + String.join("、", who)
                        + " 还在用。后端保留老接口 / 老字段到老版本下线，还是强更？");
            }
        }
        if (compat == null) {
            breaking.forEach(x -> a.getQuestions().add(sig(x.v()) + " 的不兼容改动是否有意，线上老版本 APP 怎么处理"));
        }
        removed.forEach(x -> a.getQuestions().add(sig(x.v()) + " 下线是否确认，还有没有调用方"));
        if (ticketSql) {
            a.getQuestions().add("备注里的 SQL 在哪个环境、什么时候执行，谁来执行");
        }
        if (t.getTargets().stream().noneMatch(Target::isAnalyzable)) {
            a.getQuestions().add("没认出后端分支：确认这一单是不是只改了前端 / 客户端");
        }
        for (String pn : mergedProjects) {
            a.getQuestions().add(pn + " 的分支改动已经全部在基线里了：确认这一单是不是已经上线；"
                    + "还没测的话，把基线换成合并前的提交或上一次发版的 tag 重新分析");
        }
        for (ProjectReport pr : reports == null ? List.<ProjectReport>of() : reports) {
            if (pr.target().getError() != null) {
                a.getQuestions().add(nameOf(pr.target()) + " 没分析成功：" + pr.target().getError());
            }
        }
        links.stream().filter(l -> CrossProjectLinker.CHECK.equals(l.getLevel()))
                .forEach(l -> a.getQuestions().add(l.getFromName() + " → " + l.getToName()
                        + (l.getPath() == null ? "" : " " + linkTarget(l)) + "：" + l.getTitle()));
        boolean anyFront = t.getTargets().stream().anyMatch(x -> x.getFront() != null);
        a.getLimits().add(anyFront
                ? "前端 / 客户端只认了改动文件、页面和代码里写死的接口路径；拼接出来的地址认不出，页面逻辑和样式要人看"
                : "只对比了后端仓库；前端、客户端改动没做代码对比");
        t.getTargets().stream().filter(x -> x.getCodeMissing() != null && x.getProjectCode() != null)
                .forEach(x -> a.getLimits().add(nameOf(x) + " 缺代码（" + x.getCodeMissing() + "），和它有关的关联只是推测"));
        if (!inBase.isEmpty()) {
            a.getLimits().add(inBase.size() + " 个文件在基线里已经一模一样，没参与对比（多半已合进基线）："
                    + String.join("、", limit(distinct(inBase.stream()
                    .map(x -> x.project() + " " + x.v().getFileName()).toList()))));
        }
        if (compat != null) {
            a.getLimits().add("老版本兼容：拿各前端 / 客户端仓库的 master 当线上版本，只认代码里写死的接口路径，拼出来的地址认不出"
                    + (compat.getChecked().isEmpty() ? "" : "；查过 " + String.join("、", compat.getChecked())));
            compat.getUnchecked().forEach(u -> a.getLimits().add("老版本兼容没检查：" + u));
            if (compat.getTruncated() > 0) {
                a.getLimits().add("老版本兼容还有 " + compat.getTruncated() + " 处没列出来");
            }
        }
        a.getLimits().add("状态码：代码里认不出状态码变化，以实际返回为准");
        a.getLimits().add("旧逻辑 / 新逻辑需要对照需求文档人工补充");
        for (ProjectReport pr : ok) {
            if (hasText(pr.report().getSchemaNote())) {
                a.getLimits().add(nameOf(pr.target()) + "：" + pr.report().getSchemaNote());
            }
        }

        // ---------------- 全栈影响概览 / 发版顺序 / 按端分组 ----------------
        AssessmentViews.Facts facts = new AssessmentViews.Facts(t, !ok.isEmpty(), eps, tables, others, logicCs, must, links,
                frontChanged, findings, conc, distinctKeys(changedKeys), a.getTicketChecks(), compat, hasDdl, ticketSql, configs);
        a.setOverview(AssessmentViews.overview(facts));
        a.setReleasePlan(AssessmentViews.releasePlan(facts));
        a.setReleaseOrder(AssessmentViews.releaseOrder(a.getReleasePlan()));
        a.setEndGroups(AssessmentViews.endGroups(facts));

        // ---------------- 测试指引 ----------------
        GuideViews.linkLogic(a.getLogicDetails(), eps);
        a.setEndpointCards(GuideViews.endpointCards(facts, a.getApiChanges(), a.getLogicDetails()));
        a.setImpacts(GuideViews.impacts(facts, a, t.getTargets().stream().anyMatch(Target::isAnalyzable)));
        a.setGuideVersion(GuideViews.VERSION);

        // ---------------- 七、结论 ----------------
        List<String> high = new ArrayList<>();
        long oldApp = compatRisk.stream().filter(h -> ApiProjectKind.CLIENT.equals(h.getCallerKind())).count();
        if (oldApp > 0) {
            high.add(oldApp + " 处线上老版本 App 会受影响");
        }
        long outsideH5 = compatRisk.size() - oldApp;
        if (outsideH5 > 0) {
            high.add(outsideH5 + " 处不在单里的 H5 会受影响");
        }
        if (!breaking.isEmpty()) {
            high.add(breaking.size() + " 个接口不兼容");
        }
        if (!removed.isEmpty()) {
            high.add(removed.size() + " 个接口下线");
        }
        if (migration) {
            high.add("有数据库变更 / 刷数");
        }
        List<String> breakingOrphans = orphans.stream().filter(x -> x.v().getFields().stream().anyMatch(TestAssessmentBuilder::breaksWire))
                .map(x -> x.v().getType()).distinct().toList();
        if (!breakingOrphans.isEmpty()) {
            high.add("公共返回体 " + String.join("、", breakingOrphans) + " 删了字段 / 改了类型");
        }
        List<String> noSqlCols = facts.undeclaredColumns().stream().map(c -> c.table() + "." + c.column()).distinct().toList();
        if (!noSqlCols.isEmpty()) {
            high.add("代码要用 " + noSqlCols.size() + " 个新字段（" + String.join("、", noSqlCols) + "），没找到加字段的 SQL");
        }
        if (conc.stream().anyMatch(r -> "RISK".equals(r.level()))) {
            high.add("有并发写风险");
        }
        if (eps.size() >= 8) {
            high.add("改动接口多达 " + eps.size() + " 个");
        }
        if (!riskLinks.isEmpty()) {
            high.add(riskLinks.size() + " 处跨项目对不上（调用方没跟着改 / 还在调已下线的接口）");
        }
        long undeclared = a.getTicketChecks().stream().filter(c -> TicketCodeCheck.CODE_ONLY.equals(c.status())).count();
        if (undeclared > 0) {
            high.add(undeclared + " 处代码改动单子里没写（配置 / 表结构 / 定时任务），上线容易漏配");
        }
        if (logicCs.stream().anyMatch(x -> "TRANSACTION".equals(x.v().getKind()) && x.v().getTitle().contains("去掉"))) {
            high.add("有方法去掉了事务");
        }
        if (ok.isEmpty() && !frontChanged.isEmpty()) {
            a.setRiskLevel(high.isEmpty() ? "MEDIUM" : "HIGH");
            a.getRiskReasons().addAll(high);
            a.getRiskReasons().add("只有前端 / 客户端改动（" + String.join("、", frontChanged.stream().map(TestAssessmentBuilder::frontLabel).toList()) + "）");
            a.setConclusion((high.isEmpty() ? "中风险：" : "高风险：") + String.join("、", a.getRiskReasons()) + "。回归改到的页面。");
        } else if (ok.isEmpty()) {
            a.setRiskLevel(null);
            a.getRiskReasons().add(t.getTargets().stream().anyMatch(Target::isAnalyzable)
                    ? "后端还没分析，先点分析" : "这一单没有可分析的后端改动");
            a.setConclusion(a.getRiskReasons().get(0));
        } else if (!high.isEmpty()) {
            a.setRiskLevel("HIGH");
            a.getRiskReasons().addAll(high);
            a.setConclusion("高风险：" + String.join("、", high) + "。需要完整回归 + 联调。");
        } else if (!eps.isEmpty() || !others.isEmpty() || !tables.isEmpty() || !jointLinks.isEmpty() || !frontChanged.isEmpty()
                || !logicCs.isEmpty() || !orphans.isEmpty() || !entityOrphans.isEmpty()) {
            a.setRiskLevel("MEDIUM");
            if (!orphans.isEmpty()) {
                a.getRiskReasons().add("公共返回体 " + String.join("、", orphans.stream().map(x -> x.v().getType()).distinct().toList())
                        + " 只加了字段");
            }
            if (changedMethods > 0) {
                a.getRiskReasons().add(changedMethods + " 个方法逻辑改了");
            }
            if (!jointLinks.isEmpty()) {
                a.getRiskReasons().add(jointLinks.size() + " 个跨项目联调点");
            }
            if (!eps.isEmpty()) {
                a.getRiskReasons().add(eps.size() + " 个接口有改动");
            }
            if (!frontChanged.isEmpty()) {
                a.getRiskReasons().add("前端 / 客户端有改动（" + String.join("、", frontChanged.stream().map(TestAssessmentBuilder::frontLabel).toList()) + "）");
            }
            if (!others.isEmpty()) {
                a.getRiskReasons().add(others.size() + " 处非接口改动（定时任务 / 实现 / 配置等）");
            }
            if (!tables.isEmpty()) {
                a.getRiskReasons().add("读写 " + tables.size() + " 张表");
            }
            a.setConclusion("中风险：局部业务改动（" + String.join("、", a.getRiskReasons()) + "），重点回归 + 关联模块回归。");
        } else if (!mergedProjects.isEmpty()) {
            // 不给风险等级：什么都没比出来，给「低风险」等于替没做的分析背书
            a.setRiskLevel(null);
            a.getRiskReasons().add(String.join("、", mergedProjects) + " 的分支改动已经合进基线，比不出差异");
            a.setConclusion("没比出差异：" + String.join("、", mergedProjects)
                    + " 这个分支改的代码，基线（master）里已经一模一样，多半已经合并上线。"
                    + "这不代表不用测——如果这一单还没测，把基线换成合并前的提交或上一次发版的 tag 重新分析。");
        } else {
            a.setRiskLevel("LOW");
            a.getRiskReasons().add(trivial.isEmpty() && unused.isEmpty() ? "后端没有改动" : "只有挪位置 / 日志 / 注释 / 没被引用的类");
            a.setConclusion("低风险：业务逻辑没有变化，主流程冒烟即可。");
        }
        return a;
    }

    /** 必测清单里的一句注意事项归到正常 / 边界 / 异常哪一类；用例数这类不进场景 */
    static String bucket(String note) {
        if (note == null) {
            return "";
        }
        if (note.startsWith("故意不传") || note.startsWith("入参 ") && note.contains("删了")
                || note.startsWith("请求体") || note.startsWith("返回少了") || note.startsWith("确认是有意下线")
                || note.startsWith("返回字段 ")) {
            return "error";
        }
        if (note.startsWith("新入参") || note.startsWith("返回多了") || note.startsWith("并发")
                || note.startsWith("读写表") || note.startsWith("缓存 key") || note.startsWith("走缓存")
                || note.startsWith("入参 ")) {
            return "boundary";
        }
        if (note.startsWith("用线上同等请求") || note.startsWith("带上新参数") || note.startsWith("抽查老包")) {
            return "normal";
        }
        return "";
    }

    private static EndpointDiff diffOf(String project, EndpointChange e) {
        EndpointDiff d = new EndpointDiff();
        d.setProject(project);
        d.setMethod(e.getHttpMethod());
        d.setPath(e.getPath());
        d.setSummary(e.getSummary());
        d.setChangeType(e.getChangeType());
        d.setRisk(e.getRisk());
        d.setTestCases(e.getTestCases());
        for (ParamChange p : e.getParams()) {
            d.getInputs().add(switch (nz(p.getChangeType())) {
                case "ADDED" -> "新增入参 " + p.getName() + "（" + nz(p.getNewType()) + (p.isRequired() ? "，必填" : "，可选") + "）";
                case "REMOVED" -> "删除入参 " + p.getName();
                case "TYPE_CHANGED" -> "入参 " + p.getName() + " 类型 " + p.getOldType() + " → " + p.getNewType();
                case "REQUIRED_CHANGED" -> "入参 " + p.getName() + " 改成" + (p.isRequired() ? "必填" : "可选");
                default -> "入参 " + p.getName() + " 有变化";
            });
        }
        for (FieldChange f : e.getBodyFields()) {
            d.getInputs().add("请求体 " + fieldWord(f));
        }
        for (FieldChange f : e.getFields()) {
            d.getOutputs().add(fieldWord(f));
        }
        d.getImpls().addAll(e.getChangedImpls());
        return d;
    }

    private static String fieldWord(FieldChange f) {
        String c = f.getComment() == null || f.getComment().isBlank() ? "" : "（" + f.getComment() + "）";
        return switch (nz(f.getChangeType())) {
            case "ADDED" -> "新增 " + f.getName() + c + "：" + nz(f.getNewType());
            case "REMOVED" -> "删除 " + f.getName() + c;
            case "TYPE_CHANGED" -> f.getName() + c + " 类型 " + f.getOldType() + " → " + f.getNewType()
                    + (f.getWireNote() == null ? "" : "（" + f.getWireNote() + "）");
            default -> f.getName() + c + " 有变化";
        };
    }

    record Item<T>(String project, T v) {
    }

    /** 「GET /x 等 3 个接口（CommonServiceImpl.calc()）」；连不上接口时退回「项目 类.方法()」 */
    static String where(Item<MethodLogicDiff.Change> x) {
        MethodLogicDiff.Change c = x.v();
        boolean field = "CONFIG".equals(c.getKind()) || "CONSTANT".equals(c.getKind());
        String m = c.getClassName() + "." + c.getMember() + (field ? "" : "()");
        List<String> eps = c.getEndpoints();
        if (eps != null && !eps.isEmpty()) {
            return eps.get(0) + (eps.size() > 1 ? " 等 " + eps.size() + " 个接口" : "") + "（" + m + "）";
        }
        return x.project() + " " + m;
    }

    private static ReleaseBatchDtos.LogicItem logicItem(String project, MethodLogicDiff.Change c) {
        ReleaseBatchDtos.LogicItem li = new ReleaseBatchDtos.LogicItem();
        li.setProject(project);
        li.setClassName(c.getClassName());
        li.setMember(c.getMember());
        li.setKind(c.getKind());
        li.setTitle(c.getTitle());
        li.setBefore(c.getBefore());
        li.setAfter(c.getAfter());
        li.setHint(c.getHint());
        li.setEndpoints(c.getEndpoints() == null ? new ArrayList<>() : new ArrayList<>(c.getEndpoints()));
        return li;
    }

    private static String compatWho(CompatHit h) {
        return ApiProjectKind.CLIENT.equals(h.getCallerKind()) ? "线上老版本 " + h.getCaller()
                : h.isInTicket() ? h.getCaller() + " 线上版本" : h.getCaller() + "（不在单里）";
    }

    static String sig(EndpointChange e) {
        return String.valueOf(e.getHttpMethod()).toUpperCase(Locale.ROOT) + " " + e.getPath();
    }

    private static String summaryOf(EndpointChange e) {
        return e.getSummary() == null || e.getSummary().isBlank() ? "" : " " + e.getSummary();
    }

    private static String changeWord(EndpointChange e) {
        if ("ADDED".equals(e.getChangeType())) {
            return "新增";
        }
        if ("REMOVED".equals(e.getChangeType())) {
            return "下线";
        }
        return switch (nz(e.getRisk())) {
            case "BREAKING" -> "不兼容";
            case "IMPL" -> "实现改了";
            case "COMPATIBLE" -> "兼容改动";
            default -> "修改";
        };
    }

    private static String kindWord(String kind) {
        return switch (kind) {
            case "CACHE" -> "缓存";
            case "CONFIG" -> "配置";
            case "SCHEDULE", "SERVICE_SCHEDULED" -> "定时任务";
            default -> kind;
        };
    }

    static String nameOf(Target x) {
        if (x.getProjectName() != null && !x.getProjectName().isBlank()) {
            return x.getProjectName();
        }
        if (x.getProjectCode() != null && !x.getProjectCode().isBlank()) {
            return x.getProjectCode();
        }
        return x.getRawProject();
    }

    /** 后端仓库里自带的前端标成「xxx · 前端」，和它的后端分开 */
    static String frontLabel(Target x) {
        return ApiProjectKind.isBackend(x.getKind()) && x.getProjectCode() != null ? nameOf(x) + " · 前端" : nameOf(x);
    }

    static String linkTarget(Link l) {
        String p = l.getPath() == null ? "" : l.getPath();
        return (l.getMethod() == null || p.startsWith("表 ") ? "" : l.getMethod() + " ") + p;
    }

    /** 同一对项目、同一个结论的关联合成一组，免得「只改了实现」的十几个接口各占一行 */
    private static List<List<Link>> groupLinks(List<Link> links, Predicate<Link> keep) {
        Map<String, List<Link>> out = new LinkedHashMap<>();
        for (Link l : links) {
            if (keep.test(l)) {
                out.computeIfAbsent(l.getFromNode() + "|" + l.getToNode() + "|" + l.getTitle(), k -> new ArrayList<>()).add(l);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static String groupTargets(List<Link> g) {
        String head = String.join("、", g.stream().limit(3).map(TestAssessmentBuilder::linkTarget).toList());
        return g.size() > 3 ? head + " 等 " + g.size() + " 个接口" : head;
    }

    private static final Pattern ENTITY_DIR = Pattern.compile("/(model|entity|entities|po|domain|dataobject|pojo)/");
    private static final Pattern WIRE_TYPE = Pattern.compile("(DTO|Dto|VO|Vo|Req|Request|Resp|Response|Param|Params|Query|Result|Bo|BO)$");

    /** 放在实体目录、名字不像出入参的类：多半是表实体，改了字段说明表要跟着加列，不等于接口返回变了 */
    static boolean isTableEntity(DtoChange d) {
        String file = d.getFile() == null ? "" : d.getFile().replace('\\', '/');
        return ENTITY_DIR.matcher(file).find() && !WIRE_TYPE.matcher(nz(d.getType())).find();
    }

    /** 删字段、或者类型变得 JSON 都不一样了，老调用方解析会出错 */
    static boolean breaksWire(FieldChange f) {
        return "REMOVED".equals(f.getChangeType()) || "TYPE_CHANGED".equals(f.getChangeType())
                && (f.getWireEffect() == null || "SHAPE".equals(f.getWireEffect()) || "UNKNOWN".equals(f.getWireEffect()));
    }

    static String shortPath(String rel) {
        int i = rel.lastIndexOf("/src/");
        String s = i >= 0 ? rel.substring(i + 5) : rel.startsWith("src/") ? rel.substring(4) : rel;
        return s.startsWith("views/") || s.startsWith("pages/") ? s.substring(s.indexOf('/') + 1) : s;
    }

    private static String kindLabel(Target x) {
        if (x.getProjectCode() == null) {
            return "项目管理里没有";
        }
        return ApiProjectKind.label(x.getKind());
    }

    private static List<EndpointDependencyResolver.RedisKeyRef> distinctKeys(List<EndpointDependencyResolver.RedisKeyRef> keys) {
        Map<String, EndpointDependencyResolver.RedisKeyRef> m = new LinkedHashMap<>();
        keys.forEach(k -> m.putIfAbsent(nz(k.getConstant()) + "|" + nz(k.getValue()), k));
        return new ArrayList<>(m.values());
    }

    static List<String> distinct(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    static List<String> limit(List<String> in) {
        if (in.size() <= MAX_LIST) {
            return in;
        }
        List<String> out = new ArrayList<>(in.subList(0, MAX_LIST));
        out.add("…… 还有 " + (in.size() - MAX_LIST) + " 条");
        return out;
    }

    private static String fileName(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    static String oneLine(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s*\\n\\s*", "；");
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank() && !"无".equals(s.trim());
    }

    private static String blankTo(String s, String d) {
        return s == null || s.isBlank() || "无".equals(s.trim()) ? d : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
