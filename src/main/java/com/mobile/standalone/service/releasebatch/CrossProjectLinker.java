package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FileChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.Report;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.TableChange;
import com.mobile.standalone.service.apichange.ApiEndpoint;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.apitest.SourceControllerMatcher;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.FrontChange;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Link;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.ProjectNode;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.Target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一个单涉及的几个项目之间谁调谁：前端调后端、后端 Feign 调后端、两个服务读写同一张表。
 * <p>
 * 每个项目各自的对比报告只回答「我改了什么」；测试真正要的是「改动对不对得上」——
 * 后端改了字段前端有没有跟、前端新调的接口后端有没有、新接口到底谁在用。
 */
public final class CrossProjectLinker {

    public static final String RISK = "RISK";
    public static final String JOINT = "JOINT";
    public static final String CHECK = "CHECK";
    public static final String INFO = "INFO";
    private static final List<String> ORDER = List.of(RISK, JOINT, CHECK, INFO);
    private static final int MAX_REFS = 5;

    private CrossProjectLinker() {
    }

    /**
     * @param target       单子里的项目（含前端对比结果 / 缺代码原因）
     * @param report       后端对比报告；不是后端或没分析成功为 null
     * @param headCalls    待测代码里的全部调用点
     * @param inventory    后端待测代码里的全部接口
     * @param changedFiles 这次改动的文件（相对仓库根）
     */
    public record Project(Target target, Report report, List<CallSite> headCalls, List<ApiEndpoint> inventory,
                          Set<String> changedFiles) {
        String code() {
            return target.getProjectCode() == null ? "?" + target.getRawProject() : target.getProjectCode();
        }

        String name() {
            return TestAssessmentBuilder.nameOf(target);
        }

        boolean hasCode() {
            return target.getProjectCode() != null && target.getCodeMissing() == null;
        }

        boolean backend() {
            return target.getProjectCode() != null && ApiProjectKind.isBackend(target.getKind());
        }
    }

    public record Result(List<ProjectNode> nodes, List<Link> links) {
    }

    private record Changed(Project owner, EndpointChange e) {
        String key() {
            return owner.code() + " " + e.getHttpMethod() + " " + e.getPath();
        }
    }

