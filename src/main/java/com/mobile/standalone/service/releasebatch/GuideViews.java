package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ApiRiskScanner;
import com.mobile.standalone.service.apichange.ConcurrencyRiskScanner;
import com.mobile.standalone.service.apichange.EndpointDependencyResolver;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.apichange.MustTestGuide;
import com.mobile.standalone.service.apichange.TableTestHints;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Assessment;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndGroup;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndItem;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndpointCard;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.EndpointDiff;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Focus;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.LogicItem;
import com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.oneLine;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.sig;
import static com.mobile.standalone.service.releasebatch.TestAssessmentBuilder.where;

/**
 * 测试指引：同一批分析事实按测试的做事顺序重排——先逐个接口给结论（要不要测、怎么测），
 * 再按数据库 / 缓存 / 定时任务 / 配置这些面列出牵连。
 * <p>
 * 报告模板按章节组织，同一个接口散在五六节里；这里每个接口只出现在一张卡片上，
 * 跨接口的牵连只出现在影响面里，两边不重复。
 */
final class GuideViews {

    private GuideViews() {
    }

    static final int VERSION = 3;
    static final String YES = "YES";
    static final String NO = "NO";
    static final String UNKNOWN = "UNKNOWN";
    private static final int MAX_KEYS = 8;
    private static final int MAX_TABLES = 6;
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final List<String> VERDICT_ORDER = List.of("NEW", "BREAKING", "OFFLINE", "SHAPE", "ADD", "RERUN");

    // ==================== 逻辑改动挂到接口 ====================

    /**
     * 方法级改动没连上接口时，按「接口改动的实现类」补连：
     * 接口签名没变、只是 Service 改了的情况下，分析器知道接口用了哪个 Service，但方法改动那边不知道。
     * Service 接口叫 CommonService、实现类叫 CommonServiceImpl，两种写法都认。
     */
    static void linkLogic(List<LogicItem> items, List<Item<EndpointChange>> eps) {
        for (LogicItem li : items) {
            if (!li.getEndpoints().isEmpty() || li.getClassName() == null) {
                continue;
            }
            for (Item<EndpointChange> x : eps) {
                if (!x.project().equals(li.getProject()) || "REMOVED".equals(x.v().getChangeType())) {
                    continue;
                }
                if (x.v().getChangedImpls().stream().anyMatch(impl -> sameService(impl, li.getClassName()))) {
                    li.getEndpoints().add(sig(x.v()));
                }
            }
        }
    }

    static boolean sameService(String impl, String className) {
        if (impl == null || className == null) {
            return false;
        }
        String a = impl.trim();
        String b = className.trim();
        return a.equals(b) || (a + "Impl").equals(b) || a.equals(b + "Impl");
    }

    // ==================== 接口卡片 ====================

