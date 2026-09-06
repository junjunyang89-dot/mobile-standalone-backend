package com.mobile.standalone.service.apichange;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.OtherChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把分析报告收成测试同学直接能用的「必测清单 + 注意事项」。
 * <p>
 * 报告里已经有变动、风险、表、并发，但散在六七节里。测试排期要的是：
 * 先测谁、为什么必测、这一条要注意什么。这里只做归类和措辞，不重跑 git。
 */
public final class MustTestGuide {

    private MustTestGuide() {
    }

    public record Item(String priority, String kind, String httpMethod, String path,
                       String title, String why, List<String> notes) {
    }

    public record Guide(int p0, int p1, int p2, String hint, List<Item> items) {
    }

    public static Guide of(Report report) {
        if (report == null) {
            return new Guide(0, 0, 0, "没有分析报告", List.of());
        }
        List<Item> items = new ArrayList<>();
        for (EndpointChange e : report.getEndpoints()) {
            items.add(fromEndpoint(e, report.getConcurrencyRisks()));
        }
        for (TableChange t : report.getTables()) {
            if (t.isDdl() || (t.getTestHints() != null && !t.getTestHints().isEmpty())) {
                items.add(fromTable(t));
            }
        }
        for (OtherChange o : report.getOtherChanges()) {
            items.add(fromOther(o));
        }
        items.sort(Comparator
                .comparingInt((Item i) -> rank(i.priority()))
                .thenComparing(i -> "ENDPOINT".equals(i.kind()) ? 0 : 1));
        int p0 = (int) items.stream().filter(i -> "P0".equals(i.priority())).count();
        int p1 = (int) items.stream().filter(i -> "P1".equals(i.priority())).count();
        int p2 = (int) items.stream().filter(i -> "P2".equals(i.priority())).count();
        return new Guide(p0, p1, p2, hint(p0, p1, p2), List.copyOf(items));
    }

    static String hint(int p0, int p1, int p2) {
        if (p0 + p1 + p2 == 0) {
            return "这次扫不到必测项——要么没接口变更，要么基线选错了";
        }
        return "先测 P0（老客户端会坏 / 下线 / 并发写），再测 P1（新接口、实现变了、表结构），"
                + "P2 是加字段和旁路，有空冒烟。P0 " + p0 + " / P1 " + p1 + " / P2 " + p2;
    }

    private static Item fromEndpoint(EndpointChange e, List<ConcurrencyRiskScanner.Risk> risks) {
        String sig = sig(e.getHttpMethod(), e.getPath());
        List<String> notes = new ArrayList<>();
        List<String> why = new ArrayList<>();

        if ("REMOVED".equals(e.getChangeType())) {
            why.add("接口在 master 上已经没有了");
            notes.add("确认是有意下线还是合错分支；老包如果还在调，会直接失败");
            notes.add("挂在它下面的旧用例先别删，等产品确认");
            return item("P0", e, "接口下线，必须确认", join(why), notes);
        }
        if ("BREAKING".equals(e.getRisk())) {
            why.add("不兼容变更，老客户端会坏");
            addBreakingNotes(e, notes, why);
        } else if ("NEW".equals(e.getRisk()) || "ADDED".equals(e.getChangeType())) {
            why.add("本次新增的接口");
            notes.add("先打一条真实入参，断言业务码成功，不要只看 HTTP 200");
            notes.add("缺必填、类型错误时错误码和文案要能看懂");
        } else if ("IMPL".equals(e.getRisk())) {
            why.add("路径和入参没变，但实现类改了，业务结果可能变");
            notes.add("用线上同等请求（老入参）打测试环境，对比关键业务字段，不要只比状态码");
            if (e.getChangedImpls() != null && !e.getChangedImpls().isEmpty()) {
                notes.add("改动的实现：" + String.join("、", e.getChangedImpls()));
            }
        } else {
            why.add("只加了返回字段，一般兼容");
            notes.add("抽查老包或旧解析不会因多字段崩溃即可");
        }

        addParamNotes(e, notes, why);
        addFieldNotes(e, notes, why);
        addTableCacheNotes(e, notes);

        boolean concRisk = false;
        if (risks != null) {
            for (ConcurrencyRiskScanner.Risk r : risks) {
                if (!sig.equals(sig(r.httpMethod(), r.path()))) {
                    continue;
                }
                if ("RISK".equals(r.level())) {
                    concRisk = true;
                }
                notes.add("并发：" + r.title() + (r.howToTest() == null ? "" : " —— " + r.howToTest()));
            }
        }

        if (e.getTestCases() <= 0) {
            notes.add("还没有接口用例，测完把这一条补上，否则下次还得靠人记");
        } else {
            notes.add("已有 " + e.getTestCases() + " 条用例：先看是不是只验了 200，再决定能不能当回归");
        }

        String priority = priorityOf(e, concRisk);
        String title = titleOf(e);
        return item(priority, e, title, join(why), notes);
    }

