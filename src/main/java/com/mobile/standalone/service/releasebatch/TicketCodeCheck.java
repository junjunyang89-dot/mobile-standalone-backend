package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.DdlChange;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;
import com.mobile.standalone.service.apichange.MethodLogicDiff;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Ticket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求单写的 ↔ 代码改的，逐项对账：表、字段、配置、定时任务、涉及的项目。
 * <p>
 * 两边对不上的地方就是最容易出线上问题的地方：
 * 代码加了配置项、单子没写 → 上线没人去配；单子写了加字段、代码没用上 → 功能其实没做完；
 * 代码改了定时任务、单子没写 → 测试不知道要触发、线上调度中心没人改。
 * <p>
 * 只做<b>结构性</b>对账（表名、列名、配置 key、有没有定时任务），不让 AI 去猜「需求里这句话对应哪个接口」——
 * 猜不准的提示很快会被所有人忽略。
 */
public final class TicketCodeCheck {

    private TicketCodeCheck() {
    }

    /**
     * @param topic  表 / 字段 / 配置 / 定时任务 / 项目
     * @param status MATCH 对上了 / CODE_ONLY 代码改了单子没写 / TICKET_ONLY 单子写了代码没看到 / CHECK 没法自动判断
     * @param ticket 单子里怎么写的
     * @param code   代码里看到了什么
     * @param hint   要做什么
     */
    public record Item(String topic, String status, String ticket, String code, String hint) {
    }

    public static final String MATCH = "MATCH";
    public static final String CODE_ONLY = "CODE_ONLY";
    public static final String TICKET_ONLY = "TICKET_ONLY";
    public static final String CHECK = "CHECK";

    /**
     * 需要到待测代码里全文搜的词：单子 SQL 里加 / 改的列（下划线和驼峰两种写法）、单子里写的配置 key。
     * 搜索由调用方做（它手里有代码目录），结果传回 {@link #check} 的 mentions。
     */
    public static Set<String> wordsToSearch(Ticket t) {
        Set<String> out = new LinkedHashSet<>();
        for (DdlChange d : JavaApiSourceParser.parseDdl(t.getDbChanges())) {
            if (d.column() != null && !"DROP".equals(d.action())) {
                out.add(d.column());
                out.add(camel(d.column()));
            }
        }
        out.addAll(configKeys(t.getConfigChanges()));
        return out;
    }

    /**
     * @param mentions 词 → 待测代码里提到它的文件数；null 表示没拿到后端代码，涉及搜代码的项一律标 CHECK
     */
    public static List<Item> check(Ticket t, List<TestAssessmentBuilder.ProjectReport> reports,
                                   Map<String, Integer> mentions) {
        List<Item> out = new ArrayList<>();
        List<Report> rs = new ArrayList<>();
        for (TestAssessmentBuilder.ProjectReport pr : reports == null ? List.<TestAssessmentBuilder.ProjectReport>of() : reports) {
            if (pr.report() != null) {
                rs.add(pr.report());
            }
        }
        tables(out, t, rs);
        columns(out, t, mentions);
        newColumns(out, t, rs);
        configs(out, t, rs, mentions);
        schedules(out, t, rs);
        projects(out, t, reports);
        return out;
    }

    // ==================== 表 ====================

