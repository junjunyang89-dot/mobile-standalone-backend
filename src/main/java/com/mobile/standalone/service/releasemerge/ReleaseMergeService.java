package com.mobile.standalone.service.releasemerge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.auth.CurrentUser;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.apitest.ApiProjectKind;
import com.mobile.standalone.service.releasebatch.ProjectMatcher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 合并上线：测试给出「项目：分支」，平台在上线专用的 master 目录里先核对，
 * 再用测试自己的 GitLab 令牌建合并请求并合掉。
 * <p>
 * 核对结果分三档：BLOCK 不让合（分支不存在、有冲突），WARN 要逐条确认才让合
 * （SQL、配置、夹带别的分支、前端），OK 直接合。ALREADY 表示已经在 master 里了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseMergeService {

    private static final String K_REPOS_DIR = "repos_dir";
    private static final String K_GITLAB_BASE = "gitlab_base_url";
    private static final String K_TOKEN = "token:";
    private static final int SQL_MAX_CHARS = 200_000;

    private final JdbcTemplate jdbc;
    private final ApiProjectMapper projectMapper;
    private final ObjectMapper json = new ObjectMapper();
    private final Set<String> merging = ConcurrentHashMap.newKeySet();

    @Value("${api-change.repos-dir:${user.home}/mobile-standalone-repos}")
    private String analysisReposDir;

    @Value("${release-merge.repos-dir:${user.home}/release-repos}")
    private String defaultReposDir;

    @PostConstruct
    public void ensureSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS release_merge_setting (
                        k VARCHAR(160) NOT NULL PRIMARY KEY,
                        v TEXT,
                        updated_by VARCHAR(100),
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS release_merge_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        project_code VARCHAR(128),
                        branch VARCHAR(255),
                        target_branch VARCHAR(128),
                        source_sha VARCHAR(64),
                        merge_sha VARCHAR(64),
                        mr_iid BIGINT,
                        mr_url VARCHAR(500),
                        status VARCHAR(20),
                        message VARCHAR(1000),
                        warnings TEXT,
                        operator VARCHAR(100),
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_rml_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception e) {
            log.warn("[release-merge] 建表失败: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 设置

    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reposDir", reposDir().toString());
        m.put("defaultReposDir", defaultReposDir);
        m.put("gitlabBaseUrl", setting(K_GITLAB_BASE));
        m.put("tokenSet", setting(tokenKey()) != null);
        m.put("user", CurrentUser.nameOrNull());
        return m;
    }

    public Map<String, Object> saveSettings(String reposDir, String gitlabBaseUrl, String token, boolean clearToken) {
        if (reposDir != null) {
            String d = reposDir.trim();
            if (!d.isEmpty()) {
                checkNotOverlapping(Path.of(expandHome(d)).toAbsolutePath().normalize());
            }
            put(K_REPOS_DIR, d.isEmpty() ? null : d);
        }
        if (gitlabBaseUrl != null) {
            String u = gitlabBaseUrl.trim();
            if (!u.isEmpty() && !u.matches("^https?://\\S+$")) {
                throw new IllegalArgumentException("GitLab 地址要以 http:// 或 https:// 开头");
            }
            put(K_GITLAB_BASE, u.isEmpty() ? null : u);
        }
        if (clearToken) {
            put(tokenKey(), null);
        } else if (token != null && !token.isBlank()) {
            put(tokenKey(), token.trim());
        }
        return settings();
    }

    /** 拿第一个配了 GitLab 地址的项目算接口地址，测一下令牌是谁的 */
    public Map<String, Object> testToken() {
        String token = requireToken();
        String base = setting(K_GITLAB_BASE);
        if (base == null) {
            base = projectMapper.findAll(true).stream()
                    .map(p -> MergeRules.apiBase(p.getGitUrl(), null))
                    .filter(b -> b != null)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("项目管理里没有配 GitLab 地址的项目，先在设置里填 GitLab 地址"));
        }
        String who = new GitLabClient(base, token).whoami();
        return Map.of("ok", true, "gitlab", base, "user", who);
    }

    // ---------------------------------------------------------------- 核对

    public Map<String, Object> check(String text, List<Map<String, Object>> rows) {
        List<ApiProject> projects = projectMapper.findAll(true);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> todo = new ArrayList<>();
        if (rows != null && !rows.isEmpty()) {
            for (Map<String, Object> r : rows) {
                String code = str(r.get("projectCode"));
                String branch = str(r.get("branch"));
                Map<String, Object> row = baseRow(str(r.get("raw")), code, branch);
                ApiProject p = code == null ? null : projectMapper.findByCode(code);
                if (p == null) {
                    row.put("status", "BLOCK");
                    blockers(row).add("项目不存在：" + code);
                } else if (!MergeRules.validBranch(branch)) {
                    row.put("status", "BLOCK");
                    blockers(row).add("分支名不对：" + branch);
                } else {
                    fillProject(row, p, "MANUAL");
                    todo.add(row);
                }
                results.add(row);
            }
        } else {
            for (MergeRules.Line l : MergeRules.parseLines(text)) {
                Map<String, Object> row = baseRow(l.raw(), null, l.branch());
                row.put("rawProject", l.project());
                if (l.error() != null) {
                    row.put("status", "BLOCK");
                    blockers(row).add(l.error());
                } else {
                    ProjectMatcher.Match m = ProjectMatcher.match(l.project(), projects);
                    if (m == null) {
                        row.put("status", "BLOCK");
                        blockers(row).add("项目管理里没认出「" + l.project() + "」，在下拉里选一下");
                    } else {
                        fillProject(row, m.project(), m.how());
                        todo.add(row);
                    }
                }
                results.add(row);
            }
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("没有要核对的分支，每行写一个「项目：分支」");
        }
        if (!todo.isEmpty()) {
            inspectAll(todo);
        }
        markOrder(results);
        results.sort(Comparator.comparingInt(r -> kindOrder(str(r.get("kind")))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", results);
        out.put("reposDir", reposDir().toString());
        out.put("tokenSet", setting(tokenKey()) != null);
        return out;
    }

    /** 同一个仓库的核对排队做（同目录并发 fetch 会抢锁），不同仓库并行 */
    private void inspectAll(List<Map<String, Object>> todo) {
        Map<String, List<Map<String, Object>>> byRepo = new LinkedHashMap<>();
        for (Map<String, Object> r : todo) {
            byRepo.computeIfAbsent(str(r.get("projectCode")), k -> new ArrayList<>()).add(r);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, byRepo.size()));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (List<Map<String, Object>> group : byRepo.values()) {
                fs.add(pool.submit(() -> group.forEach(this::inspectQuietly)));
            }
            for (Future<?> f : fs) {
                try {
                    f.get(20, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("[release-merge] 核对超时或失败: {}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void inspectQuietly(Map<String, Object> row) {
        try {
            inspect(row);
        } catch (Exception e) {
            row.put("status", "BLOCK");
            blockers(row).add(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void inspect(Map<String, Object> row) {
        ApiProject p = projectMapper.findByCode(str(row.get("projectCode")));
        String branch = str(row.get("branch"));
        String target = str(row.get("target"));
        if (p.getGitUrl() == null || p.getGitUrl().isBlank()) {
            row.put("status", "BLOCK");
            blockers(row).add("项目管理里这个项目没填 GitLab 地址");
            return;
        }
        if (branch.equals(target)) {
            row.put("status", "BLOCK");
            blockers(row).add("分支就是 " + target + "，不用合");
            return;
        }
        Path root = reposDir();
        checkNotOverlapping(root);
        Path dir = MasterRepo.ensure(root, p.getGitUrl().trim(), target);
        row.put("repoDir", dir.toString());
        if (!MasterRepo.fetchBranch(dir, branch)) {
            row.put("status", "BLOCK");
            blockers(row).add("GitLab 上没有这个分支，检查一下分支名");
            return;
        }
        String m = "origin/" + target;
        String b = "origin/" + branch;
        row.put("sha", MasterRepo.sha(dir, b));
        row.put("masterSha", MasterRepo.sha(dir, m));
        if (MasterRepo.isAncestor(dir, b, m)) {
            row.put("status", "ALREADY");
            row.put("summary", "这个分支已经全部在 " + target + " 里了");
            return;
        }
        row.put("ahead", MasterRepo.count(dir, m + ".." + b));
        row.put("behind", MasterRepo.count(dir, b + ".." + m));
        List<MasterRepo.Commit> commits = MasterRepo.commits(dir, m + ".." + b, 50);
        row.put("commits", commits.stream().map(c -> Map.of(
                "sha", c.sha().substring(0, Math.min(10, c.sha().length())),
                "author", c.author(), "subject", c.subject(), "time", c.time())).toList());
        Set<String> authors = new LinkedHashSet<>();
        commits.forEach(c -> authors.add(c.author()));
        row.put("authors", new ArrayList<>(authors));

        List<String> conflicts = MasterRepo.conflicts(dir, m, b);
        if (conflicts != null) {
            row.put("conflicts", conflicts);
            blockers(row).add("和 " + target + " 有冲突" + (conflicts.isEmpty() ? "" : "（" + conflicts.size() + " 个文件）")
                    + "，要开发先把 " + target + " 合进分支解决冲突");
        }

        List<String> mergedIn = MergeRules.mergedInBranches(MasterRepo.mergeSubjects(dir, m + ".." + b), target, branch);
        if (!mergedIn.isEmpty()) {
            row.put("mergedIn", mergedIn);
            warnings(row).add(warn("MERGED_IN", "分支里合进过别的分支：" + String.join("、", mergedIn)
                    + "。如果它们今天不上线，合 master 会把它们的代码一起带上去，先和开发确认"));
        }
        if (authors.size() > 1) {
            warnings(row).add(warn("AUTHORS", "分支里有 " + authors.size() + " 个人的提交：" + String.join("、", authors)
                    + "，确认都是这次要上线的"));
        }

        MergeRules.Files files = MergeRules.classify(MasterRepo.nameStatus(dir, m, b));
        row.put("files", Map.of("added", files.added(), "modified", files.modified(), "deleted", files.deleted(),
                "list", files.all().size() > 200 ? files.all().subList(0, 200) : files.all(), "total", files.all().size()));
        if (!files.sql().isEmpty()) {
            row.put("sqlFiles", files.sql());
            Set<String> risky = new LinkedHashSet<>();
            for (String f : files.sql()) {
                risky.addAll(MergeRules.riskySql(MasterRepo.show(dir, b, f, SQL_MAX_CHARS)));
            }
            row.put("riskySql", new ArrayList<>(risky));
            warnings(row).add(warn("SQL", "改了 " + files.sql().size() + " 个 SQL 文件，发版前先执行 SQL，再发这个服务"
                    + (risky.isEmpty() ? "" : "。注意有：" + String.join("、", risky)
                    + "——老代码还在跑时先执行可能让老服务出错，和开发确认执行时机")));
        }
        List<String> keys = MergeRules.newConfigKeys(MasterRepo.diffLines(dir, m, b, "*.java", "*.kt"));
        if (!files.config().isEmpty() || !keys.isEmpty()) {
            row.put("configFiles", files.config());
            row.put("configKeys", keys);
            List<String> parts = new ArrayList<>();
            if (!files.config().isEmpty()) {
                parts.add("改了配置文件 " + String.join("、", files.config()));
            }
            if (!keys.isEmpty()) {
                parts.add("代码新读了配置项 " + String.join("、", keys.size() > 8 ? keys.subList(0, 8) : keys)
                        + (keys.size() > 8 ? " 等 " + keys.size() + " 个" : ""));
            }
            warnings(row).add(warn("CONFIG", String.join("；", parts) + "。发版前先在配置中心加好，否则上线拿不到值会挂"));
        }
        String kind = str(row.get("kind"));
        if (ApiProjectKind.FRONTEND.equals(kind) || ApiProjectKind.CLIENT.equals(kind)) {
            warnings(row).add(warn("FRONT", "这是前端 / 客户端：依赖的后端必须先上线，后端没上就发前端会挂"));
        }
        if ("FUZZY".equals(row.get("matchHow"))) {
            warnings(row).add(warn("FUZZY", "项目是按相近名字猜的（你写的是「" + row.get("rawProject") + "」），确认没合错项目"));
        }
        row.put("status", !blockers(row).isEmpty() ? "BLOCK" : warnings(row).isEmpty() ? "OK" : "WARN");
    }

    /** 同一批里还有后端没合完，前端那行加一条提醒 */
    private static void markOrder(List<Map<String, Object>> rows) {
        List<String> pendingBackends = rows.stream()
                .filter(r -> ApiProjectKind.BACKEND.equals(r.get("kind")))
                .filter(r -> !"ALREADY".equals(r.get("status")))
                .map(r -> str(r.get("projectCode")))
                .distinct()
                .toList();
        rows.forEach(r -> r.put("pendingBackends", ApiProjectKind.BACKEND.equals(r.get("kind")) ? List.of() : pendingBackends));
    }

    // ---------------------------------------------------------------- 合并

    public Map<String, Object> merge(String projectCode, String branch, String expectSha, List<String> acked) {
        if (projectCode == null || branch == null || expectSha == null) {
            throw new IllegalArgumentException("缺项目、分支或核对时的提交，先核对再合并");
        }
        String lock = projectCode.trim().toLowerCase();
        if (!merging.add(lock)) {
            throw new IllegalStateException("这个项目正在合并中，别重复点");
        }
        try {
            return doMerge(projectCode.trim(), branch.trim(), expectSha.trim(), acked == null ? List.of() : acked);
        } finally {
            merging.remove(lock);
        }
    }

    private Map<String, Object> doMerge(String code, String branch, String expectSha, List<String> acked) {
        String token = requireToken();
        ApiProject p = projectMapper.findByCode(code);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在：" + code);
        }
        Map<String, Object> row = baseRow(null, code, branch);
        fillProject(row, p, "MANUAL");
        inspect(row);
        String status = str(row.get("status"));
        if ("ALREADY".equals(status)) {
            throw new IllegalStateException("这个分支已经在 master 里了，不用再合");
        }
        if ("BLOCK".equals(status)) {
            throw new IllegalStateException("重新核对没通过：" + String.join("；", blockers(row)));
        }
        if (!expectSha.equals(row.get("sha"))) {
            throw new IllegalStateException("核对之后分支又有新提交，请重新核对后再合");
        }
        List<String> missing = new ArrayList<>();
        for (Map<String, String> w : warnings(row)) {
            if (!acked.contains(w.get("code"))) {
                missing.add(w.get("text"));
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("还有提醒没确认：" + String.join("；", missing));
        }

        String target = str(row.get("target"));
        String base = MergeRules.apiBase(p.getGitUrl(), setting(K_GITLAB_BASE));
        String path = MergeRules.projectPath(p.getGitUrl());
        if (base == null || path == null) {
            throw new IllegalStateException("从 GitLab 地址里算不出项目路径：" + p.getGitUrl());
        }
        GitLabClient gl = new GitLabClient(base, token);
        String operator = CurrentUser.nameOr("unknown");
        GitLabClient.Mr mr = null;
        try {
            mr = gl.findOpen(path, branch, target);
            if (mr == null) {
                mr = gl.create(path, branch, target, branch + " → " + target,
                        "测试平台「合并上线」发起，操作人：" + operator + "，核对时分支提交：" + expectSha);
            }
            mr = gl.waitChecked(path, mr.iid(), 30_000);
            String ds = mr.detailed() != null ? mr.detailed() : mr.mergeStatus();
            if (!"mergeable".equals(ds) && !"can_be_merged".equals(ds)) {
                throw new GitLabClient.GitLabException(0, MergeRules.gitlabStatusText(ds));
            }
            mr = gl.merge(path, mr.iid(), expectSha);
            for (int i = 0; i < 8 && !"merged".equals(mr.state()); i++) {
                Thread.sleep(1500);
                mr = gl.get(path, mr.iid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("被中断");
        } catch (GitLabClient.GitLabException e) {
            writeLog(code, branch, target, expectSha, null, mr, "FAILED", e.getMessage(), row, operator);
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("message", e.getMessage());
            fail.put("mrUrl", mr == null ? null : mr.url());
            return fail;
        }

        boolean verified = false;
        String note;
        try {
            Path dir = Path.of(str(row.get("repoDir")));
            MasterRepo.fetch(dir, target);
            verified = MasterRepo.isAncestor(dir, expectSha, "origin/" + target);
            note = verified ? "已合并，并在 " + target + " 里核对到了这次的提交"
                    : "GitLab 说已合并，但 " + target + " 里还没看到这次的提交，稍后刷新再看";
        } catch (Exception e) {
            note = "GitLab 说已合并；回拉 " + target + " 核对失败：" + e.getMessage();
        }
        String st = "merged".equals(mr.state()) ? (verified ? "MERGED" : "MERGED_UNVERIFIED") : "FAILED";
        writeLog(code, branch, target, expectSha, mr.mergeSha(), mr, st, note, row, operator);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", "merged".equals(mr.state()));
        out.put("status", st);
        out.put("message", "merged".equals(mr.state()) ? note : "GitLab 没合成功，状态：" + mr.state());
        out.put("mrUrl", mr.url());
        out.put("mergeSha", mr.mergeSha());
        return out;
    }

    public List<Map<String, Object>> logs(int limit) {
        return jdbc.queryForList("""
                SELECT id, project_code AS projectCode, branch, target_branch AS target, source_sha AS sourceSha,
                       merge_sha AS mergeSha, mr_url AS mrUrl, status, message, operator, created_at AS createdAt
                FROM release_merge_log ORDER BY id DESC LIMIT ?
                """, Math.max(1, Math.min(limit, 200)));
    }

    private void writeLog(String code, String branch, String target, String sha, String mergeSha, GitLabClient.Mr mr,
                          String status, String message, Map<String, Object> row, String operator) {
        try {
            jdbc.update("""
                    INSERT INTO release_merge_log (project_code, branch, target_branch, source_sha, merge_sha, mr_iid, mr_url,
                        status, message, warnings, operator) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, code, branch, target, sha, mergeSha, mr == null ? null : mr.iid(), mr == null ? null : mr.url(),
                    status, message == null ? null : message.substring(0, Math.min(1000, message.length())),
                    json.writeValueAsString(warnings(row)), operator);
        } catch (Exception e) {
            log.warn("[release-merge] 写合并记录失败: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> baseRow(String raw, String code, String branch) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("raw", raw);
        row.put("projectCode", code);
        row.put("branch", branch);
        row.put("blockers", new ArrayList<String>());
        row.put("warnings", new ArrayList<Map<String, String>>());
        return row;
    }

    private static void fillProject(Map<String, Object> row, ApiProject p, String how) {
        row.put("projectCode", p.getCode());
        row.put("projectName", p.getName() == null || p.getName().isBlank() ? p.getCode() : p.getName());
        row.put("kind", ApiProjectKind.of(p));
        row.put("matchHow", how);
        row.put("target", p.getBaseBranch() == null || p.getBaseBranch().isBlank() ? "master" : p.getBaseBranch().trim());
    }

    @SuppressWarnings("unchecked")
    private static List<String> blockers(Map<String, Object> row) {
        return (List<String>) row.get("blockers");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> warnings(Map<String, Object> row) {
        return (List<Map<String, String>>) row.get("warnings");
    }

    private static Map<String, String> warn(String code, String text) {
        return Map.of("code", code, "text", text);
    }

    private static int kindOrder(String kind) {
        int i = ApiProjectKind.ORDER.indexOf(kind);
        return i < 0 ? ApiProjectKind.ORDER.size() : i;
    }

    private Path reposDir() {
        String d = setting(K_REPOS_DIR);
        return Path.of(expandHome(d == null ? defaultReposDir : d)).toAbsolutePath().normalize();
    }

    /** 上线目录必须和接口对比分析用的代码目录分开：那边会切分支、拉各种代码，这边只放 master */
    private void checkNotOverlapping(Path root) {
        Path analysis = Path.of(expandHome(analysisReposDir)).toAbsolutePath().normalize();
        if (root.startsWith(analysis) || analysis.startsWith(root)) {
            throw new IllegalArgumentException("上线目录不能和接口对比分析的代码目录（" + analysis + "）重叠，换一个目录");
        }
    }

    private static String expandHome(String p) {
        return p.startsWith("~") ? System.getProperty("user.home") + p.substring(1) : p;
    }

    private String requireToken() {
        String t = setting(tokenKey());
        if (t == null) {
            throw new IllegalStateException("还没填 GitLab 个人令牌，先点右上角「设置」填上");
        }
        return t;
    }

    private static String tokenKey() {
        String u = CurrentUser.usernameOrNull();
        return K_TOKEN + (u == null ? "local" : u);
    }

    private String setting(String k) {
        List<String> v = jdbc.queryForList("SELECT v FROM release_merge_setting WHERE k = ?", String.class, k);
        return v.isEmpty() || v.get(0) == null || v.get(0).isBlank() ? null : v.get(0);
    }

    private void put(String k, String v) {
        if (v == null) {
            jdbc.update("DELETE FROM release_merge_setting WHERE k = ?", k);
            return;
        }
        jdbc.update("""
                INSERT INTO release_merge_setting (k, v, updated_by) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE v = VALUES(v), updated_by = VALUES(updated_by)
                """, k, v, CurrentUser.nameOrNull());
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }
}
