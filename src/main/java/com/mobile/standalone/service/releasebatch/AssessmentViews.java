package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer;
import com.mobile.standalone.service.apichange.ConfigPresence;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ApiRiskScanner;
import com.mobile.standalone.service.apichange.ConcurrencyRiskScanner;
import com.mobile.standalone.service.apichange.EndpointDependencyResolver;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.apichange.MustTestGuide;
import com.mobile.standalone.service.apichange.TableTestHints;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatCheck;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatHit;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndGroup;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndItem;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FrontChange;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Link;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.ReleaseStep;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Stat;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;
import com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.linkTarget;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.nameOf;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.oneLine;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.shortPath;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.sig;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.where;

/**
 * 评估报告里的三块「换个角度看同一批事实」的视图：全栈影响概览、发版顺序、按端分组的测试清单。
 * <p>
 * 只用已经分析出来的结构化结果，不另外推测；某一端没拿到代码 / 没分析成功时写明，不当成「没改动」。
 */
final class AssessmentViews {

    private AssessmentViews() {
    }

    /** 评估时已经算好的结构化事实 */
    record Facts(Ticket t,
                 boolean backendAnalyzed,
                 List<Item<EndpointChange>> eps,
                 List<Item<TableChange>> tables,
                 List<Item<OtherChange>> others,
                 List<Item<MethodLogicDiff.Change>> logic,
                 List<Item<MustTestGuide.Item>> must,
                 List<Link> links,
                 List<Target> frontChanged,
                 List<ApiRiskScanner.Finding> findings,
                 List<ConcurrencyRiskScanner.Risk> conc,
                 List<EndpointDependencyResolver.RedisKeyRef> changedKeys,
                 List<TicketCodeCheck.Item> ticketChecks,
                 CompatCheck compat,
                 boolean hasDdl,
                 boolean ticketSql,
                 List<Item<ConfigPresence>> configs) {

        /** 代码开始读写的新列，带上分支 / 单子 / 测试库里有没有 */
        List<NewCol> newColumns() {
            List<NewCol> out = new ArrayList<>();
            for (Item<TableChange> x : tables) {
                TableChange tc = x.v();
                for (ApiChangeAnalyzer.NewColumn c : tc.getNewColumns()) {
                    Boolean inDb = tc.getDdlText() == null ? null
                            : Pattern.compile("(?i)[`\\s(,]" + Pattern.quote(c.getColumn()) + "[`\\s]").matcher(tc.getDdlText()).find();
                    out.add(new NewCol(x.project(), tc.getTable(), c.getColumn(), c.getFile(), c.isDdlInBranch(),
                            TicketCodeCheck.ticketDeclares(t, tc.getTable(), c.getColumn()), inDb));
                }
            }
            return out;
        }

        List<NewCol> undeclaredColumns() {
            return newColumns().stream().filter(c -> !c.inBranch() && !c.inTicket()).toList();
        }

        List<CompatHit> hits(String callerKind) {
            return compat == null ? List.of()
                    : compat.getHits().stream().filter(h -> callerKind.equals(h.getCallerKind())).toList();
        }

        List<Item<MethodLogicDiff.Change>> logic(String... kinds) {
            Set<String> ks = Set.of(kinds);
            return logic.stream().filter(x -> ks.contains(x.v().getKind())).toList();
        }

        List<Item<OtherChange>> schedules() {
            return others.stream().filter(x -> "SCHEDULE".equals(x.v().getKind()) || "SERVICE_SCHEDULED".equals(x.v().getKind())).toList();
        }

        List<TicketCodeCheck.Item> checks(String topic) {
            return ticketChecks.stream().filter(c -> topic.equals(c.topic())).toList();
        }

        List<Target> targets(String kind) {
            return t.getTargets().stream().filter(x -> kind.equals(x.getKind())).toList();
        }

        /** 有不兼容改动但没跑过调用方检查（老数据、或检查本身没执行） */
        boolean compatNotRun() {
            return compat == null && CallerCompatProbe.anyBreak(
                    eps.stream().map(x -> new CallerCompatProbe.Endpoint(x.project(), x.v())).toList());
        }
    }

    /** @param inTestDb 测试库建表语句里有没有这一列；没读到表结构为 null */
    record NewCol(String project, String table, String column, String file,
                  boolean inBranch, boolean inTicket, Boolean inTestDb) {
    }

    static final String RISK = "RISK";
    static final String WARN = "WARN";
    static final String NONE = "NONE";
    static final String BEFORE = "BEFORE";
    static final String ORDER = "ORDER";
    static final String AFTER = "AFTER";
    private static final int MAX_ITEMS = 30;

    // ==================== 看板一：全栈影响概览 ====================

