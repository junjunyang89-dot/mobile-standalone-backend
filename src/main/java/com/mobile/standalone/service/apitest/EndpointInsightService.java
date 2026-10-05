package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.service.ApiProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 接口体检的入口：找到项目的本地仓库 → 定位 Controller 方法 → {@link EndpointInsight} 读源码，
 * 再补两样只有这里拿得到的：git 最后改动时间、客户端里有没有人调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndpointInsightService {

    private final ApiProjectService projectService;
    private final ApiEndpointService endpointService;
    private final ApiEndpointMapper endpointMapper;
    private final ControllerBackfillService backfillService;
    private final GitSnapshotService gitSnapshotService;

    /** 被测环境部署的分支：体检、按源码生成用例都读它，不读本地工作区 */
    @org.springframework.beans.factory.annotation.Value("${app.api-test.test-ref:origin/testing}")
    private String testRef = "origin/testing";

    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long OLD_DAYS = 730;

    private record RepoCache(long at, List<SourceEndpointScanner.Scanned> endpoints,
                             Map<String, List<Path>> classes) {
    }

    private final Map<Path, RepoCache> cache = new ConcurrentHashMap<>();

    /** 定位到了就带 report；没定位到时 located=false，reason 说清楚卡在哪一步 */
    /**
     * @param ref 读的是哪个分支；null＝分支不存在，退回读的本地工作区
     * @param sha 那个分支的提交
     */
    public record Result(boolean located, String reason, String project, String repo, String matchKind,
                         List<String> candidates, EndpointInsight.Report report, String lastChanged,
                         String callerStatus, String ref, String sha) {
        static Result fail(String project, String repo, String reason, List<String> candidates) {
            return new Result(false, reason, project, repo, null, candidates, null, null, null, null, null);
        }
    }

    /**
     * @param endpointId 有就按接口清单里的这条来（项目、方法、路径、抓包响应都从它取）
     * @param respSample 抓包响应；空时用接口上存的抓包样本
     */
    public Result inspect(String endpointId, String project, String method, String path, String respSample) {
        ApiEndpoint e = endpointId == null || endpointId.isBlank() ? null : endpointMapper.findById(endpointId);
        if (e != null) {
            project = e.getProject();
            method = e.getHttpMethod();
            path = e.getPath();
            if (respSample == null || respSample.isBlank()) {
                respSample = EndpointAssertionContext.respSampleOf(endpointService.captureMetaOf(e));
            }
        }
        if (project == null || project.isBlank()) {
            return Result.fail(null, null, "先选这个接口属于哪个项目", null);
        }
        ApiProject p = projectService.get(project);
        if (p == null) {
            return Result.fail(project, null, "项目不存在：" + project, null);
        }
        if (e == null) {
            e = findEndpoint(project, method, path);
        }
        Path root = backfillService.repoOf(p);
        if (root == null) {
            return Result.fail(project, null, "本地没找到 " + project + " 的仓库，去项目管理里配一下仓库路径", null);
        }
        Path src = root;
        String ref = null;
        String sha = null;
        try {
            var snap = gitSnapshotService.snapshot(root, testRef, true, false);
            src = snap.dir();
            ref = snap.ref();
            sha = snap.sha();
        } catch (Exception ex) {
            // 没有这个分支（或不是 git 仓库）：退回读工作区，结果里 ref 为空，页面会提示
            log.debug("[insight] 读 {} 的 {} 失败，改读工作区: {}", project, testRef, ex.getMessage());
        }
        RepoCache rc;
        try {
            rc = load(src);
        } catch (Exception ex) {
            return Result.fail(project, root.toString(), "读仓库失败：" + ex.getMessage(), null);
        }

        var match = SourceControllerMatcher.match(method, path, rc.endpoints());
        if (match.hit() == null && e != null && e.getCodePath() != null && !e.getCodePath().equals(path)) {
            match = SourceControllerMatcher.match(method, e.getCodePath(), rc.endpoints());
        }
        SourceEndpointScanner.Scanned hit = match.hit();
        String kind = match.kind().name();
        if (hit == null && e != null && e.getSourceFile() != null && e.getHandler() != null) {
            String file = e.getSourceFile();
            String handler = e.getHandler();
            hit = rc.endpoints().stream()
                    .filter(s -> file.equals(s.file()) && handler.equals(s.handler()))
                    .findFirst().orElse(null);
            kind = hit == null ? kind : "LINKED";
        }
        if (hit == null) {
            if (match.kind() == SourceControllerMatcher.Kind.AMBIGUOUS) {
                return Result.fail(project, root.toString(), "仓库里有好几个接口都对得上这个路径，没法确定是哪个",
                        match.candidates().stream().map(c -> c.file() + " " + c.httpMethod() + " " + c.path()).toList());
            }
            return Result.fail(project, root.toString(),
                    (ref == null ? "本地仓库" : ref + " ") + "里没找到 " + method + " " + path
                            + "：可能已经删了、还没合到这个分支，或者网关转给了别的服务", null);
        }

        String configured = p.getSuccessCodePath() == null || p.getSuccessCodePath().isBlank()
                ? null : p.getSuccessCodeValue();
        var repo = new EndpointInsight.Repo(src, rc.classes());
        EndpointInsight.Report report = EndpointInsight.analyze(repo, hit.file(), hit.handler(), hit.auth(),
                respSample, configured);
        if (report == null) {
            return Result.fail(project, root.toString(), "在 " + hit.file() + " 里没读到方法 " + hit.handler(), null);
        }

        String lastChanged = lastChanged(root, sha, report.ranges());
        String callers = e == null ? null : e.getCallerStatus();
        List<EndpointInsight.Risk> risks = new ArrayList<>(report.risks());
        if ("NONE".equals(callers) || "DEF_ONLY".equals(callers)) {
            risks.add(new EndpointInsight.Risk("MID", "NO_CALLERS", "客户端里找不到调用方",
                    "NONE".equals(callers) ? "上次扫描在客户端 / H5 / 其他服务里都没搜到这个路径，多半已经下线了"
                            : "只在 API 封装里写了路径，封装本身没人用，多半已经下线了",
                    null));
        }
        if (lastChanged != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(lastChanged), LocalDate.now());
            if (days > OLD_DAYS) {
                risks.add(new EndpointInsight.Risk("LOW", "OLD_CODE", "老接口：" + (days / 365) + " 年多没人改过",
                        "接口和它调的 service 方法最后一次改动是 " + lastChanged
                                + "。老接口常见的问题：字段注释和实际不符、返回里有早就不用的字段、报错方式跟不上现在的约定",
                        null));
            }
        }
        risks.addAll(paramRisks(report.params()));
        risks.sort((a, b) -> EndpointInsight.levelRank(a.level()) - EndpointInsight.levelRank(b.level()));
        report = report.withRisks(risks);
        return new Result(true, null, project, root.toString(), kind, null, report, lastChanged, callers, ref, sha);
    }

    private ApiEndpoint findEndpoint(String project, String method, String path) {
        if (path == null) {
            return null;
        }
        try {
            for (ApiEndpoint x : endpointService.search(project, path, null, null)) {
                if (path.equals(x.getPath()) && (method == null || method.equalsIgnoreCase(x.getHttpMethod()))) {
                    return x;
                }
            }
        } catch (Exception ex) {
            log.debug("[insight] 查接口失败 {} {}: {}", method, path, ex.getMessage());
        }
        return null;
    }

    private RepoCache load(Path root) throws Exception {
        RepoCache c = cache.get(root);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_MS) {
            return c;
        }
        c = new RepoCache(System.currentTimeMillis(), SourceEndpointScanner.scan(root).endpoints(),
                EndpointInsight.Repo.index(root));
        cache.put(root, c);
        return c;
    }

    /** 这几段代码里最新一行的提交日期；不是 git 仓库或 git 不可用时返回 null */
    static String lastChanged(Path root, String rev, List<String> ranges) {
        long latest = 0;
        for (String r : ranges) {
            int colon = r.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String file = r.substring(0, colon);
            String[] se = r.substring(colon + 1).split("-");
            try {
                List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString(), "blame", "--porcelain",
                        "-L", se[0] + "," + se[1]));
                if (rev != null) {
                    cmd.add(rev);
                }
                cmd.add("--");
                cmd.add(file);
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("committer-time ")) {
                            latest = Math.max(latest, Long.parseLong(line.substring(15).trim()));
                        }
                    }
                }
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Exception ex) {
                log.debug("[insight] git blame 失败 {}: {}", r, ex.getMessage());
            }
        }
        return latest == 0 ? null
                : Instant.ofEpochSecond(latest).atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    /** 给生成用例的模型看的摘要：字段含义、业务码、登录要求 */
    /** 缺参静默成功、缺参没判空：这两种客户端传漏了都发现不了，或者报的是系统繁忙 */
    static List<EndpointInsight.Risk> paramRisks(List<ParamInsight.Param> params) {
        List<EndpointInsight.Risk> out = new ArrayList<>();
        if (params == null) {
            return out;
        }
        List<ParamInsight.Param> silent = params.stream()
                .filter(p -> p.missing() != null && ParamInsight.SILENT.equals(p.missing().kind())).toList();
        List<ParamInsight.Param> crash = params.stream()
                .filter(p -> p.missing() != null && p.missing().suspect()
                        && !ParamInsight.SILENT.equals(p.missing().kind())).toList();
        if (!silent.isEmpty()) {
            out.add(new EndpointInsight.Risk("MID", "PARAM_SILENT",
                    "缺参不报错：" + String.join("、", silent.stream().map(ParamInsight.Param::name).toList()),
                    "这些参数不传时接口照样返回成功，只是数据为空（" + silent.get(0).missing().detail()
                            + "）。客户端传漏了参数也发现不了，看起来就像「没数据」",
                    silent.get(0).missing().where()));
        }
        if (!crash.isEmpty()) {
            ParamInsight.Outcome o = crash.get(0).missing();
            out.add(new EndpointInsight.Risk("MID", "PARAM_CRASH",
                    "缺参报" + (o.codeName() == null ? "异常" : o.codeName()) + "："
                            + String.join("、", crash.stream().map(ParamInsight.Param::name).toList()),
                    o.detail() + "。应该返回参数错误，现在返回的是兜底的系统异常", o.where()));
        }
        return out;
    }

    public static String promptNotes(EndpointInsight.Report r) {
        if (r == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<String> docs = new ArrayList<>();
        for (EndpointInsight.Field f : r.fields()) {
            if (f.comment() != null && !"EXTRA".equals(f.status()) && docs.size() < 30) {
                docs.add(f.path() + "（" + f.type() + "）：" + f.comment());
            }
        }
        if (!docs.isEmpty()) {
            sb.append("字段含义：\n");
            docs.forEach(d -> sb.append("  ").append(d).append('\n'));
        }
        if (r.success() != null) {
            sb.append("成功码：").append(r.success().name()).append('=').append(r.success().code()).append('\n');
        }
        List<EndpointInsight.Code> errs = new ArrayList<>(r.errors());
        errs.addAll(r.globalErrors());
        if (!errs.isEmpty()) {
            sb.append("代码里会返回的错误码：");
            Map<String, String> seen = new LinkedHashMap<>();
            for (EndpointInsight.Code c : errs) {
                seen.putIfAbsent(c.name(), c.name() + (c.code() == null ? "" : "=" + c.code())
                        + (c.message() == null ? "" : "（" + c.message() + "）"));
            }
            sb.append(String.join("、", seen.values())).append('\n');
        }
        var login = r.login();
        if (login != null) {
            if (Boolean.TRUE.equals(login.required())) {
                sb.append("要登录：").append(login.annotation());
                if (login.failCode() != null) {
                    sb.append("，没登录返回 ").append(login.failCode().name()).append('=').append(login.failCode().code());
                }
                sb.append('\n');
            } else if (login.commented()) {
                sb.append("登录注解被注释掉了，代码不校验登录\n");
            }
        }
        List<String> ps = new ArrayList<>();
        for (ParamInsight.Param p : r.params() == null ? List.<ParamInsight.Param>of() : r.params()) {
            if (p.missing() != null && ps.size() < 12) {
                ps.add(p.name() + (p.comment() == null ? "" : "（" + p.comment() + "）") + "：不传→"
                        + SourceCases.behavior(p.missing()));
            }
        }
        if (!ps.isEmpty()) {
            sb.append("入参在代码里的处理：\n");
            ps.forEach(x -> sb.append("  ").append(x).append('\n'));
            sb.append("这些参数的缺参 / 传错类型 / 越界用例平台已按源码生成，不要再出\n");
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
