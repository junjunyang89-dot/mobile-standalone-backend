package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.EndpointChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.ParamChange;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CallSite;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatCheck;
import com.mobile.standalone.service.releasebatch.ReleaseBatchDtos.CompatHit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 线上版本的调用方扛不扛得住这次后端改动。
 * <p>
 * 用户手机上的 App 是 master 打的包，后端一发版它就要面对新接口——新包修了也没用；
 * 不在这次单子里的 H5 也一样，后端发了它不会跟着发。所以拿每个前端 / 客户端仓库的 master，
 * 先看它调没调改了的接口，调了再搜它代码里有没有读被删 / 被改的那个字段，定位到文件。
 * <p>
 * 只认代码里写死的接口路径（见 {@link CallSiteScanner}）；路径是运行时拼出来的认不出，
 * 这种情况算「没调」，所以结果里要写明检查了哪些仓库、靠什么判断的。
 */
public final class CallerCompatProbe {

    private CallerCompatProbe() {
    }

    /** 调用方仓库；dir 为 null 时 missing 说明为什么没拿到代码 */
    public record Caller(String code, String name, String kind, boolean inTicket, Path dir, String missing) {
    }

    /** 接口上一处会让老调用方出错的改动；field 是要到调用方代码里搜的返回字段 */
    record Break(String what, String field, boolean strong) {
    }

    public record Endpoint(String backend, EndpointChange change) {
    }

    static final int MAX_HITS = 80;
    private static final int MAX_REFS = 3;

    /** 太常见的字段名，搜出来满屏都是，定位不到具体哪处在用 */
    private static final Set<String> COMMON = Set.of(
            "id", "name", "type", "status", "code", "msg", "message", "data", "list", "title", "url", "time",
            "count", "value", "desc", "content", "result", "success", "total", "page", "size", "key", "icon",
            "image", "avatar", "text", "state", "info", "items", "records", "extra", "tag", "level", "num", "price");

    /** 后端有没有会伤到老调用方的改动；没有就不用去拉一堆前端 / 客户端仓库 */
    public static boolean anyBreak(List<Endpoint> endpoints) {
        return endpoints.stream().anyMatch(e -> !breaksOf(e.change()).isEmpty());
    }

    static List<Break> breaksOf(EndpointChange e) {
        List<Break> out = new ArrayList<>();
        if ("REMOVED".equals(e.getChangeType())) {
            out.add(new Break("接口下线了", null, true));
            return out;
        }
        for (ParamChange p : e.getParams()) {
            if ("ADDED".equals(p.getChangeType()) && p.isRequired()) {
                out.add(new Break("新增必填入参 " + p.getName() + "，线上版本不会传", null, true));
            } else if ("REQUIRED_CHANGED".equals(p.getChangeType()) && p.isRequired()) {
                out.add(new Break("入参 " + p.getName() + " 改成了必填", null, true));
            } else if ("TYPE_CHANGED".equals(p.getChangeType()) && shapeChanged(p.getWireEffect())) {
                out.add(new Break("入参 " + p.getName() + " 类型 " + p.getOldType() + " → " + p.getNewType(), null, true));
            }
        }
        for (FieldChange f : e.getBodyFields()) {
            if ("TYPE_CHANGED".equals(f.getChangeType()) && shapeChanged(f.getWireEffect())) {
                out.add(new Break("请求体字段 " + f.getName() + " 类型 " + f.getOldType() + " → " + f.getNewType(), null, true));
            }
        }
        for (FieldChange f : e.getFields()) {
            if ("REMOVED".equals(f.getChangeType())) {
                out.add(new Break("删了返回字段 " + f.getName(), f.getName(), true));
            } else if ("TYPE_CHANGED".equals(f.getChangeType())) {
                if (shapeChanged(f.getWireEffect())) {
                    out.add(new Break("返回字段 " + f.getName() + " 类型 " + f.getOldType() + " → " + f.getNewType()
                            + (f.getWireNote() == null ? "" : "（" + f.getWireNote() + "）"), f.getName(), true));
                } else if ("NULLABILITY".equals(f.getWireEffect())) {
                    out.add(new Break("返回字段 " + f.getName() + " 可能返回 null（" + f.getOldType() + " → " + f.getNewType() + "）",
                            f.getName(), false));
                }
            }
        }
        if (out.isEmpty() && "BREAKING".equals(e.getRisk())) {
            out.add(new Break("接口有不兼容改动（见二.2 接口变更）", null, true));
        }
        return out;
    }