    static List<Stat> overview(Facts f) {
        List<Stat> out = new ArrayList<>();

        int pages = 0;
        List<String> h5 = new ArrayList<>();
        for (Target x : f.frontChanged()) {
            if (ApiProjectKind.CLIENT.equals(x.getKind())) {
                continue;
            }
            FrontChange fc = x.getFront();
            int n = fc.getPages().isEmpty() ? 0 : fc.getPages().size();
            pages += n;
            h5.add(TestAssessmentBuilder.frontLabel(x) + (n > 0 ? " " + n + " 个页面" : " " + fc.getChangedFiles() + " 个文件"));
        }
        List<CompatHit> h5Hits = f.hits(ApiProjectKind.FRONTEND).stream().filter(h -> !h.isInTicket() && RISK.equals(h.getLevel())).toList();
        Set<String> outside = new LinkedHashSet<>();
        h5Hits.forEach(h -> outside.add(h.getCaller()));
        if (!outside.isEmpty()) {
            h5.add("不在单里但受影响：" + String.join("、", outside));
        }
        missing(f, ApiProjectKind.FRONTEND).forEach(h5::add);
        out.add(new Stat("h5", "H5 页面", pages, h5.isEmpty() ? "没改到" : String.join("；", h5),
                !outside.isEmpty() ? RISK : pages > 0 || !h5.isEmpty() ? WARN : NONE));

        List<CompatHit> appHits = f.hits(ApiProjectKind.CLIENT).stream().filter(h -> RISK.equals(h.getLevel())).toList();
        Set<String> apps = new LinkedHashSet<>();
        f.targets(ApiProjectKind.CLIENT).forEach(x -> apps.add(nameOf(x)));
        appHits.forEach(h -> apps.add(h.getCaller()));
        List<String> app = new ArrayList<>();
        if (!appHits.isEmpty()) {
            app.add("线上老版本受影响 " + appHits.size() + " 处");
        }
        if (!f.targets(ApiProjectKind.CLIENT).isEmpty()) {
            app.add("同单改了 " + String.join("、", f.targets(ApiProjectKind.CLIENT).stream().map(TestAssessmentBuilder::nameOf).toList()));
        }
        if (f.compat() != null && !f.compat().getUnchecked().isEmpty()) {
            app.add(f.compat().getUnchecked().size() + " 个仓库没拿到代码，老版本兼容没检查全");
        }
        if (f.compatNotRun()) {
            app.add("后端有不兼容改动，还没检查线上版本，重新分析后才有");
        }
        out.add(new Stat("app", "App", apps.size(), app.isEmpty() ? (!f.backendAnalyzed() ? "后端没分析成功，不知道"
                : f.compat() == null ? "后端没有不兼容改动" : "线上版本没调到不兼容的接口")
                : String.join("；", app), !appHits.isEmpty() || f.compatNotRun() ? RISK : !apps.isEmpty() ? WARN : NONE));

        long breaking = f.eps().stream().filter(x -> "BREAKING".equals(x.v().getRisk())).count();
        long removed = f.eps().stream().filter(x -> "REMOVED".equals(x.v().getChangeType())).count();
        long added = f.eps().stream().filter(x -> "ADDED".equals(x.v().getChangeType())).count();
        long impl = f.eps().stream().filter(x -> "IMPL".equals(x.v().getRisk())).count();
        List<String> api = new ArrayList<>();
        if (breaking > 0) {
            api.add("不兼容 " + breaking);
        }
        if (removed > 0) {
            api.add("下线 " + removed);
        }
        if (added > 0) {
            api.add("新增 " + added);
        }
        if (impl > 0) {
            api.add("只改实现 " + impl);
        }
        out.add(new Stat("api", "后端接口", f.eps().size(), !f.backendAnalyzed() ? "后端没分析成功，不知道"
                : api.isEmpty() ? "接口没变" : String.join("、", api), breaking + removed > 0 ? RISK : f.eps().isEmpty() ? NONE : WARN));

        Set<String> tables = new LinkedHashSet<>();
        f.tables().forEach(x -> tables.add(x.v().getTable().toLowerCase()));
        f.checks("表").forEach(c -> {
            if (c.ticket() != null && c.ticket().startsWith("SQL 里动了 ")) {
                tables.add(c.ticket().substring("SQL 里动了 ".length()).trim());
            }
        });
        long ddl = f.tables().stream().filter(x -> x.v().isDdl()).count();
        List<NewCol> noSql = f.undeclaredColumns();
        out.add(new Stat("db", "数据库表", tables.size(), tables.isEmpty() ? "没涉及"
                : String.join("、", tables) + (ddl > 0 ? "（" + ddl + " 张改了结构）" : "")
                + (noSql.isEmpty() ? "" : "；代码要用新字段 " + String.join("、", noSql.stream().map(c -> c.table() + "." + c.column()).toList())
                + "，没找到加字段的 SQL"),
                f.hasDdl() || f.ticketSql() || !f.newColumns().isEmpty() ? RISK : tables.isEmpty() ? NONE : WARN));

        Set<String> jobs = new LinkedHashSet<>();
        f.logic("SCHEDULE").forEach(x -> jobs.add(x.v().getClassName() + "." + x.v().getMember()));
        f.schedules().forEach(x -> jobs.add(x.v().getFileName().replace(".java", "")));
        boolean jobMismatch = f.checks("定时任务").stream().anyMatch(c -> !TicketCodeCheck.MATCH.equals(c.status()));
        out.add(new Stat("job", "定时任务", jobs.size(), jobs.isEmpty() ? (jobMismatch ? "单子提了，代码里没看到" : "没涉及") : String.join("、", jobs),
                jobMismatch ? RISK : jobs.isEmpty() ? NONE : WARN));

        Set<String> configs = new LinkedHashSet<>();
        f.logic("CONFIG").forEach(x -> configs.add(x.v().getRef() != null ? x.v().getRef() : x.v().getMember()));
        f.checks("配置").forEach(c -> configs.add(c.ticket().replaceFirst("^单子里(写了|没提到) ", "")));
        boolean cfgMismatch = f.checks("配置").stream().anyMatch(c -> TicketCodeCheck.CODE_ONLY.equals(c.status())
                || TicketCodeCheck.TICKET_ONLY.equals(c.status()));
        out.add(new Stat("config", "配置", configs.size(), configs.isEmpty() ? "没涉及" : String.join("、", configs),
                cfgMismatch ? RISK : configs.isEmpty() ? NONE : WARN));

        long mismatch = f.ticketChecks().stream().filter(c -> TicketCodeCheck.CODE_ONLY.equals(c.status())
                || TicketCodeCheck.TICKET_ONLY.equals(c.status())).count();
        out.add(new Stat("check", "单子 ↔ 代码对不上", (int) mismatch, mismatch == 0 ? "都对上了或没涉及" : "见「需求单 ↔ 代码 对账」",
                mismatch > 0 ? RISK : NONE));
        return out;
    }

