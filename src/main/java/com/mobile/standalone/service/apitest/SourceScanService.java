package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.ApiProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全量扫描：单个项目（页面上点「全量重扫」）和全部项目（一键把所有仓库的基线分支扫一遍）走同一套逻辑。
 * <p>
 * 一个项目扫完 = 接口入库 + 搜调用方，级别（核心 / 重要 / 一般 / 无人调用）就是按调用方算出来的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceScanService {

    private final ApiProjectService projectService;
    private final ApiEndpointService endpointService;
    private final ControllerBackfillService backfillService;
    private final GitSnapshotService gitSnapshotService;
    private final ApiAuthService authService;

    /** 同一时间只跑一个批量任务：几个任务同时扫会互相抢 git 和磁盘，也没必要 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "source-scan-all");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile String runningJobId;

    /**
     * 扫一个项目。
     *
     * @param dir        给了就扫这个目录（工作区），不标「基线里没有」
     * @param ref        给了就扫这个分支；空＝基线分支
     * @param callerDirs 调用方搜索范围，空＝仓库的上一级目录
     * @throws IllegalArgumentException 项目不存在、找不到仓库、分支不存在
     */
    public Map<String, Object> scanProject(String project, String dir, String ref, boolean forceFetch,
                                           String callerDirs) throws Exception {
        ApiProject p = projectService.get(project);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + project);
        }
        if (!ApiProjectKind.scansEndpoints(p)) {
            throw new IllegalArgumentException(
                    ApiProjectKind.skipScan(p.getCode())
                            ? "网关不扫接口，不用做接口自动化"
                            : "只有后端业务仓库才扫接口（前端 / 客户端不扫）");
        }
        boolean worktree = dir != null && !dir.isBlank();
        Path repo = worktree ? Path.of(dir.trim()) : backfillService.repoOf(p);
        if (repo == null) {
            throw new IllegalArgumentException("没给源码目录，项目管理里也没配仓库路径");
        }
        repo = repo.toAbsolutePath().normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        Path source = repo;
        String scannedRef = null;
        String goneRef = null;
        String sha = null;
        LocalDateTime committed = null;
        if (!worktree) {
            String base = gitSnapshotService.baseRef(repo, p.getBaseBranch());
            String want = ref == null || ref.isBlank() ? base : ref.trim();
            var snap = gitSnapshotService.snapshot(repo, want, true, forceFetch);
            source = snap.dir();
            scannedRef = want;
            sha = snap.sha();
            committed = snap.committed();
            if (want.equals(base)) {
                goneRef = want + "@" + snap.shortSha();
            }
            out.put("ref", want);
            out.put("sha", sha);
            out.put("shortSha", snap.shortSha());
            out.put("committedAt", committed);
            out.put("fetch", snap.fetch());
            out.put("isBase", want.equals(base));
        }
        var scan = SourceEndpointScanner.scan(source);
        var saved = endpointService.upsertFromSource(p.getCode(), scan.endpoints(), goneRef);
        out.put("dir", repo.toString());
        out.put("javaFiles", scan.javaFiles());
        out.put("controllers", scan.controllers());
        out.put("endpoints", scan.endpoints().size());
        out.put("created", saved.created());
        out.put("updated", saved.updated());
        out.put("linked", saved.linked());
        out.put("reprefixed", saved.reprefixed());
        out.put("gone", saved.gone());
        out.put("revived", saved.revived());
        out.put("prefix", saved.prefix());
        out.put("duplicates", endpointService.duplicates(p.getCode()).pairs().size());
        out.put("authRequired", scan.endpoints().stream().filter(s -> Boolean.TRUE.equals(s.auth())).count());
        out.put("tokenHeaders", scan.tokenHeaders());
        authService.rememberDetectedHeaders(p.getCode(), scan.tokenHeaders());

        // 仓库一般并排放在同一个目录下，默认就搜后端仓库的上一级
        List<Path> callerRoots = new ArrayList<>();
        if (callerDirs != null && !callerDirs.isBlank()) {
            for (String d : callerDirs.split("[\\n,;]")) {
                if (!d.isBlank()) {
                    callerRoots.add(Path.of(d.trim()));
                }
            }
        } else if (repo.getParent() != null) {
            callerRoots.add(repo.getParent());
        }
        out.put("callerDirs", callerRoots.stream().map(String::valueOf).toList());
        try {
            var callers = endpointService.syncCallers(p.getCode(), callerRoots, repo);
            out.put("callerUsed", callers.used());
            out.put("callerDefOnly", callers.defOnly());
            out.put("callerNone", callers.none());
        } catch (Exception e) {
            // 接口已经入库了，搜调用方失败不该让整次扫描算失败
            out.put("callerError", e.getMessage() == null ? "搜调用方失败" : e.getMessage());
        }
        endpointService.recordScan(p.getCode(), scannedRef, sha, committed, scan.endpoints().size(),
                "新建 " + saved.created() + "、更新 " + saved.updated() + "、认上抓包 " + saved.linked()
                        + "、基线里没有 " + saved.gone() + (out.get("fetch") == null ? "" : "；" + out.get("fetch")));
        return out;
    }

    /** 扫完后这个项目各级别的接口数（不含已废弃的） */
    public Map<String, Integer> levelCounts(String project) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (EndpointLevel l : EndpointLevel.values()) {
            out.put(l.name(), 0);
        }
        for (ApiEndpoint e : endpointService.search(project, null, null, null)) {
            if (e.isRetired() || !project.equals(e.getProject())) {
                continue;
            }
            EndpointLevel l = ApiEndpointService.levelOf(e);
            if (l != null) {
                out.merge(l.name(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** 一个项目在批量任务里的结果 */
    public static final class Item {
        public final String project;
        public final String name;
        public volatile String state = "WAITING";
        public volatile String error;
        public volatile Map<String, Object> result;
        public volatile Map<String, Integer> levels;

        Item(String project, String name) {
            this.project = project;
            this.name = name;
        }
    }

    public static final class Job {
        public final String id;
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;
        public final List<Item> items;

        Job(String id, List<Item> items) {
            this.id = id;
            this.items = Collections.unmodifiableList(items);
        }

        public boolean done() {
            return finishedAt != null;
        }
    }

    /**
     * 把所有后端项目的基线分支扫一遍，后台跑，页面轮询进度。
     * 找不到本地仓库的项目直接标「跳过」，不算失败。已经有任务在跑就返回那个任务。
     */
    public synchronized Job startScanAll() {
        if (runningJobId != null) {
            Job running = jobs.get(runningJobId);
            if (running != null && !running.done()) {
                return running;
            }
        }
        List<Item> items = new ArrayList<>();
        for (ApiProject p : projectService.list(true)) {
            if (!ApiProjectKind.scansEndpoints(p)) {
                continue;
            }
            items.add(new Item(p.getCode(), p.getName()));
        }
        Job job = new Job("scan-" + UUID.randomUUID().toString().substring(0, 8), items);
        jobs.put(job.id, job);
        runningJobId = job.id;
        worker.submit(() -> runAll(job));
        return job;
    }

    public Job job(String id) {
        return id == null ? null : jobs.get(id);
    }

    /** 最近一个批量任务（页面刷新后接着看进度） */
    public Job latestJob() {
        return runningJobId == null ? null : jobs.get(runningJobId);
    }

    private void runAll(Job job) {
        try {
            for (Item it : job.items) {
                ApiProject p = projectService.get(it.project);
                if (p == null || backfillService.repoOf(p) == null) {
                    it.state = "SKIPPED";
                    it.error = "本地没找到仓库，去项目管理里配一下仓库路径";
                    continue;
                }
                it.state = "RUNNING";
                try {
                    it.result = scanProject(it.project, null, null, false, null);
                    it.levels = levelCounts(it.project);
                    it.state = "DONE";
                } catch (IllegalArgumentException e) {
                    it.state = "FAILED";
                    it.error = e.getMessage();
                } catch (Exception e) {
                    log.warn("[scan-all] {} 扫描失败", it.project, e);
                    it.state = "FAILED";
                    it.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        } finally {
            job.finishedAt = LocalDateTime.now();
            log.info("[scan-all] {} 完成：{} 个项目", job.id, job.items.size());
        }
    }
}