    static List<EndpointCard> endpointCards(AssessmentViews.Facts f, List<EndpointDiff> diffs, List<LogicItem> logic) {
        List<EndpointCard> out = new ArrayList<>();
        for (Item<EndpointChange> x : f.eps()) {
            EndpointChange e = x.v();
            String s = sig(e);
            EndpointCard c = new EndpointCard();
            c.setProject(x.project());
            c.setMethod(upper(e.getHttpMethod()));
            c.setPath(e.getPath());
            c.setSummary(e.getSummary());
            c.setChangeType(e.getChangeType());
            c.setRisk(e.getRisk());
            c.setTestCases(e.getTestCases());

            EndpointDiff d = diffs.stream()
                    .filter(o -> x.project().equals(o.getProject()) && s.equals(upper(o.getMethod()) + " " + o.getPath()))
                    .findFirst().orElse(null);
            if (d != null) {
                c.getChanges().addAll(d.getInputs());
                d.getOutputs().forEach(o -> c.getChanges().add("返回 " + o));
                if (!d.getImpls().isEmpty()) {
                    c.getChanges().add("实现改了：" + String.join("、", d.getImpls()));
                }
            }
            for (LogicItem li : logic) {
                if (x.project().equals(li.getProject()) && li.getEndpoints().contains(s)
                        && !"METHOD_CHANGED".equals(li.getKind())) {
                    c.getLogic().add(li.getClassName() + "." + li.getMember() + "：" + li.getTitle());
                }
            }
            if (c.getLogic().isEmpty()) {
                for (LogicItem li : logic) {
                    if (x.project().equals(li.getProject()) && li.getEndpoints().contains(s)) {
                        c.getLogic().add(li.getClassName() + "." + li.getMember() + "：" + li.getTitle());
                    }
                }
            }

            MustTestGuide.Item must = f.must().stream().map(Item::v)
                    .filter(i -> "ENDPOINT".equals(i.kind()) && s.equals(upper(i.httpMethod()) + " " + i.path()))
                    .findFirst().orElse(null);
            if (must != null) {
                c.setPriority(must.priority());
                for (String n : must.notes()) {
                    // 实现类已在「改了什么」，并发类已在「重点」
                    if (n.startsWith("改动的实现：") || n.startsWith("并发：")) {
                        continue;
                    }
                    c.getTests().add(n.startsWith("还没有接口用例") ? "测完把这条接口用例补上，否则下次还得靠人记" : n);
                }
            }
            if (c.getTests().isEmpty() && !"REMOVED".equals(e.getChangeType())) {
                c.getTests().add("真实入参调一次，断言业务码和关键字段，不要只看 HTTP 状态码");
            }

            for (ApiRiskScanner.Finding fd : f.findings()) {
                if (!s.equals(fd.target())) {
                    continue;
                }
                ("SECURITY".equals(fd.kind()) ? c.getSecurity() : c.getRisks()).add(fd.title());
            }
            if ("ADDED".equals(e.getChangeType())) {
                c.getSecurity().add("登录态：不带 token、带过期 token、换成别人的 token / 用户 ID 各调一次，确认被拦住且拿不到别人的数据");
                c.getSecurity().add("参数篡改：数字传负数 / 0 / 超大值 / 非数字，字符串传超长和特殊字符（' \" <script>），确认被拦住、不报 500、不落脏数据");
                if (WRITE_METHODS.contains(c.getMethod())) {
                    c.getSecurity().add("重复提交：同一请求快速连发两次，确认不会写两条、不会重复扣减 / 发放");
                }
            }
            List<String> tables = e.getTables();
            int related = e.getRelatedTableCount() > 0 ? Math.min(e.getRelatedTableCount(), tables.size()) : tables.size();
            c.getTables().addAll(tables.subList(0, Math.min(related, MAX_TABLES)));
            c.getCacheKeys().addAll(keysOf(e));
            for (ConcurrencyRiskScanner.Risk r : f.conc()) {
                if (s.equals(upper(r.httpMethod()) + " " + r.path())) {
                    c.getFocus().add(focus(r, c.getCacheKeys().size()));
                }
            }

            verdict(c, e);
            out.add(c);
        }
        out.sort(Comparator.comparingInt(c -> VERDICT_ORDER.indexOf(c.getVerdict())));
        return out;
    }

    /** 扫描器的标题是「类别：明细。原因」，缓存那类明细是一长串 key——key 在「查数据」里列，这里只留原因 */
    static Focus focus(ConcurrencyRiskScanner.Risk r, int keyCount) {
        String t = r.title() == null ? "" : r.title();
        int colon = t.indexOf('：');
        String rest = colon < 0 ? t : t.substring(colon + 1);
        return switch (r.kind() == null ? "" : r.kind()) {
            case "CACHE_RACE" -> new Focus("并发", "写接口同时在操作缓存与库",
                    rest.indexOf('。') < 0 ? rest : rest.substring(rest.indexOf('。') + 1).trim(), r.howToTest(), keyCount);
            case "DUPLICATE_SUBMIT" -> new Focus("重复提交", "同样的请求并发提交", rest, r.howToTest(), 0);
            case "LOST_UPDATE" -> new Focus("丢更新", "计数 / 金额类字段并发写", rest, r.howToTest(), 0);
            default -> new Focus("并发", colon < 0 ? t : t.substring(0, colon), rest, r.howToTest(), 0);
        };
    }