    private static List<String> missing(Facts f, String kind) {
        return f.targets(kind).stream().filter(x -> x.getCodeMissing() != null)
                .map(x -> nameOf(x) + " 缺代码，没对比").toList();
    }

    // ==================== 上线清单：上线前必做 → 发版顺序 → 发版后 ====================

    /**
     * 测试也负责发版，这份清单就是发版时照着做的顺序。
     * 只写从代码 / 单子里看得出来的步骤；看不出来的（配置中心、网关路由里的调用）写明「认不出」，不写成「没有先后」。
     */
    static List<ReleaseStep> releasePlan(Facts f) {
        List<ReleaseStep> out = new ArrayList<>();
        sqlSteps(f, out);
        configSteps(f, out);
        mqTopicSteps(f, out);
        backendOrder(f, out);
        frontOrder(f, out);
        afterSteps(f, out);
        return out;
    }

    private static final java.util.Map<String, String> PHASE_WORD = java.util.Map.of(
            BEFORE, "【上线前】", ORDER, "【发版顺序】", AFTER, "【发版后】");

    /** 纯文字版，导出文档用 */
    static List<String> releaseOrder(List<ReleaseStep> plan) {
        return plan.stream().map(s -> PHASE_WORD.getOrDefault(s.phase(), "") + (s.must() ? "必须 · " : "") + s.what()
                + (s.why() == null ? "" : "——" + s.why())).toList();
    }

    private static void sqlSteps(Facts f, List<ReleaseStep> out) {
        List<NewCol> cols = f.newColumns();
        for (NewCol c : f.undeclaredColumns()) {
            String db = c.inTestDb() == null ? "测试库的表结构没读到，不知道测试库有没有这个字段"
                    : c.inTestDb() ? "测试库里已经有这个字段（多半是开发自己执行过），线上库还没有"
                    : "测试库里也没有，现在部署测试环境就会报错";
            out.add(new ReleaseStep(BEFORE, "找开发要给 " + c.table() + " 加 " + c.column() + " 字段的 SQL，执行完再发 " + c.project(),
                    "代码开始读写 " + c.table() + "." + c.column() + "（" + shortPath(c.file()) + "），分支和单子里都没有加字段的 SQL；"
                            + "不先执行，读写这张表的语句全部报 Unknown column。" + db + "。SQL 拿到后补进单子", true));
        }
        if (f.ticketSql()) {
            List<String> declared = cols.stream().filter(NewCol::inTicket).map(c -> c.table() + "." + c.column()).toList();
            out.add(new ReleaseStep(BEFORE, "先执行单子里的 SQL，再部署代码（测试环境部署前、线上发版前各执行一次）"
                    + (declared.isEmpty() ? "" : "：含 " + String.join("、", declared)),
                    "反过来新代码会查不存在的字段 / 表；执行完查一遍表结构和数据", true));
        }
        Set<String> branchDdl = new LinkedHashSet<>();
        f.tables().stream().filter(x -> x.v().isDdl())
                .forEach(x -> branchDdl.add(x.v().getTable() + "（" + String.join("、", x.v().getFiles().stream()
                        .filter(p -> p.toLowerCase(java.util.Locale.ROOT).endsWith(".sql")).map(TestAssessmentBuilder::shortPath).toList()) + "）"));
        cols.stream().filter(c -> c.inBranch() && !c.inTicket()).forEach(c -> branchDdl.add(c.table() + "." + c.column()));
        if (!branchDdl.isEmpty()) {
            out.add(new ReleaseStep(BEFORE, "先执行分支里改表结构的 SQL：" + String.join("、", branchDdl).replace("（）", ""),
                    f.ticketSql() ? "单子里的 SQL 和分支里的对一下，别漏执行" : "单子里没写 SQL：谁来执行、什么时候执行定下来，并补进单子", true));
        }
        Map<String, Set<String>> owners = new java.util.LinkedHashMap<>();
        f.tables().forEach(x -> owners.computeIfAbsent(x.v().getTable().toLowerCase(java.util.Locale.ROOT), k -> new LinkedHashSet<>()).add(x.project()));
        Set<String> changedTables = new LinkedHashSet<>();
        f.tables().stream().filter(x -> x.v().isDdl()).forEach(x -> changedTables.add(x.v().getTable().toLowerCase(java.util.Locale.ROOT)));
        cols.forEach(c -> changedTables.add(c.table().toLowerCase(java.util.Locale.ROOT)));
        for (String tb : changedTables) {
            Set<String> who = owners.getOrDefault(tb, Set.of());
            if (who.size() > 1) {
                out.add(new ReleaseStep(BEFORE, "表 " + tb + " 的 SQL 要在 " + String.join("、", who) + " 都发版之前执行",
                        "这几个服务都读写这张表，哪个先发都要能读到新字段", true));
            }
        }
    }

    private static final Pattern SECRET = Pattern.compile("(?i)(password|passwd|pwd|secret|token|credential|private|(^|[.\\-_])(ak|sk|key)$)");