    private static void tables(List<Item> out, Ticket t, List<Report> rs) {
        Set<String> ticketTables = lower(JavaApiSourceParser.parseTables(t.getDbChanges()));
        JavaApiSourceParser.parseDdl(t.getDbChanges()).forEach(d -> ticketTables.add(d.table().toLowerCase(Locale.ROOT)));
        Map<String, TableChange> codeTables = new LinkedHashMap<>();
        Set<String> usedByEndpoints = new LinkedHashSet<>();
        for (Report r : rs) {
            r.getTables().forEach(x -> codeTables.putIfAbsent(x.getTable().toLowerCase(Locale.ROOT), x));
            r.getEndpoints().forEach(e -> e.getTables().forEach(x -> usedByEndpoints.add(x.toLowerCase(Locale.ROOT))));
        }
        for (String tb : ticketTables) {
            TableChange tc = codeTables.get(tb);
            if (tc != null) {
                out.add(new Item("表", MATCH, "SQL 里动了 " + tb, describe(tc),
                        "按单子里的 SQL 在测试库执行后再测，执行顺序：先 SQL 后发代码"));
            } else if (usedByEndpoints.contains(tb)) {
                out.add(new Item("表", MATCH, "SQL 里动了 " + tb, "这次改动的接口在读写 " + tb + "（SQL 本身没改）",
                        "先执行单子里的 SQL，再测读写这张表的接口"));
            } else {
                out.add(new Item("表", TICKET_ONLY, "SQL 里动了 " + tb, "这次的代码改动里没看到读写 " + tb,
                        "如果是刷数 / 初始化数据没问题，执行后核对数据；如果是加字段，确认代码有没有漏改"));
            }
        }
        for (TableChange tc : codeTables.values()) {
            String tb = tc.getTable().toLowerCase(Locale.ROOT);
            if (tc.isDdl() && !ticketTables.contains(tb)) {
                out.add(new Item("表", CODE_ONLY, hasText(t.getDbChanges()) ? "SQL 里没有 " + tb : "单子里没写 SQL",
                        "代码里有改表结构的 SQL：" + describe(tc),
                        "上线要不要执行这段 DDL、谁来执行、先于代码发布？单子里补上，否则线上会缺字段"));
            }
        }
    }

    private static String describe(TableChange tc) {
        if (tc.getDdlDetails() != null && !tc.getDdlDetails().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (DdlChange d : tc.getDdlDetails()) {
                parts.add(d.action() + (d.column() == null ? "" : " " + d.column()));
            }
            return tc.getTable() + "（" + String.join("、", parts) + "）";
        }
        return tc.getTable() + (tc.isDdl() ? "（结构有变）" : "（读写 SQL 改了）");
    }

    // ==================== 字段 ====================

    private static void columns(List<Item> out, Ticket t, Map<String, Integer> mentions) {
        for (DdlChange d : JavaApiSourceParser.parseDdl(t.getDbChanges())) {
            if (d.column() == null) {
                continue;
            }
            String col = d.table() + "." + d.column();
            String declared = d.action() + " " + col + (d.detail() == null || d.detail().isBlank() ? "" : " " + d.detail());
            if ("DROP".equals(d.action())) {
                out.add(new Item("字段", CHECK, declared, null,
                        "删列前确认代码、报表、离线任务里都不再用 " + d.column() + "；老版本服务还在跑时删列会直接报错"));
                continue;
            }
            if (mentions == null) {
                out.add(new Item("字段", CHECK, declared, "没拿到后端代码，没法确认有没有用上", "拿到代码后重新分析"));
                continue;
            }
            int n = mentions.getOrDefault(d.column(), 0) + mentions.getOrDefault(camel(d.column()), 0);
            if (n > 0) {
                out.add(new Item("字段", MATCH, declared, "代码里有 " + n + " 处提到 " + d.column() + " / " + camel(d.column()),
                        "造数据时这个字段取空值、默认值、边界值各一份，确认接口返回和页面展示"));
            } else {
                out.add(new Item("字段", TICKET_ONLY, declared, "待测代码里搜不到 " + d.column() + " 或 " + camel(d.column()),
                        "是预留字段，还是代码漏改了？找开发确认"));
            }
        }
    }

    /** 代码开始读写的新列，单子 SQL 里没加：单子里加了的已经由 {@link #columns} 对过 */
    private static void newColumns(List<Item> out, Ticket t, List<Report> rs) {
        for (Report r : rs) {
            for (TableChange tc : r.getTables()) {
                for (com.mobile.standalone.service.apichange.ApiChangeAnalyzer.NewColumn c : tc.getNewColumns()) {
                    if (ticketDeclares(t, tc.getTable(), c.getColumn())) {
                        continue;
                    }
                    String col = tc.getTable() + "." + c.getColumn();
                    out.add(new Item("字段", CODE_ONLY, hasText(t.getDbChanges()) ? "SQL 里没有 " + col : "单子里没写 SQL",
                            "代码开始读写 " + col + (c.isDdlInBranch() ? "，分支里有加字段的 SQL" : "，分支里也没有加字段的 SQL"),
                            c.isDdlInBranch() ? "把分支里的 SQL 补进单子，发版前先执行"
                                    : "找开发要加字段的 SQL 补进单子；不先执行，读写 " + tc.getTable() + " 的语句全部报错"));
                }
            }
        }
    }