    private static void addBreakingNotes(EndpointChange e, List<String> notes, List<String> why) {
        for (FieldChange f : e.getFields()) {
            if ("REMOVED".equals(f.getChangeType())) {
                notes.add("返回少了字段 " + f.getName() + "：用老包或旧断言打，看解析会不会挂");
            } else if ("TYPE_CHANGED".equals(f.getChangeType())) {
                notes.add("返回字段 " + f.getName() + " 类型 " + f.getOldType() + " → " + f.getNewType()
                        + "：前端按老类型解析会静默错，抓包对照新旧各打一次");
            }
        }
        for (FieldChange f : e.getBodyFields()) {
            if ("REMOVED".equals(f.getChangeType()) || "TYPE_CHANGED".equals(f.getChangeType())) {
                notes.add("请求体 " + f.getName() + " " + changeWord(f.getChangeType())
                        + "：老客户端按旧 body 打，看是 400 还是被忽略");
            }
        }
        for (ParamChange p : e.getParams()) {
            if ("ADDED".equals(p.getChangeType()) && p.isRequired()) {
                why.add("新增必填入参 " + p.getName());
                notes.add("故意不传 " + p.getName() + "，老客户端会 400——和开发确认是不是有意不兼容");
                notes.add("带上新参数再打一条正向");
            } else if ("REMOVED".equals(p.getChangeType())) {
                notes.add("入参 " + p.getName() + " 删了：老包如果还传，确认会被忽略还是报错");
            } else if ("TYPE_CHANGED".equals(p.getChangeType()) || "REQUIRED_CHANGED".equals(p.getChangeType())) {
                notes.add("入参 " + p.getName() + " " + changeWord(p.getChangeType())
                        + "：用旧类型/旧可选性各打一次");
            }
        }
    }

    private static void addParamNotes(EndpointChange e, List<String> notes, List<String> why) {
        if ("BREAKING".equals(e.getRisk())) {
            return;
        }
        for (ParamChange p : e.getParams()) {
            if ("ADDED".equals(p.getChangeType())) {
                notes.add("新入参 " + p.getName() + (p.isRequired() ? "（必填）" : "（可选）")
                        + "：有就测，没有就确认默认值");
            }
        }
    }

    private static void addFieldNotes(EndpointChange e, List<String> notes, List<String> why) {
        if ("BREAKING".equals(e.getRisk())) {
            return;
        }
        for (FieldChange f : e.getFields()) {
            if ("ADDED".equals(f.getChangeType())) {
                notes.add("返回多了 " + f.getName() + "：确认有值、空值、和页面要展示的是否一致");
            }
        }
    }

    private static void addTableCacheNotes(EndpointChange e, List<String> notes) {
        if (e.getTables() != null && !e.getTables().isEmpty()) {
            int n = e.getRelatedTableCount() > 0 ? e.getRelatedTableCount() : e.getTables().size();
            List<String> focus = e.getTables().subList(0, Math.min(n, e.getTables().size()));
            notes.add("读写表 " + String.join("、", focus) + "：写接口造边界数据，读接口确认不是脏缓存");
        }
        if (e.getRedisKeys() != null && !e.getRedisKeys().isEmpty()) {
            boolean changed = e.getRedisKeys().stream().anyMatch(k -> k != null && k.isValueChanged());
            if (changed) {
                notes.add("缓存 key 变了：测环境可能新老数据混用，对不上先清缓存再打");
            } else {
                notes.add("走缓存：结果不对时先看是逻辑错还是缓存没失效");
            }
        }
    }