    private static void configSteps(Facts f, List<ReleaseStep> out) {
        Set<String> covered = new LinkedHashSet<>();
        for (Item<ConfigPresence> x : f.configs()) {
            ConfigPresence p = x.v();
            String key = p.getKey();
            covered.add(key);
            Integer need = p.getNeedCount();
            Map<String, String> prof = p.getProfiles();
            if (prof.isEmpty()) {
                out.add(new ReleaseStep(BEFORE, x.project() + "：上线前到配置中心 / k8s 配置里把 " + key + " 配好"
                        + (need == null ? "" : "（至少 " + need + " 个值）"),
                        "代码新读了这个配置" + (p.isNoDefault() ? "、没写默认值，不配发版后启动失败" : "")
                                + "；仓库里没找到 application 配置文件，没法自动检查各环境配没配", p.isNoDefault() || need != null));
                continue;
            }
            List<String> missing = prof.entrySet().stream().filter(e -> e.getValue() == null).map(Map.Entry::getKey).toList();
            List<String> few = need == null ? List.of() : prof.entrySet().stream()
                    .filter(e -> e.getValue() != null && ConfigPresence.count(e.getValue()) < need)
                    .map(e -> e.getKey() + " 只有 " + ConfigPresence.count(e.getValue()) + " 个").toList();
            if (!few.isEmpty() || need != null && !missing.isEmpty()) {
                List<String> bad = new ArrayList<>(few);
                missing.forEach(m -> bad.add(m + " 没配"));
                out.add(new ReleaseStep(BEFORE, x.project() + "：" + key + " 上线前要补到 " + need + " 个值（" + String.join("、", bad) + "）",
                        "代码按下标取到了第 " + need + " 个值，少一个就越界，用到它的计算整段失败", true));
            } else if (!missing.isEmpty() && p.isNoDefault()) {
                out.add(new ReleaseStep(BEFORE, x.project() + "：" + String.join("、", missing) + " 环境的配置文件里没有 " + key + "，上线前加上",
                        "代码新读这个配置、没写默认值：不配的环境发版后启动失败", true));
            } else if (!missing.isEmpty()) {
                out.add(new ReleaseStep(BEFORE, x.project() + "：" + String.join("、", missing) + " 环境没配 " + key + "，会走代码里的默认值",
                        "确认默认值就是这个环境要的；要改的话上线前加到配置文件 / 配置中心", false));
            } else {
                out.add(new ReleaseStep(BEFORE, x.project() + "：" + key + " 各环境都配了（" + values(prof, SECRET.matcher(key).find()) + "）",
                        "配置文件跟代码一起发；核对线上值和需求一致" + (need == null ? "" : "，个数够 " + need + " 个"), false));
            }
        }
        for (TicketCodeCheck.Item c : f.checks("配置")) {
            String key = c.ticket() == null ? null : c.ticket().replaceFirst("^单子里(写了|没提到) ", "");
            if (key == null || covered.contains(key) || TicketCodeCheck.CODE_ONLY.equals(c.status())) {
                continue;
            }
            out.add(new ReleaseStep(BEFORE, "按单子改好配置 " + key + " 再发代码",
                    TicketCodeCheck.TICKET_ONLY.equals(c.status()) ? "代码里没搜到这个 key，可能是别的服务 / 网关的配置，找开发确认改哪" : "单子里写了要改", false));
        }
    }

    private static String values(Map<String, String> prof, boolean secret) {
        List<String> parts = new ArrayList<>();
        prof.forEach((env, v) -> parts.add(env + "=" + (secret ? "已配" : v.length() > 40 ? v.substring(0, 40) + "…" : v)));
        return String.join("；", parts);
    }

    private static void mqTopicSteps(Facts f, List<ReleaseStep> out) {
        Set<String> topics = new LinkedHashSet<>();
        for (Item<MethodLogicDiff.Change> x : consumers(f)) {
            topics.add(x.project() + " 消费的 " + (x.v().getRef() == null ? x.v().getClassName() + "." + x.v().getMember() + " 的 topic" : x.v().getRef()));
        }
        if (!topics.isEmpty()) {
            out.add(new ReleaseStep(BEFORE, "Kafka 上先建好 topic / 消费组：" + String.join("、", topics),
                    "没开自动创建 topic 的话，消费方起来就报错；测试环境和线上都要有", false));
        }
    }

    /** 这次新开始消费消息的地方 */
    private static List<Item<MethodLogicDiff.Change>> consumers(Facts f) {
        return f.logic().stream().filter(x -> "MQ".equals(x.v().getKind()) && "开始消费消息".equals(x.v().getTitle())
                || "METHOD_ADDED".equals(x.v().getKind()) && x.v().getHint() != null && x.v().getHint().contains("消息消费方")).toList();
    }