    public static Result link(List<Project> projects) {
        List<ProjectNode> nodes = nodes(projects);
        Set<String> nodeIds = new HashSet<>();
        nodes.forEach(n -> nodeIds.add(n.getId()));
        List<Link> links = new ArrayList<>();

        List<Changed> changed = new ArrayList<>();
        for (Project p : projects) {
            if (p.report() != null) {
                p.report().getEndpoints().forEach(e -> changed.add(new Changed(p, e)));
            }
        }

        // ---- 调用方 × 本次改了的接口 ----
        Set<String> calledKeys = new HashSet<>();
        Set<String> matchedCalls = new HashSet<>();
        for (Changed c : changed) {
            Map<String, List<CallSite>> byCaller = new LinkedHashMap<>();
            Map<String, Project> callerOf = new LinkedHashMap<>();
            for (Project q : projects) {
                for (CallSite s : q.headCalls()) {
                    if (q == c.owner() && CallSiteScanner.BACK.equals(s.getSide())) {
                        continue;
                    }
                    if (matches(s.getPath(), c.e().getPath())) {
                        String id = nodeId(q, s.getSide());
                        byCaller.computeIfAbsent(id, k -> new ArrayList<>()).add(s);
                        callerOf.put(id, q);
                        matchedCalls.add(q.code() + "|" + s.getFile() + "|" + s.getPath());
                    }
                }
            }
            if (!byCaller.isEmpty()) {
                calledKeys.add(c.key());
            }
            byCaller.forEach((id, sites) -> links.add(callLink(callerOf.get(id), id, sites, c)));
        }

        // ---- 新接口没人调 ----
        List<String> silentFronts = projects.stream()
                .filter(p -> !p.hasCode() && p.target().getProjectCode() != null && !p.backend())
                .map(Project::name).toList();
        for (Changed c : changed) {
            if ("ADDED".equals(c.e().getChangeType()) && !calledKeys.contains(c.key())) {
                Link l = base(null, "（本单代码里没找到调用方）", nodeId(c.owner(), CallSiteScanner.BACK), c.owner().name(), c.e());
                l.setLevel(CHECK);
                if (silentFronts.isEmpty()) {
                    l.setTitle("新接口在本单的代码里没找到调用方：确认是给 App / 其他端用，还是前端还没接");
                } else {
                    l.setInferred(true);
                    l.setTitle("新接口在本单的代码里没找到调用方；" + String.join("、", silentFronts) + " 缺代码，可能是它们在调");
                }
                links.add(l);
            }
        }

        // ---- 前端新写 / 删掉的调用，但不是本次改的接口 ----
        List<Project> missingBackends = projects.stream().filter(p -> p.backend() && !p.hasCode()).toList();
        for (Project q : projects) {
            FrontChange fc = q.target().getFront();
            if (fc == null) {
                continue;
            }
            Map<String, Link> byPath = new LinkedHashMap<>();
            for (CallSite s : fc.getAddedCalls()) {
                if (matchedCalls.contains(q.code() + "|" + s.getFile() + "|" + s.getPath())) {
                    continue;
                }
                String fromId = nodeId(q, s.getSide());
                Hit hit = findInInventory(projects, s.getPath());
                Link l;
                if (hit != null) {
                    l = base(fromId, q.name(), nodeId(hit.owner(), CallSiteScanner.BACK), hit.owner().name(), null);
                    l.setMethod(hit.e().httpMethod());
                    l.setPath(hit.e().path());
                    l.setSummary(hit.e().summary());
                    l.setLevel(INFO);
                    l.setTitle("新调用了 " + hit.owner().name() + " 的已有接口（后端这次没改），回归一下这个页面");
                } else if (!s.isApiLike()) {
                    continue;
                } else if (!missingBackends.isEmpty()) {
                    Project m = missingBackends.get(0);
                    l = base(fromId, q.name(), nodeId(m, CallSiteScanner.BACK), m.name(), null);
                    l.setPath(s.getRaw());
                    l.setLevel(CHECK);
                    l.setInferred(true);
                    l.setTitle("新调用的接口在有代码的后端里找不到；"
                            + String.join("、", missingBackends.stream().map(Project::name).toList())
                            + " 缺代码，可能是它们提供的");
                } else {
                    l = base(fromId, q.name(), null, "（本单后端里没找到）", null);
                    l.setPath(s.getRaw());
                    l.setLevel(CHECK);
                    l.setTitle("新调用的接口在本单的后端里找不到：确认对应服务已上线、路径没写错");
                }
                l.setCallChange("NEW_CALL");
                Link prev = byPath.putIfAbsent(l.getFromNode() + "|" + nz(l.getToNode()) + "|" + s.getPath(), l);
                (prev == null ? l : prev).getRefs().add(ref(s));
            }
            for (CallSite s : fc.getRemovedCalls()) {
                Hit hit = findInInventory(projects, s.getPath());
                if (hit == null) {
                    Changed gone = changed.stream().filter(c -> "REMOVED".equals(c.e().getChangeType())
                            && matches(s.getPath(), c.e().getPath())).findFirst().orElse(null);
                    if (gone == null) {
                        continue;
                    }
                    Link l = base(nodeId(q, s.getSide()), label(q, s.getSide()),
                            nodeId(gone.owner(), CallSiteScanner.BACK), gone.owner().name(), gone.e());
                    l.setCallChange("REMOVED_CALL");
                    l.setLevel(INFO);
                    l.setTitle("前端删了调用，后端也下线了这个接口：确认页面 / 菜单入口一起去掉了，老版本页面别再请求它");
                    Link prev = byPath.putIfAbsent(l.getFromNode() + "|gone|" + gone.key(), l);
                    (prev == null ? l : prev).getRefs().add(ref(s));
                    continue;
                }
                Link l = base(nodeId(q, s.getSide()), q.name(), nodeId(hit.owner(), CallSiteScanner.BACK),
                        hit.owner().name(), null);
                l.setMethod(hit.e().httpMethod());
                l.setPath(hit.e().path());
                l.setSummary(hit.e().summary());
                l.setCallChange("REMOVED_CALL");
                l.setLevel(INFO);
                l.setTitle("这次不再调用这个接口了：确认页面上对应功能是有意去掉的");
                Link prev = byPath.putIfAbsent(l.getFromNode() + "|-|" + s.getPath(), l);
                (prev == null ? l : prev).getRefs().add(ref(s));
            }
            byPath.values().forEach(l -> {
                l.setRefs(l.getRefs().stream().limit(MAX_REFS).toList());
                links.add(l);
            });
        }

        // ---- 两个后端读写同一张表 ----
        Map<String, List<Project>> byTable = new LinkedHashMap<>();
        for (Project p : projects) {
            if (p.report() == null) {
                continue;
            }
            for (TableChange t : p.report().getTables()) {
                String key = tableKey(t.getTable());
                List<Project> list = byTable.computeIfAbsent(key, k -> new ArrayList<>());
                if (!list.contains(p)) {
                    list.add(p);
                }
            }
        }
        byTable.forEach((table, owners) -> {
            for (int i = 1; i < owners.size(); i++) {
                Link l = base(nodeId(owners.get(i), CallSiteScanner.BACK), owners.get(i).name(),
                        nodeId(owners.get(0), CallSiteScanner.BACK), owners.get(0).name(), null);
                l.setPath("表 " + table);
                l.setLevel(INFO);
                l.setTitle("两个服务这次都改了表 " + table + " 的读写，一边的数据另一边要能读对");
                links.add(l);
            }
        });

        // ---- 缺代码的前端 / 客户端 ----
        long changedCount = changed.stream().filter(c -> !"REMOVED".equals(c.e().getChangeType())).count();
        for (Project p : projects) {
            if (p.hasCode() || p.target().getProjectCode() == null || p.backend() || changedCount == 0) {
                continue;
            }
            Set<Project> owners = new LinkedHashSet<>();
            changed.forEach(c -> owners.add(c.owner()));
            for (Project o : owners) {
                Link l = base(nodeId(p, sideOfKind(p)), p.name(), nodeId(o, CallSiteScanner.BACK), o.name(), null);
                l.setLevel(CHECK);
                l.setInferred(true);
                l.setTitle(p.name() + " 缺代码（" + p.target().getCodeMissing() + "），没法确认它调不调 "
                        + o.name() + " 这次改的接口");
                links.add(l);
            }
        }

        Map<String, String> nodeNames = new LinkedHashMap<>();
        nodes.forEach(n -> nodeNames.put(n.getId(), n.getName()));
        links.forEach(l -> {
            if (l.getFromNode() != null && !nodeIds.contains(l.getFromNode())) {
                l.setFromNode(null);
            }
            if (l.getToNode() != null && !nodeIds.contains(l.getToNode())) {
                l.setToNode(null);
            }
            if (l.getFromNode() != null) {
                l.setFromName(nodeNames.get(l.getFromNode()));
            }
            if (l.getToNode() != null) {
                l.setToName(nodeNames.get(l.getToNode()));
            }
        });
        links.sort(Comparator.comparingInt(l -> ORDER.indexOf(l.getLevel())));
        return new Result(nodes, links);
    }