    private static boolean shapeChanged(String wireEffect) {
        return wireEffect == null || "SHAPE".equals(wireEffect) || "UNKNOWN".equals(wireEffect);
    }

    public static CompatCheck probe(List<Endpoint> endpoints, List<Caller> callers) {
        CompatCheck out = new CompatCheck();
        Map<Endpoint, List<Break>> breaks = new LinkedHashMap<>();
        for (Endpoint e : endpoints) {
            List<Break> b = breaksOf(e.change());
            if (!b.isEmpty()) {
                breaks.put(e, b);
            }
        }
        for (Caller c : callers) {
            String label = c.name() + "（" + kindWord(c.kind()) + "）";
            if (c.dir() == null) {
                out.getUnchecked().add(label + "：" + c.missing());
                continue;
            }
            try {
                probeOne(c, breaks, out);
                out.getChecked().add(label);
            } catch (IOException | RuntimeException ex) {
                out.getUnchecked().add(label + "：读代码失败 " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        out.getHits().sort((x, y) -> Integer.compare(levelOrder(x.getLevel()), levelOrder(y.getLevel())));
        if (out.getHits().size() > MAX_HITS) {
            out.setTruncated(out.getHits().size() - MAX_HITS);
            out.setHits(new ArrayList<>(out.getHits().subList(0, MAX_HITS)));
        }
        return out;
    }

    private static void probeOne(Caller c, Map<Endpoint, List<Break>> breaks, CompatCheck out) throws IOException {
        boolean client = ApiProjectKind.CLIENT.equals(c.kind());
        List<CallSite> sites = CallSiteScanner.scanRepo(c.dir(), client ? CallSiteScanner.Scope.CLIENT : CallSiteScanner.Scope.FRONTEND);
        Map<Endpoint, List<CallSite>> called = new LinkedHashMap<>();
        Set<String> words = new LinkedHashSet<>();
        for (Map.Entry<Endpoint, List<Break>> en : breaks.entrySet()) {
            List<CallSite> hit = sites.stream().filter(s -> CrossProjectLinker.matches(s.getPath(), en.getKey().change().getPath())).toList();
            if (hit.isEmpty()) {
                continue;
            }
            called.put(en.getKey(), hit);
            for (Break b : en.getValue()) {
                String w = searchWord(b.field());
                if (w != null) {
                    words.add(w);
                    words.add(snake(w));
                }
            }
        }
        if (called.isEmpty()) {
            return;
        }
        Map<String, List<String>> refs = locate(c.dir(), client, words);
        for (Map.Entry<Endpoint, List<CallSite>> en : called.entrySet()) {
            EndpointChange e = en.getKey().change();
            List<String> callRefs = en.getValue().stream().limit(MAX_REFS).map(s -> s.getFile() + ":" + s.getLine()).toList();
            for (Break b : breaks.get(en.getKey())) {
                CompatHit h = new CompatHit();
                h.setCaller(c.name());
                h.setCallerKind(c.kind());
                h.setInTicket(c.inTicket());
                h.setBackend(en.getKey().backend());
                h.setEndpoint(e.getHttpMethod() + " " + e.getPath());
                h.setProblem(b.what());
                h.setCallRefs(new ArrayList<>(callRefs));
                String found = null;
                if (b.field() != null) {
                    h.setField(b.field());
                    String w = searchWord(b.field());
                    if (w == null) {
                        found = "TOO_COMMON";
                    } else {
                        List<String> r = new ArrayList<>(refs.getOrDefault(w, List.of()));
                        refs.getOrDefault(snake(w), List.of()).stream().filter(x -> !r.contains(x)).forEach(r::add);
                        found = r.isEmpty() ? "NOT_FOUND" : "FOUND";
                        h.setFieldRefs(r.stream().limit(MAX_REFS).toList());
                    }
                    h.setFieldFound(found);
                }
                judge(h, c, client, b, found);
                out.getHits().add(h);
            }
        }
    }

    private static void judge(CompatHit h, Caller c, boolean client, Break b, String found) {
        String page = "打开调用它的页面（" + String.join("、", h.getCallRefs()) + "）";
        if ("NOT_FOUND".equals(found)) {
            h.setLevel("CHECK");
            h.setAdvice("调了这个接口，但代码里搜不到 " + h.getField() + "，多半没用到；" + page + "看一眼确认");
            return;
        }
        if (!b.strong()) {
            h.setLevel("CHECK");
            h.setAdvice(client ? "Kotlin / Swift 里没判空会直接崩：造这个字段为空的数据，用线上版本 App " + page
                    : "造这个字段为空的数据，" + page + "看展示是不是 null / undefined");
            return;
        }
        String field = "FOUND".equals(found) ? "，代码里在读 " + h.getField() + "（" + String.join("、", h.getFieldRefs()) + "）"
                : "TOO_COMMON".equals(found) ? "（字段名太常见，定位不到具体哪处在读）" : "";
        if (client) {
            h.setLevel("RISK");
            h.setAdvice("线上版本 App 装在用户手机上，新包修了也没用" + field + "。用 master 打的包连测试环境新后端，"
                    + page + "，确认不闪退、不白屏、数据正常；要改的话后端得保留老接口 / 老字段到老版本下线，或者走强更");
        } else if (!c.inTicket()) {
            h.setLevel("RISK");
            h.setAdvice("这个 H5 不在这次的单里，后端一发它就受影响" + field + "。测试环境" + page
                    + "验证；确认 H5 要不要一起改、一起发");
        } else {
            h.setLevel("CHECK");
            h.setAdvice("H5 这次在单里，要和后端同时发" + field + "。用 H5 的 master 版本对新后端测一次，确认发版间隙不会出错");
        }
    }

    // ==================== 在调用方代码里定位字段 ====================

    private static final Set<String> CLIENT_FILES = union(CallSiteScanner.CLIENT_EXTS, Set.of("xml", "json"));

    private static Map<String, List<String>> locate(Path root, boolean client, Set<String> words) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (words.isEmpty()) {
            return out;
        }
        Pattern any = Pattern.compile("\\b(" + String.join("|", words.stream().map(Pattern::quote).toList()) + ")\\b");
        Set<String> exts = client ? CLIENT_FILES : CallSiteScanner.FRONT_EXTS;
        for (String[] f : CallSiteScanner.listFiles(root)) {
            String rel = f[0];
            int dot = rel.lastIndexOf('.');
            if (dot < 0 || !exts.contains(rel.substring(dot + 1).toLowerCase(Locale.ROOT)) || isTestFile(rel)) {
                continue;
            }
            String src;
            try {
                src = Files.readString(Path.of(f[1]), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = any.matcher(lines[i]);
                while (m.find()) {
                    List<String> r = out.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                    String ref = rel + ":" + (i + 1);
                    if (r.size() < MAX_REFS && r.stream().noneMatch(x -> x.startsWith(rel + ":"))) {
                        r.add(ref);
                    }
                }
            }
        }
        return out;
    }

    private static boolean isTestFile(String rel) {
        String p = rel.toLowerCase(Locale.ROOT);
        return p.contains("/test/") || p.contains("/androidtest/") || p.contains("__tests__") || p.contains(".spec.")
                || p.contains(".test.") || p.startsWith("test/");
    }

    /** 嵌套字段取最后一段；太短或太常见返回 null（搜了也定位不到） */
    static String searchWord(String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        String w = field.substring(field.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9_]", "");
        if (w.length() <= 2 || COMMON.contains(w.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return w;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String kindWord(String kind) {
        return ApiProjectKind.CLIENT.equals(kind) ? "客户端" : "H5 / 前端";
    }

    private static int levelOrder(String level) {
        return "RISK".equals(level) ? 0 : 1;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return s;
    }
}