    /**
     * 多个后端之间谁先发：看本单里一个后端调另一个后端（Feign / 写死的 http 地址）、Kafka 发和收。
     * 只认代码里看得见的调用；地址配在配置中心、走网关转发的认不出。
     */
    private static void backendOrder(Facts f, List<ReleaseStep> out) {
        Map<String, Set<String>> first = new java.util.LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        Set<String> together = new LinkedHashSet<>();
        String back = ":" + CallSiteScanner.BACK;
        for (Link l : f.links()) {
            String from = l.getFromNode();
            String to = l.getToNode();
            if (from == null || to == null || from.equals(to) || !from.endsWith(back) || !to.endsWith(back)) {
                continue;
            }
            String caller = l.getFromName();
            String callee = l.getToName();
            String api = linkTarget(l);
            if ("REMOVED".equals(l.getEndpointChange())) {
                out.add(new ReleaseStep(ORDER, caller + " 这次的代码里还在调 " + callee + " 要下线的 " + api,
                        callee + " 发版后这个调用直接失败：要么 " + caller + " 先去掉调用并发版、" + callee + " 再发，要么 " + callee + " 这次先别下线", true));
            } else if ("BREAKING".equals(l.getEndpointRisk())) {
                if ("UNCHANGED".equals(l.getCallChange())) {
                    out.add(new ReleaseStep(ORDER, callee + " 发版后，" + caller + " 调 " + api + " 会出错",
                            callee + " 这个接口有不兼容改动，" + caller + " 的调用代码没跟着改：先找开发确认，或让 " + callee + " 兼容老参数 / 老字段", true));
                } else {
                    together.add(caller + " 和 " + callee + "（" + api + "）");
                }
            } else if ("ADDED".equals(l.getEndpointChange())) {
                edge(first, callee, caller);
                reasons.add(caller + " 这次调用了 " + callee + " 新加的 " + api + "，" + caller + " 先发会调到 404");
            } else if ("COMPATIBLE".equals(l.getEndpointRisk()) && !"UNCHANGED".equals(l.getCallChange())) {
                edge(first, callee, caller);
                reasons.add(callee + " 的 " + api + " 加了参数 / 返回字段，" + caller + " 调用的代码这次也改了，可能要用新的");
            }
        }
        Map<String, Set<String>> consumerByTopic = new java.util.LinkedHashMap<>();
        for (Item<MethodLogicDiff.Change> x : consumers(f)) {
            if (x.v().getRef() != null) {
                consumerByTopic.computeIfAbsent(x.v().getRef(), k -> new LinkedHashSet<>()).add(x.project());
            }
        }
        for (Item<MethodLogicDiff.Change> x : f.logic("MQ")) {
            String topic = x.v().getRef();
            if (topic == null || !"新增发消息".equals(x.v().getTitle())) {
                continue;
            }
            for (String consumer : consumerByTopic.getOrDefault(topic, Set.of())) {
                if (!consumer.equals(x.project())) {
                    edge(first, consumer, x.project());
                    reasons.add(x.project() + " 开始往 " + topic + " 发消息，" + consumer + " 这次开始消费它：消费方先发，不然消息没人处理");
                }
            }
        }
        if (!first.isEmpty()) {
            List<String> order = topo(first);
            if (order == null) {
                out.add(new ReleaseStep(ORDER, "这几个后端互相依赖，只能同时发：" + String.join("、", nodesOf(first)),
                        String.join("；", reasons) + "。发版窗口里会有调用失败，安排在低峰期，发完马上回归", true));
            } else {
                out.add(new ReleaseStep(ORDER, "后端按这个顺序发：" + String.join(" → ", order), String.join("；", reasons), true));
            }
        }
        if (!together.isEmpty()) {
            out.add(new ReleaseStep(ORDER, "要同时发：" + String.join("、", together),
                    "接口有不兼容改动，调用方也跟着改了：谁先发中间都调不通，安排在低峰期一起发", true));
        }
        List<Target> backends = f.t().getTargets().stream()
                .filter(x -> x.getProjectCode() != null && ApiProjectKind.isBackend(x.getKind())).toList();
        if (first.isEmpty() && together.isEmpty() && backends.size() > 1) {
            out.add(new ReleaseStep(ORDER, "这单改了 " + backends.size() + " 个后端（"
                    + String.join("、", backends.stream().map(TestAssessmentBuilder::nameOf).distinct().toList())
                    + "），代码里没看到它们之间有新接口 / 新消息的依赖",
                    "只认得 Feign、写死的 http 地址和写死的 Kafka topic；地址配在配置里、走网关转发、topic 用别的类的常量的认不出，找开发确认有没有先后", false));
        }
    }

    private static void edge(Map<String, Set<String>> first, String before, String after) {
        first.computeIfAbsent(before, k -> new LinkedHashSet<>()).add(after);
        first.computeIfAbsent(after, k -> new LinkedHashSet<>());
    }

    private static Set<String> nodesOf(Map<String, Set<String>> g) {
        return g.keySet();
    }