    private static Item fromTable(TableChange t) {
        List<String> notes = new ArrayList<>();
        if (t.getDdlDetails() != null) {
            for (DdlChange d : t.getDdlDetails()) {
                notes.add((d.action() == null ? "DDL" : d.action()) + " "
                        + (d.column() == null ? "" : d.column()) + " "
                        + (d.detail() == null ? "" : d.detail()));
            }
        }
        if (t.getTestHints() != null) {
            for (TableTestHints.Hint h : t.getTestHints()) {
                notes.add(h.title() + "（" + h.basis() + "）");
            }
        }
        if (t.getRemark() != null && !t.getRemark().isBlank()) {
            notes.add(t.getRemark());
        }
        if (t.getUsedByEndpoints() != null && !t.getUsedByEndpoints().isEmpty()) {
            notes.add("先测这些接口：" + String.join("、", t.getUsedByEndpoints()));
        }
        if (notes.isEmpty()) {
            notes.add("表结构变了，读写它的接口都要回归造数");
        }
        String priority = t.isDdl() ? "P1" : "P2";
        return new Item(priority, "TABLE", null, null,
                "表 " + t.getTable() + (t.isDdl() ? " 结构变了" : " 要注意"),
                t.isDdl() ? "DDL 变更，唯一/可空/类型都会让接口表现变" : "这张表有测试注意事项",
                notes);
    }

    private static Item fromOther(OtherChange o) {
        String kind = o.getKind() == null ? "OTHER" : o.getKind();
        String title = switch (kind) {
            case "SCHEDULE" -> "定时任务改了";
            case "CACHE" -> "缓存相关改了";
            case "CONFIG" -> "配置改了";
            default -> "旁路改动（不是接口签名）";
        };
        String why = "接口列表可能看起来干净，但测试环境的数据或缓存会变";
        List<String> notes = new ArrayList<>();
        if (o.getHint() != null && !o.getHint().isBlank()) {
            notes.add(o.getHint());
        }
        notes.add(o.getFile() == null ? "看这次 diff 里的非接口文件" : o.getFile());
        String priority = "SCHEDULE".equals(kind) || "CACHE".equals(kind) ? "P1" : "P2";
        return new Item(priority, "OTHER", null, null, title, why, notes);
    }

    private static String priorityOf(EndpointChange e, boolean concRisk) {
        if (concRisk || "BREAKING".equals(e.getRisk()) || "REMOVED".equals(e.getChangeType())) {
            return "P0";
        }
        if ("NEW".equals(e.getRisk()) || "ADDED".equals(e.getChangeType()) || "IMPL".equals(e.getRisk())) {
            return "P1";
        }
        if (e.getTestCases() <= 0 && !"COMPATIBLE".equals(e.getRisk())) {
            return "P1";
        }
        return "P2";
    }

    private static String titleOf(EndpointChange e) {
        if ("REMOVED".equals(e.getChangeType())) {
            return "确认下线";
        }
        if ("BREAKING".equals(e.getRisk())) {
            return "不兼容，老客户端会坏";
        }
        if ("NEW".equals(e.getRisk()) || "ADDED".equals(e.getChangeType())) {
            return "新接口，补正向和缺参";
        }
        if ("IMPL".equals(e.getRisk())) {
            return "实现变了，回归业务结果";
        }
        return "兼容变更，冒烟即可";
    }

    private static Item item(String priority, EndpointChange e, String title, String why, List<String> notes) {
        return new Item(priority, "ENDPOINT", e.getHttpMethod(), e.getPath(), title, why, List.copyOf(notes));
    }

    private static String changeWord(String t) {
        if ("REMOVED".equals(t)) {
            return "被删";
        }
        if ("TYPE_CHANGED".equals(t)) {
            return "改了类型";
        }
        if ("REQUIRED_CHANGED".equals(t)) {
            return "必填/可选变了";
        }
        return t == null ? "变更" : t;
    }

    private static String join(List<String> parts) {
        return String.join("；", parts);
    }

    private static String sig(String method, String path) {
        return String.valueOf(method).toUpperCase(Locale.ROOT) + " " + String.valueOf(path);
    }

    private static int rank(String p) {
        if ("P0".equals(p)) {
            return 0;
        }
        if ("P1".equals(p)) {
            return 1;
        }
        return 2;
    }
}