    private static void verdict(EndpointCard c, EndpointChange e) {
        int n = e.getTestCases();
        boolean shape = !e.getParams().isEmpty() || !e.getFields().isEmpty() || !e.getBodyFields().isEmpty();
        if ("REMOVED".equals(e.getChangeType())) {
            c.setVerdict("OFFLINE");
            c.setAction("接口下线：确认没有调用方还在调（线上老版本 App 调会直接失败）"
                    + (n > 0 ? "；已有 " + n + " 条用例要停用" : ""));
        } else if ("ADDED".equals(e.getChangeType())) {
            c.setVerdict("NEW");
            c.setAction("新接口：必测。生成用例到分支，测正常流程、参数校验和下面的安全项");
        } else if ("BREAKING".equals(e.getRisk())) {
            c.setVerdict("BREAKING");
            c.setAction("不兼容改动：必测，并回归所有调用方（包括线上老版本 App / H5）"
                    + (n > 0 ? "；已有 " + n + " 条用例按新出入参改完再跑" : "；还没有用例，生成到分支"));
        } else if (shape) {
            c.setVerdict("SHAPE");
            c.setAction(n > 0 ? "出入参有变：已有 " + n + " 条用例按新字段更新断言后重跑"
                    : "出入参有变，还没有用例：生成用例到分支，覆盖新字段");
        } else if (n > 0) {
            c.setVerdict("RERUN");
            c.setAction("只改了实现：按新逻辑重跑已有 " + n + " 条用例，核对业务结果，不要只看状态码");
        } else {
            c.setVerdict("ADD");
            c.setAction("只改了实现，还没有用例：生成用例到分支；用老请求打一遍，对比新老返回");
        }
    }

    private static List<String> keysOf(EndpointChange e) {
        Set<String> out = new LinkedHashSet<>();
        for (EndpointDependencyResolver.RedisKeyRef k : e.getRedisKeys()) {
            String v = k.getValue() != null && !k.getValue().isBlank() ? k.getValue() : k.getConstant();
            if (v != null && !v.isBlank()) {
                out.add(v + (k.isValueChanged() ? "（key 改了）" : ""));
            }
        }
        return new ArrayList<>(out);
    }

    // ==================== 影响面 ====================

    /** 一个后端都没有的单返回空：这几格只对后端有意义 */
    static List<EndGroup> impacts(AssessmentViews.Facts f, Assessment a, boolean hasBackend) {
        if (!hasBackend) {
            return List.of();
        }
        List<EndGroup> out = new ArrayList<>();
        out.add(db(f));
        out.add(cache(f));
        out.add(finish(AssessmentViews.job(f), f));
        out.add(mq(f, a));
        out.add(config(f));
        out.add(security(f));
        out.add(legacy(f, a));
        return out;
    }

    private static EndGroup group(String key, String label) {
        EndGroup g = new EndGroup();
        g.setKey(key);
        g.setLabel(label);
        return g;
    }