    private static Link callLink(Project caller, String callerId, List<CallSite> sites, Changed c) {
        Link l = base(callerId, caller.name(), nodeId(c.owner(), CallSiteScanner.BACK), c.owner().name(), c.e());
        String callChange = "UNCHANGED";
        FrontChange fc = caller.target().getFront();
        for (CallSite s : sites) {
            boolean added = fc != null && fc.getAddedCalls().stream()
                    .anyMatch(a -> a.getFile().equals(s.getFile()) && a.getPath().equals(s.getPath()));
            if (added) {
                callChange = "NEW_CALL";
                break;
            }
            if (caller.changedFiles().contains(s.getFile())) {
                callChange = "CHANGED_FILE";
            }
        }
        l.setCallChange(callChange);
        l.setRefs(sites.stream().limit(MAX_REFS).map(CrossProjectLinker::ref).toList());
        String type = c.e().getChangeType();
        String risk = c.e().getRisk();
        String who = label(caller, callerId.substring(callerId.lastIndexOf(':') + 1));
        if ("REMOVED".equals(type)) {
            l.setLevel(RISK);
            l.setTitle("接口下线了，" + who + " 还在调");
        } else if ("NEW_CALL".equals(callChange) || "ADDED".equals(type)
                || "CHANGED_FILE".equals(callChange) && contractChanged(risk)) {
            l.setLevel(JOINT);
            l.setTitle(("ADDED".equals(type) ? "新接口，" + who + " 这次接上了" : "两边这次都改了")
                    + "：要联调" + ("BREAKING".equals(risk) ? "（后端有不兼容改动，字段要逐个对）" : ""));
        } else if ("CHANGED_FILE".equals(callChange)) {
            l.setLevel(INFO);
            l.setTitle(who + " 用到这个接口，后端只改了实现（出入参没变），调用所在文件这次也动了：回归对应页面");
        } else if ("BREAKING".equals(risk)) {
            l.setLevel(RISK);
            l.setTitle("后端有不兼容改动，" + who + " 这次没跟着改：确认页面不会解析失败");
        } else if ("ADDED".equals(type)) {
            l.setLevel(JOINT);
            l.setTitle("新接口，" + who + " 里早就写好了调用：要联调");
        } else {
            l.setLevel(INFO);
            l.setTitle(who + " 用到这个接口，后端实现改了：回归对应页面");
        }
        if (!caller.hasCode()) {
            l.setInferred(true);
        }
        return l;
    }

    /** 出入参变了（不兼容 / 兼容地加字段）；IMPL 只是实现改了，调用方不用跟着改 */
    private static boolean contractChanged(String risk) {
        return "BREAKING".equals(risk) || "COMPATIBLE".equals(risk);
    }