    /** 先发的排前面；有环返回 null */
    private static List<String> topo(Map<String, Set<String>> g) {
        Map<String, Integer> in = new java.util.LinkedHashMap<>();
        g.keySet().forEach(k -> in.put(k, 0));
        g.values().forEach(vs -> vs.forEach(v -> in.merge(v, 1, Integer::sum)));
        List<String> out = new ArrayList<>();
        java.util.Deque<String> ready = new java.util.ArrayDeque<>();
        in.forEach((k, v) -> {
            if (v == 0) {
                ready.add(k);
            }
        });
        while (!ready.isEmpty()) {
            String n = ready.poll();
            out.add(n);
            for (String m : g.getOrDefault(n, Set.of())) {
                if (in.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        return out.size() == in.size() ? out : null;
    }

    private static void frontOrder(Facts f, List<ReleaseStep> out) {
        String back = ":" + CallSiteScanner.BACK;
        Set<String> newCallers = new LinkedHashSet<>();
        Set<String> together = new LinkedHashSet<>();
        Set<String> backs = new LinkedHashSet<>();
        for (Link l : f.links()) {
            if (l.getFromNode() == null || l.getFromNode().endsWith(back)) {
                continue;
            }
            if (CrossProjectLinker.JOINT.equals(l.getLevel())
                    && ("ADDED".equals(l.getEndpointChange()) || "NEW_CALL".equals(l.getCallChange()))) {
                newCallers.add(l.getFromName());
                backs.add(l.getToName());
            }
            if ("BREAKING".equals(l.getEndpointRisk()) && !"UNCHANGED".equals(l.getCallChange()) && l.getFromName() != null) {
                together.add(l.getFromName() + " 和 " + l.getToName());
            }
        }
        if (!newCallers.isEmpty()) {
            out.add(new ReleaseStep(ORDER, "先发后端 " + String.join("、", backs) + "，再发 " + String.join("、", newCallers),
                    "它们这次要调的接口后端要先有，前端先发页面会报错", true));
        }
        if (!together.isEmpty()) {
            out.add(new ReleaseStep(ORDER, "要同时发：" + String.join("、", together),
                    "后端有不兼容改动、前端也跟着改了，谁先发中间页面都会出错", true));
        }
        Set<String> oldApps = new LinkedHashSet<>();
        f.hits(ApiProjectKind.CLIENT).stream().filter(h -> RISK.equals(h.getLevel())).forEach(h -> oldApps.add(h.getCaller()));
        if (!oldApps.isEmpty()) {
            out.add(new ReleaseStep(ORDER, "线上老版本 " + String.join("、", oldApps) + " 没法跟着发",
                    "后端要保留老接口 / 老字段到老版本下线，或者协调强更", true));
        }
        for (Target x : f.t().getTargets()) {
            if (x.getNote() != null && x.getNote().contains("顺序")) {
                out.add(new ReleaseStep(ORDER, "单子备注：" + nameOf(x) + " " + x.getNote(), null, true));
            }
        }
    }

    private static void afterSteps(Facts f, List<ReleaseStep> out) {
        boolean xxl = false;
        for (Item<MethodLogicDiff.Change> x : f.logic()) {
            MethodLogicDiff.Change c = x.v();
            String h = c.getJobHandler();
            if (h == null) {
                continue;
            }
            xxl = true;
            if ("METHOD_ADDED".equals(c.getKind()) || "变成了定时任务".equals(c.getTitle())) {
                out.add(new ReleaseStep(AFTER, "到 XXL-JOB 调度中心给 " + x.project() + " 新建任务，JobHandler 填 " + h + "，测试环境先手动执行一次",
                        "代码发上去之后执行器才认得这个 JobHandler，提前建任务会报找不到 handler", true));
            } else if ("不再是定时任务".equals(c.getTitle())) {
                out.add(new ReleaseStep(AFTER, "调度中心里 JobHandler 为 " + h + " 的任务停掉",
                        "代码里已经没有这个任务，调度中心还挂着会一直报错", true));
            } else {
                out.add(new ReleaseStep(AFTER, "调度中心里核对 " + h + " 任务的配置（JobHandler / 执行时间 / 参数）",
                        c.getTitle() + "：" + nz(c.getBefore()) + " → " + nz(c.getAfter()), true));
            }
        }
        if (!f.logic("SCHEDULE").isEmpty() && !xxl || !f.schedules().isEmpty()) {
            Set<String> jobs = new LinkedHashSet<>();
            f.logic("SCHEDULE").stream().filter(x -> x.v().getJobHandler() == null)
                    .forEach(x -> jobs.add(x.v().getClassName() + "." + x.v().getMember()));
            f.schedules().forEach(x -> jobs.add(x.v().getFileName().replace(".java", "")));
            if (!jobs.isEmpty()) {
                out.add(new ReleaseStep(AFTER, "发版后核对定时任务：" + String.join("、", jobs),
                        "定时逻辑改了：测试环境手动触发一次看日志和落库数据；用 XXL-JOB 的到调度中心核对执行时间和参数", false));
            }
        }
    }

    // ==================== 看板三：按端分组的测试清单 ====================

    static List<EndGroup> endGroups(Facts f) {
        List<EndGroup> out = new ArrayList<>();
        out.add(h5(f));
        out.add(app(f));
        out.add(api(f));
        out.add(db(f));
        out.add(job(f));
        out.add(infra(f));
        out.add(config(f));
        out.add(security(f));
        for (EndGroup g : out) {
            List<EndItem> uniq = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (EndItem i : g.getItems()) {
                if (seen.add(i.what())) {
                    uniq.add(i);
                }
            }
            if (uniq.size() > MAX_ITEMS) {
                int more = uniq.size() - MAX_ITEMS;
                uniq = new ArrayList<>(uniq.subList(0, MAX_ITEMS));
                uniq.add(new EndItem("…… 还有 " + more + " 条，见上面各节", null));
            }
            g.setItems(uniq);
        }
        return out;
    }

    private static EndGroup group(String key, String label) {
        EndGroup g = new EndGroup();
        g.setKey(key);
        g.setLabel(label);
        return g;
    }

    private static EndGroup h5(Facts f) {
        EndGroup g = group("h5", "H5 / 前端");
        for (Target x : f.frontChanged()) {
            if (ApiProjectKind.CLIENT.equals(x.getKind())) {
                continue;
            }
            frontItems(g, x, "页面");
        }
        linkItems(g, f, ":" + CallSiteScanner.FRONT);
        for (CompatHit h : f.hits(ApiProjectKind.FRONTEND)) {
            g.getItems().add(new EndItem(h.getAdvice(), h.getCaller() + " 线上版本调 " + h.getEndpoint() + "：" + h.getProblem()));
        }
        if (g.getItems().isEmpty()) {
            List<String> m = missing(f, ApiProjectKind.FRONTEND);
            g.setNote(!m.isEmpty() ? String.join("；", m) + "：H5 改了什么不知道" : "这次没看到 H5 / 前端相关改动");
        }
        return g;
    }

    private static EndGroup app(Facts f) {
        EndGroup g = group("app", "App 客户端");
        for (CompatHit h : f.hits(ApiProjectKind.CLIENT)) {
            g.getItems().add(new EndItem(h.getAdvice(), h.getCaller() + " 线上版本调 " + h.getEndpoint() + "：" + h.getProblem()));
        }
        for (Target x : f.frontChanged()) {
            if (ApiProjectKind.CLIENT.equals(x.getKind())) {
                frontItems(g, x, "功能");
            }
        }
        linkItems(g, f, ":" + CallSiteScanner.CLIENT);
        if (f.compatNotRun()) {
            g.getItems().add(new EndItem("重新分析一次，看线上版本 App 调没调这些不兼容的接口；在那之前，用线上版本 App 打开相关页面人工确认",
                    "后端有不兼容改动，但这份评估里没有线上版本调用方的检查结果"));
        }
        if (f.compat() != null) {
            for (String u : f.compat().getUnchecked()) {
                if (u.contains("客户端")) {
                    g.getItems().add(new EndItem("没拿到代码，老版本兼容没检查：" + u, "没分析到不等于没问题，这个客户端要人工确认调没调改了的接口"));
                }
            }
        }
        if (g.getItems().isEmpty()) {
            List<String> m = missing(f, ApiProjectKind.CLIENT);
            if (!m.isEmpty()) {
                g.setNote(String.join("；", m) + "：客户端改了什么不知道");
            } else if (!f.backendAnalyzed()) {
                g.setNote("后端没分析成功，老版本兼容没法判断");
            } else if (f.compat() == null) {
                g.setNote("后端没有不兼容改动（没删字段、没加必填入参、没下线接口），老版本 App 不用专门测");
            } else {
                g.setNote("查了 " + String.join("、", f.compat().getChecked()) + " 的 master，没有调到这次不兼容的接口");
            }
        }
        return g;
    }

    private static void frontItems(EndGroup g, Target x, String what) {
        FrontChange fc = x.getFront();
        String name = TestAssessmentBuilder.frontLabel(x);
        if (!fc.getPages().isEmpty()) {
            List<String> pages = fc.getPages().stream().map(TestAssessmentBuilder::shortPath).limit(6).toList();
            g.getItems().add(new EndItem("打开改到的" + what + "走一遍：" + String.join("、", pages)
                    + (fc.getPages().size() > pages.size() ? " 等 " + fc.getPages().size() + " 个" : ""),
                    name + " 这次改了这些" + what));
        } else {
            g.getItems().add(new EndItem("回归 " + name + " 改到的" + what + "（" + FrontDiffScanner.summary(fc) + "）", name + " 有代码改动"));
        }
        for (CallSite s : fc.getAddedCalls()) {
            g.getItems().add(new EndItem("新调了 " + s.getRaw() + "（" + shortPath(s.getFile()) + "）：抓包看请求参数、返回字段和页面展示对得上",
                    name + " 这次新接的接口"));
        }
        for (CallSite s : fc.getRemovedCalls()) {
            g.getItems().add(new EndItem("不再调 " + s.getRaw() + "（" + shortPath(s.getFile()) + "）：确认对应功能是有意去掉的",
                    name + " 这次删掉的接口调用"));
        }
    }

    private static void linkItems(EndGroup g, Facts f, String side) {
        for (Link l : f.links()) {
            if (l.getFromNode() == null || !l.getFromNode().endsWith(side)) {
                continue;
            }
            if (CrossProjectLinker.RISK.equals(l.getLevel()) || CrossProjectLinker.JOINT.equals(l.getLevel())) {
                g.getItems().add(new EndItem(l.getFromName() + " → " + linkTarget(l) + "：" + l.getTitle(),
                        CrossProjectLinker.RISK.equals(l.getLevel()) ? "两边对不上" : "两边这次都改了，要联调"));
            }
        }
    }

    private static EndGroup api(Facts f) {
        EndGroup g = group("api", "后端接口");
        for (Item<MustTestGuide.Item> x : f.must()) {
            MustTestGuide.Item it = x.v();
            if (!"ENDPOINT".equals(it.kind())) {
                continue;
            }
            List<String> notes = it.notes().stream().filter(n -> !TestAssessmentBuilder.bucket(n).isEmpty()).limit(3).toList();
            g.getItems().add(new EndItem(it.httpMethod() + " " + it.path() + "：" + (notes.isEmpty() ? "真实入参调一次，断言业务码和关键字段"
                    : String.join("；", notes)), "【" + it.priority() + "】" + it.title()));
        }
        for (Item<MethodLogicDiff.Change> x : f.logic()) {
            MethodLogicDiff.Change c = x.v();
            if (c.getEndpoints() == null || c.getEndpoints().isEmpty() || c.getHint() == null
                    || "METHOD_CHANGED".equals(c.getKind()) || isInfra(c.getKind())) {
                continue;
            }
            g.getItems().add(new EndItem(where(x) + "：" + c.getHint(), c.getTitle()));
        }
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "接口出入参和实现都没变" : "后端没分析成功，接口改没改不知道");
        }
        return g;
    }

    private static boolean isInfra(String kind) {
        return Set.of("CACHE", "MQ", "REMOTE", "SCHEDULE", "CONFIG", "WRITE").contains(kind);
    }

    private static EndGroup db(Facts f) {
        EndGroup g = group("db", "数据库");
        for (NewCol c : f.undeclaredColumns()) {
            g.getItems().add(new EndItem("找开发要给 " + c.table() + " 加 " + c.column() + " 的 SQL，在测试库执行后再部署代码",
                    "代码开始读写这一列，分支和单子里都没有加字段的 SQL" + (Boolean.TRUE.equals(c.inTestDb()) ? "（测试库已经有了，线上没有）" : "")));
        }
        if (f.ticketSql()) {
            g.getItems().add(new EndItem("先在测试库执行单子里的 SQL，再部署代码；执行后查一遍表结构 / 数据", "单子里有 SQL"));
        }
        for (NewCol c : f.newColumns()) {
            g.getItems().add(new EndItem("触发写 " + c.table() + " 的逻辑后查 " + c.column() + " 有值、内容对；老数据这一列是空的，读它的地方要能正常展示",
                    "代码开始读写 " + c.table() + "." + c.column() + "（" + shortPath(c.file()) + "）"));
        }
        for (Item<TableChange> x : f.tables()) {
            TableChange tc = x.v();
            for (TableTestHints.Hint h : tc.getTestHints()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + "：" + h.title(), h.basis()));
            }
            if (tc.getTestHints().isEmpty()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + "：造正常 / 边界 / 空数据各一份，调接口后查表核对",
                        tc.isDdl() ? "结构改了" : "读写这张表的 SQL 改了"));
            }
            if (tc.isDdl()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + " 的历史数据：新字段是默认值 / NULL 时接口和页面能正常展示",
                        "老数据不会自动补新字段"));
            }
        }
        for (Item<MethodLogicDiff.Change> x : f.logic("WRITE")) {
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(), x.v().getTitle() + " " + oneLine(nzs(x.v().getAfter(), x.v().getBefore()))));
        }
        for (ConcurrencyRiskScanner.Risk r : f.conc()) {
            g.getItems().add(new EndItem(r.httpMethod() + " " + r.path() + "：" + (r.howToTest() == null ? r.title() : r.howToTest()), r.title()));
        }
        checkItems(g, f, c -> "表".equals(c.topic()) || "字段".equals(c.topic()));
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "没涉及表结构和读写 SQL 的改动" : "后端没分析成功，不知道");
        }
        return g;
    }

    static EndGroup job(Facts f) {
        EndGroup g = group("job", "定时任务");
        for (Item<MethodLogicDiff.Change> x : f.logic("SCHEDULE")) {
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(), x.v().getTitle()
                    + (x.v().getAfter() == null ? "" : "（" + x.v().getAfter() + "）")));
        }
        for (Item<OtherChange> x : f.schedules()) {
            g.getItems().add(new EndItem("测试环境手动触发 " + x.v().getFileName().replace(".java", "") + " 一次，看日志和落库数据",
                    x.project() + " 里含定时任务的类改了"));
        }
        checkItems(g, f, c -> "定时任务".equals(c.topic()));
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "没涉及定时任务" : "后端没分析成功，不知道");
        }
        return g;
    }

    private static EndGroup infra(Facts f) {
        EndGroup g = group("infra", "缓存 / 消息 / 外部调用");
        for (Item<MethodLogicDiff.Change> x : f.logic("CACHE", "MQ", "REMOTE")) {
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(), x.v().getTitle()
                    + (nzs(x.v().getAfter(), x.v().getBefore()) == null ? "" : " " + oneLine(nzs(x.v().getAfter(), x.v().getBefore())))));
        }
        for (EndpointDependencyResolver.RedisKeyRef k : f.changedKeys()) {
            g.getItems().add(new EndItem("缓存 key " + k.getConstant() + " 改了：上线时新老 key 并存，改数据后确认读到新值；老 key 要不要清",
                    k.getOldValue() + " → " + k.getValue()));
        }
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "代码里没认出缓存、消息、外部调用的增删" : "后端没分析成功，不知道");
        }
        return g;
    }

    static EndGroup config(Facts f) {
        EndGroup g = group("config", "配置");
        for (Item<MethodLogicDiff.Change> x : f.logic("CONFIG")) {
            g.getItems().add(new EndItem(x.v().getHint(), x.v().getTitle() + "（" + x.project() + " " + x.v().getClassName() + "）"));
        }
        checkItems(g, f, c -> "配置".equals(c.topic()));
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "代码里没认出配置改动，单子里也没写" : "后端没分析成功，不知道");
        }
        return g;
    }

    private static EndGroup security(Facts f) {
        EndGroup g = group("security", "安全");
        for (ApiRiskScanner.Finding x : f.findings()) {
            if ("SECURITY".equals(x.kind())) {
                g.getItems().add(new EndItem(x.title(), x.target()));
            }
        }
        for (Item<EndpointChange> x : f.eps()) {
            if ("ADDED".equals(x.v().getChangeType())) {
                g.getItems().add(new EndItem(sig(x.v()) + "：不带登录态、换成别人的 token / 用户 ID 各调一次，确认被拦住",
                        "新接口；鉴权在网关 / 拦截器里，代码里看不出这个接口有没有配上"));
            }
        }
        if (g.getItems().isEmpty()) {
            g.setNote(f.backendAnalyzed() ? "没有新接口，也没扫到越权风险（鉴权在网关，没法从代码判断）" : "后端没分析成功，不知道");
        }
        return g;
    }

    private static void checkItems(EndGroup g, Facts f, Predicate<TicketCodeCheck.Item> which) {
        for (TicketCodeCheck.Item c : f.ticketChecks()) {
            if (which.test(c)) {
                g.getItems().add(new EndItem(c.hint(), "对账（" + statusWord(c.status()) + "）：" + nzs(c.ticket(), "")
                        + (c.code() == null ? "" : " / " + oneLine(c.code()))));
            }
        }
    }

    private static String statusWord(String status) {
        return switch (status) {
            case TicketCodeCheck.MATCH -> "对上了";
            case TicketCodeCheck.CODE_ONLY -> "代码改了单子没写";
            case TicketCodeCheck.TICKET_ONLY -> "单子写了代码没看到";
            default -> "没法自动判断";
        };
    }

    private static String nzs(String a, String b) {
        return a != null ? a : b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