    /** 有条目就是涉及；没条目时，后端没分析成功是「没分析到」，不能说成「不涉及」 */
    private static EndGroup finish(EndGroup g, AssessmentViews.Facts f) {
        List<EndItem> uniq = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EndItem i : g.getItems()) {
            if (seen.add(i.what())) {
                uniq.add(i);
            }
        }
        g.setItems(uniq);
        if (!uniq.isEmpty()) {
            g.setState(YES);
            g.setNote(null);
        } else if (!f.backendAnalyzed()) {
            g.setState(UNKNOWN);
            g.setNote("后端没分析成功，这一项不知道");
        } else {
            g.setState(NO);
        }
        return g;
    }

    private static EndGroup db(AssessmentViews.Facts f) {
        EndGroup g = group("db", "数据库");
        if (f.ticketSql()) {
            g.getItems().add(new EndItem("先在测试库执行单子里的 SQL，再部署代码；执行后查一遍表结构 / 数据", "单子里有 SQL"));
        }
        for (Item<TableChange> x : f.tables()) {
            TableChange tc = x.v();
            for (TableTestHints.Hint h : tc.getTestHints()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + "：" + h.title(), h.basis()));
            }
            if (tc.getTestHints().isEmpty()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + "：造正常 / 边界 / 空数据各一份，调接口后查表核对",
                        tc.isDdl() ? "表结构改了" : "读写这张表的 SQL 改了"));
            }
            if (tc.isDdl()) {
                g.getItems().add(new EndItem("表 " + tc.getTable() + " 的历史数据：新字段是默认值 / NULL 时，接口和页面能正常展示",
                        "老数据不会自动补新字段"));
            }
        }
        for (Item<MethodLogicDiff.Change> x : f.logic("WRITE")) {
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(), x.v().getTitle()));
        }
        checks(g, f, "表", "字段");
        g.setNote("没看到表结构和读写 SQL 的改动");
        return finish(g, f);
    }

    private static EndGroup cache(AssessmentViews.Facts f) {
        EndGroup g = group("cache", "缓存");
        for (EndpointDependencyResolver.RedisKeyRef k : f.changedKeys()) {
            g.getItems().add(new EndItem("缓存 key " + k.getConstant() + " 改了：上线时新老 key 并存，改数据后确认读到新值；老 key 要不要清",
                    k.getOldValue() + " → " + k.getValue()));
        }
        for (Item<MethodLogicDiff.Change> x : f.logic("CACHE")) {
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(), x.v().getTitle()));
        }
        // 实现改了、又读写缓存的接口：同一组 key 的接口合成一条，免得十几个 key 每个接口抄一遍
        Map<List<String>, List<String>> byKeys = new LinkedHashMap<>();
        for (Item<EndpointChange> x : f.eps()) {
            if ("REMOVED".equals(x.v().getChangeType())) {
                continue;
            }
            List<String> keys = keysOf(x.v()).stream().map(k -> k.replace("（key 改了）", "")).toList();
            if (!keys.isEmpty()) {
                byKeys.computeIfAbsent(keys, k -> new ArrayList<>()).add(sig(x.v()));
            }
        }
        for (Map.Entry<List<String>, List<String>> en : byKeys.entrySet()) {
            List<String> keys = en.getKey();
            String shown = String.join("、", keys.subList(0, Math.min(MAX_KEYS, keys.size())))
                    + (keys.size() > MAX_KEYS ? " 等 " + keys.size() + " 个" : "");
            List<String> eps = en.getValue();
            g.getItems().add(new EndItem("调完接口去「测试工具 → Redis」查这些 key：" + shown
                    + "。写接口调完 key 要被更新或删掉；再调读接口，确认拿到的不是旧值",
                    String.join("、", eps.subList(0, Math.min(3, eps.size()))) + (eps.size() > 3 ? " 等 " + eps.size() + " 个接口" : "")
                            + " 这次改了，并且读写这些缓存"));
        }
        g.setNote("改动的接口没用到缓存，也没看到缓存读写的改动");
        return finish(g, f);
    }

    private static EndGroup mq(AssessmentViews.Facts f, Assessment a) {
        EndGroup g = group("mq", "消息 / 外部调用");
        for (Item<MethodLogicDiff.Change> x : f.logic("MQ", "REMOTE")) {
            String what = x.v().getAfter() != null ? x.v().getAfter() : x.v().getBefore();
            g.getItems().add(new EndItem(where(x) + "：" + x.v().getHint(),
                    x.v().getTitle() + (what == null ? "" : " " + oneLine(what))));
        }
        for (String s : a.getOtherChanges()) {
            if (s.startsWith("消息队列相关：")) {
                g.getItems().add(new EndItem("确认消息格式和消费方约定一致：老消息新代码能消费、新消息老消费方不报错；用「测试工具 → Kafka」看实际发出的消息", s));
            } else if (s.startsWith("第三方 / 远程调用相关：")) {
                g.getItems().add(new EndItem("确认调用参数和对方约定一致；对方超时 / 报错时这边有降级、不把异常抛给用户", s));
            }
        }
        g.setNote("代码里没认出消息、远程调用的改动");
        return finish(g, f);
    }

    private static EndGroup config(AssessmentViews.Facts f) {
        EndGroup g = AssessmentViews.config(f);
        g.setKey("config");
        g.setLabel("配置（Apollo / 配置文件）");
        g.setNote(null);
        for (Item<OtherChange> x : f.others()) {
            if ("CONFIG".equals(x.v().getKind())) {
                g.getItems().add(new EndItem("配置文件 " + x.v().getFileName() + " 改了：测试环境先配上；发版前确认生产的 Apollo / 配置中心同步了",
                        x.project() + " " + x.v().getFileName()));
            }
        }
        String ticketConfig = f.t().getConfigChanges();
        if (ticketConfig != null && !ticketConfig.isBlank()) {
            g.getItems().add(new EndItem("单子里写的配置：" + oneLine(ticketConfig) + "。测试环境 Apollo 先配上再测；开关关掉时功能要能退回老逻辑",
                    "JIRA 备注里有配置项"));
        }
        g.setNote("代码里没认出配置改动，单子里也没写");
        return finish(g, f);
    }

    /** 接口自己的安全项写在接口卡片里，这里只放挂不到接口上的（Mapper 里的 SQL 拼接这类） */
    private static EndGroup security(AssessmentViews.Facts f) {
        EndGroup g = group("security", "安全");
        Set<String> sigs = new LinkedHashSet<>();
        f.eps().forEach(x -> sigs.add(sig(x.v())));
        for (ApiRiskScanner.Finding x : f.findings()) {
            if ("SECURITY".equals(x.kind()) && !sigs.contains(x.target())) {
                g.getItems().add(new EndItem(x.title(), x.target()));
            }
        }
        boolean inCards = f.eps().stream().anyMatch(x -> "ADDED".equals(x.v().getChangeType()))
                || f.findings().stream().anyMatch(x -> "SECURITY".equals(x.kind()) && sigs.contains(x.target()));
        g.setNote(inCards ? "接口相关的安全项写在上面各接口里" : "没有新接口，也没扫到越权 / 注入风险（鉴权在网关，代码里看不出）");
        return finish(g, f);
    }

    private static EndGroup legacy(AssessmentViews.Facts f, Assessment a) {
        EndGroup g = group("legacy", "老功能牵连");
        for (String s : a.getHiddenRisks()) {
            if (s.startsWith("公共返回体") || s.contains("没连到接口") || s.startsWith("数据访问")) {
                g.getItems().add(new EndItem("回归用到它的老接口 / 老页面", s));
            }
        }
        for (String s : a.getRelatedRegress()) {
            g.getItems().add(new EndItem("回归 " + s, "这次没改它，但和改动共用表 / 被改动的接口牵连"));
        }
        g.setNote("改动都挂在具体接口上，没看到会牵连其它老功能的公共代码");
        return finish(g, f);
    }

    private static void checks(EndGroup g, AssessmentViews.Facts f, String... topics) {
        Set<String> ts = Set.of(topics);
        for (TicketCodeCheck.Item c : f.ticketChecks()) {
            if (ts.contains(c.topic()) && !TicketCodeCheck.MATCH.equals(c.status())) {
                g.getItems().add(new EndItem(c.hint(), "单子和代码对不上：" + (c.ticket() == null ? "" : c.ticket())
                        + (c.code() == null ? "" : " / " + oneLine(c.code()))));
            }
        }
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