    private static Link base(String fromId, String fromName, String toId, String toName, EndpointChange e) {
        Link l = new Link();
        l.setFromNode(fromId);
        l.setFromName(fromName);
        l.setToNode(toId);
        l.setToName(toName);
        if (e != null) {
            l.setMethod(e.getHttpMethod());
            l.setPath(e.getPath());
            l.setSummary(e.getSummary());
            l.setEndpointChange(e.getChangeType());
            l.setEndpointRisk(e.getRisk());
        }
        return l;
    }

    private record Hit(Project owner, ApiEndpoint e) {
    }

    private static Hit findInInventory(List<Project> projects, String callPath) {
        for (Project p : projects) {
            for (ApiEndpoint e : p.inventory()) {
                if (matches(callPath, e.path())) {
                    return new Hit(p, e);
                }
            }
        }
        return null;
    }

    /** 调用路径 = 网关前缀（最多两段）+ 接口路径；接口里的 {id} 对得上任意一段 */
    static boolean matches(String callPath, String endpointPath) {
        if (callPath == null || endpointPath == null || endpointPath.isBlank()) {
            return false;
        }
        String code = endpointPath.toLowerCase(Locale.ROOT);
        return SourceControllerMatcher.gatewayPrefix(callPath.toLowerCase(Locale.ROOT), code) != null;
    }

    // ---------------------------------------------------------------- 节点

    static List<ProjectNode> nodes(List<Project> projects) {
        List<ProjectNode> out = new ArrayList<>();
        for (Project p : projects) {
            Target t = p.target();
            if (p.backend()) {
                out.add(node(p, CallSiteScanner.BACK));
                boolean hasFront = t.getFront() != null
                        || p.headCalls().stream().anyMatch(s -> CallSiteScanner.FRONT.equals(s.getSide()));
                if (hasFront) {
                    out.get(out.size() - 1).setName(p.name() + " 后端");
                    out.add(node(p, CallSiteScanner.FRONT));
                }
            } else {
                out.add(node(p, sideOfKind(p)));
            }
        }
        return out;
    }

    private static ProjectNode node(Project p, String side) {
        Target t = p.target();
        ProjectNode n = new ProjectNode();
        n.setId(nodeId(p, side));
        n.setProjectCode(t.getProjectCode());
        n.setName(label(p, side));
        n.setSide(side);
        n.setBranch(t.getBranch());
        if (t.getProjectCode() == null) {
            n.setStatus("NOT_FOUND");
            n.setSummary("项目管理里没找到「" + t.getRawProject() + "」");
            return n;
        }
        if (!p.hasCode()) {
            n.setStatus("NO_CODE");
            n.setSummary("缺代码：" + t.getCodeMissing());
            return n;
        }
        if (CallSiteScanner.BACK.equals(side)) {
            Report r = p.report();
            n.setStatus(t.getError() != null ? "ERROR" : "ANALYZED");
            n.setChanged(r != null && !r.getFiles().isEmpty());
            n.setSummary(t.getError() != null ? t.getError()
                    : t.getReportSummary() != null ? t.getReportSummary() : !t.isAnalyzable() ? "不扫接口" : "还没分析");
        } else {
            FrontChange fc = t.getFront();
            n.setStatus("ANALYZED");
            n.setChanged(fc != null && fc.getChangedFiles() > 0);
            n.setSummary(fc == null ? "没有找到前端代码" : FrontDiffScanner.summary(fc));
        }
        return n;
    }

    static String nodeId(Project p, String side) {
        return p.code() + ":" + side;
    }

    /** 后端仓库里自带的前端（如 app-admin/frontend）单独叫「X 前端」，不然图上会出现「X → X」 */
    private static String label(Project p, String side) {
        return p.backend() && CallSiteScanner.FRONT.equals(side) ? p.name() + " 前端" : p.name();
    }

    private static String sideOfKind(Project p) {
        return ApiProjectKind.CLIENT.equals(p.target().getKind()) ? CallSiteScanner.CLIENT : CallSiteScanner.FRONT;
    }

    /** 改动文件集合：后端报告里的 + 前端对比里的 */
    public static Set<String> changedFiles(Report report, FrontChange front) {
        Set<String> out = new HashSet<>();
        if (report != null) {
            for (FileChange f : report.getFiles()) {
                out.add(f.getPath());
            }
        }
        if (front != null) {
            front.getFiles().forEach(f -> out.add(f.getPath()));
        }
        return out;
    }

    private static String ref(CallSite s) {
        return s.getFile() + ":" + s.getLine();
    }

    private static String tableKey(String table) {
        String s = table == null ? "" : table.trim().replace("`", "");
        int dot = s.lastIndexOf('.');
        return (dot >= 0 ? s.substring(dot + 1) : s).toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