    /** 单子的 SQL 里有没有给 table 加 column（ALTER ... ADD 或建表语句里带着） */
    static boolean ticketDeclares(Ticket t, String table, String column) {
        String sql = t.getDbChanges();
        if (!hasText(sql)) {
            return false;
        }
        for (DdlChange d : JavaApiSourceParser.parseDdl(sql)) {
            if (d.column() != null && d.column().equalsIgnoreCase(column) && !"DROP".equals(d.action())
                    && (d.table() == null || d.table().equalsIgnoreCase(table))) {
                return true;
            }
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.contains(table.toLowerCase(Locale.ROOT))
                && Pattern.compile("(?i)(?<![\\w])`?" + Pattern.quote(column) + "`?(?![\\w])").matcher(sql).find();
    }

    // ==================== 配置 ====================

    private static final Pattern CONFIG_KEY = Pattern.compile("\\b[a-zA-Z][\\w-]*(?:\\.[a-zA-Z0-9_-]+)+\\b");
    private static final Pattern NOT_KEY = Pattern.compile(
            "(?i).*\\.(com|cn|net|org|io|cc|java|xml|ya?ml|properties|sql|js|ts|vue|png|jpg|html|json|md)$|^v?\\d.*");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");

    static Set<String> configKeys(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = CONFIG_KEY.matcher(URL.matcher(text).replaceAll(" "));
        while (m.find()) {
            String k = m.group();
            if (!NOT_KEY.matcher(k).matches()) {
                out.add(k);
            }
        }
        return out;
    }

    private static void configs(List<Item> out, Ticket t, List<Report> rs, Map<String, Integer> mentions) {
        String declaredText = (t.getConfigChanges() == null ? "" : t.getConfigChanges()) + "\n"
                + (t.getNotes() == null ? "" : t.getNotes());
        Set<String> ticketKeys = configKeys(t.getConfigChanges());
        Map<String, MethodLogicDiff.Change> codeKeys = new LinkedHashMap<>();
        for (Report r : rs) {
            for (MethodLogicDiff.Change c : r.getLogicChanges()) {
                if ("CONFIG".equals(c.getKind()) && c.getRef() != null) {
                    codeKeys.putIfAbsent(c.getRef(), c);
                }
            }
        }
        for (Map.Entry<String, MethodLogicDiff.Change> e : codeKeys.entrySet()) {
            String key = e.getKey();
            MethodLogicDiff.Change c = e.getValue();
            String code = c.getClassName() + "：" + c.getTitle()
                    + (c.getAfter() == null ? "" : "（" + c.getAfter() + "）");
            if (declaredText.contains(key)) {
                out.add(new Item("配置", MATCH, "单子里写了 " + key, code,
                        "确认测试环境配置中心已经按单子改好，再测依赖这个配置的逻辑"));
            } else {
                out.add(new Item("配置", CODE_ONLY, "单子里没提到 " + key, code,
                        "各环境配置中心要不要加 / 改这个配置？单子里补上，否则上线后走默认值或启动失败"));
            }
        }
        for (String key : ticketKeys) {
            if (codeKeys.containsKey(key)) {
                continue;
            }
            if (mentions == null) {
                out.add(new Item("配置", CHECK, "单子里写了 " + key, "没拿到后端代码", "拿到代码后重新分析"));
            } else if (mentions.getOrDefault(key, 0) > 0) {
                out.add(new Item("配置", MATCH, "单子里写了 " + key, "代码里在读这个配置（读取方式这次没改）",
                        "测试环境配置中心按单子改好后，测依赖它的逻辑；改前改后各测一次"));
            } else {
                out.add(new Item("配置", TICKET_ONLY, "单子里写了 " + key, "待测代码里搜不到 " + key,
                        "key 是不是写错了，或者是别的服务 / 网关的配置？找开发确认"));
            }
        }
    }

    // ==================== 定时任务 ====================

    private static final Pattern SCHEDULE_WORD = Pattern.compile("(?i)定时|xxl-?job|cron|调度|\\bjob\\b|jobhandler");

    private static void schedules(List<Item> out, Ticket t, List<Report> rs) {
        List<String> declared = new ArrayList<>();
        for (String src : new String[]{t.getNotes(), t.getConfigChanges()}) {
            if (src == null) {
                continue;
            }
            for (String line : src.split("\n")) {
                String s = line.trim();
                if (!s.isEmpty() && SCHEDULE_WORD.matcher(s).find() && !s.contains("://")) {
                    declared.add(s.length() > 120 ? s.substring(0, 120) + "…" : s);
                }
            }
        }
        Set<String> code = new LinkedHashSet<>();
        for (Report r : rs) {
            for (MethodLogicDiff.Change c : r.getLogicChanges()) {
                if ("SCHEDULE".equals(c.getKind())
                        || ("METHOD_ADDED".equals(c.getKind()) && c.getHint() != null && c.getHint().contains("定时任务"))) {
                    code.add(c.getClassName() + "." + c.getMember() + "：" + c.getTitle());
                }
            }
            for (OtherChange o : r.getOtherChanges()) {
                if (o.getTrivial() == null && o.getKind() != null
                        && (o.getKind().equals("SCHEDULE") || o.getKind().equals("SERVICE_SCHEDULED"))) {
                    code.add(o.getFileName() + "（含定时任务的类改了）");
                }
            }
        }
        String d = declared.isEmpty() ? null : String.join("\n", declared.stream().distinct().limit(3).toList());
        String c = code.isEmpty() ? null : String.join("\n", code);
        if (d != null && c != null) {
            out.add(new Item("定时任务", MATCH, d, c,
                    "测试环境手动触发一次（或在调度中心执行一次），核对生成的数据；确认线上调度中心的配置跟着改"));
        } else if (c != null) {
            out.add(new Item("定时任务", CODE_ONLY, "单子里没提定时任务", c,
                    "测试环境怎么触发、线上调度中心要不要新建 / 修改任务？单子里补上"));
        } else if (d != null) {
            out.add(new Item("定时任务", TICKET_ONLY, d, "代码里没看到定时任务改动",
                    "可能只改了调度中心的配置（时间、参数），找开发确认改了什么、测试环境是否同步"));
        }
    }

    // ==================== 项目 ====================

    private static void projects(List<Item> out, Ticket t, List<TestAssessmentBuilder.ProjectReport> reports) {
        Map<Target, Report> byTarget = new LinkedHashMap<>();
        if (reports != null) {
            reports.forEach(pr -> byTarget.put(pr.target(), pr.report()));
        }
        for (Target x : t.getTargets()) {
            String name = TestAssessmentBuilder.nameOf(x) + "（" + x.getBranch() + "）";
            if (x.getProjectCode() == null) {
                out.add(new Item("项目", CHECK, name, "项目管理里没找到", "确认是哪个仓库，在项目列表里手动选上"));
                continue;
            }
            if (x.getCodeMissing() != null) {
                out.add(new Item("项目", CHECK, name, "没拿到代码：" + x.getCodeMissing(), "补上代码后重新分析，否则这个项目的改动没有被分析"));
                continue;
            }
            Report r = byTarget.get(x);
            if (ApiProjectKind.isBackend(x.getKind()) && r != null) {
                if (r.isAlreadyInBase()) {
                    out.add(new Item("项目", CHECK, name, "分支改动已经全部在基线里", "换合并前的基线重新分析"));
                } else if (r.getChangedFiles() == 0) {
                    out.add(new Item("项目", TICKET_ONLY, name, "和基线比没有任何改动", "分支写错了，还是代码还没提交？找开发确认"));
                }
            } else if (!ApiProjectKind.isBackend(x.getKind()) && x.getFront() != null && x.getFront().getChangedFiles() == 0) {
                out.add(new Item("项目", TICKET_ONLY, name, "前端 / 客户端代码和基线比没有改动", "分支写错了，还是代码还没提交？找开发确认"));
            }
        }
    }

    // ==================== 小工具 ====================

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        in.forEach(x -> out.add(x.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
