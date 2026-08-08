package com.mobile.standalone.service;

import com.mobile.standalone.config.ChangeReviewProperties;
import com.mobile.standalone.dto.ChangeReviewDtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeReviewService {

    private static final Pattern BRANCH_LINE = Pattern.compile(
            "(?i)^\\s*([\\w./@-]+)\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern NUMSTAT = Pattern.compile("^(\\d+|-)\t(\\d+|-)\t(.+)$");

    private final ChangeReviewProperties props;

    public DiffResponse analyze(DiffRequest req) {
        if (!props.isEnabled()) {
            throw new IllegalStateException("change-review 已禁用（change-review.enabled=false）");
        }
        if (req == null || req.getProjects() == null || req.getProjects().isEmpty()) {
            throw new IllegalArgumentException("请至少配置一个项目");
        }
        DiffResponse out = new DiffResponse();
        out.setWeekLabel(blankToNull(req.getWeekLabel()));
        List<ChecklistItem> checklist = req.getChecklist() != null ? req.getChecklist() : List.of();

        for (ProjectSpec spec : req.getProjects()) {
            out.getProjects().add(analyzeOne(spec, checklist));
        }

        out.setGaps(buildGaps(out.getProjects(), checklist));
        out.setDbCompare(compareDb(out.getProjects(), req.getReleaseDbNotes()));
        return out;
    }

    public ImportBranchesResponse parseReleaseBranches(ImportBranchesRequest req) {
        ImportBranchesResponse out = new ImportBranchesResponse();
        if (req == null || req.getRows() == null) return out;
        StringBuilder dbNotes = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (BranchRow row : req.getRows()) {
            if (row.getDbChanges() != null && !row.getDbChanges().isBlank()
                    && !"无".equals(row.getDbChanges().trim()) && !"-".equals(row.getDbChanges().trim())) {
                if (dbNotes.length() > 0) dbNotes.append("\n\n");
                if (row.getSummary() != null) dbNotes.append("# ").append(row.getSummary()).append('\n');
                dbNotes.append(row.getDbChanges().trim());
            }
            if (row.getBranches() == null || row.getBranches().isBlank()) continue;
            for (String line : row.getBranches().split("\\R")) {
                String t = line.trim();
                if (t.isEmpty() || "无".equals(t) || "-".equals(t)) continue;
                Matcher m = BRANCH_LINE.matcher(t);
                String repo;
                String branch;
                if (m.matches()) {
                    repo = m.group(1).trim();
                    branch = m.group(2).trim();
                } else {
                    repo = "";
                    branch = t;
                }
                String key = repo + "|" + branch;
                if (!seen.add(key)) continue;
                ParsedBranch pb = new ParsedBranch();
                pb.setRepoHint(repo);
                pb.setBranch(branch);
                pb.setFromSummary(row.getSummary());
                pb.setSuggestedType(guessType(repo, branch));
                out.getItems().add(pb);
            }
        }
        out.setReleaseDbNotes(dbNotes.toString());
        return out;
    }

    private ProjectResult analyzeOne(ProjectSpec spec, List<ChecklistItem> checklist) {
        ProjectResult r = new ProjectResult();
        r.setName(blankTo(spec.getName(), "未命名"));
        r.setType(normalizeType(spec.getType()));
        r.setBaseRef(blankTo(spec.getBaseRef(), "master"));
        r.setHeadRef(spec.getHeadRef());
        try {
            Path root = resolveAllowedRepo(spec.getPath());
            r.setPath(root.toString());
            if (spec.getHeadRef() == null || spec.getHeadRef().isBlank()) {
                throw new IllegalArgumentException("请填写本周分支 headRef");
            }
            if (props.isAllowFetch()) {
                tryFetch(root, r.getBaseRef(), r.getHeadRef());
            }
            ensureRefExists(root, r.getBaseRef());
            ensureRefExists(root, r.getHeadRef());

            List<String> pathspecs = spec.getPathspecs() != null ? spec.getPathspecs() : List.of();
            Map<String, int[]> numstat = parseNumstat(root, r.getBaseRef(), r.getHeadRef(), pathspecs);
            Map<String, String> nameStatus = parseNameStatus(root, r.getBaseRef(), r.getHeadRef(), pathspecs);
            r.setRawStat(runGit(root, buildDiffArgs(r.getBaseRef(), r.getHeadRef(), pathspecs, "--stat")));

            int ins = 0;
            int del = 0;
            List<FileChange> files = new ArrayList<>();
            Set<String> allPaths = new LinkedHashSet<>();
            allPaths.addAll(nameStatus.keySet());
            allPaths.addAll(numstat.keySet());
            for (String fp : allPaths) {
                FileChange fc = new FileChange();
                fc.setPath(fp);
                fc.setStatus(nameStatus.getOrDefault(fp, "M"));
                fc.setCategory(categorize(fp));
                fc.setModule(moduleOf(fp));
                int[] nd = numstat.getOrDefault(fp, new int[]{0, 0});
                fc.setInsertions(nd[0]);
                fc.setDeletions(nd[1]);
                ins += nd[0];
                del += nd[1];
                matchChecklist(fc, checklist, r.getType());
                files.add(fc);
            }
            files.sort(Comparator.comparing(FileChange::getPath));
            r.setFiles(files);
            r.setFilesChanged(files.size());
            r.setInsertions(ins);
            r.setDeletions(del);
            r.setModules(aggregateModules(files));
            r.setOk(true);
            r.setMessage(files.isEmpty() ? "无文件改动" : "OK");
        } catch (Exception e) {
            r.setOk(false);
            r.setMessage(e.getMessage() != null ? e.getMessage() : e.toString());
            log.warn("change-review 项目失败 {}: {}", r.getName(), r.getMessage());
        }
        return r;
    }

    private void matchChecklist(FileChange fc, List<ChecklistItem> checklist, String projectType) {
        if (checklist == null || checklist.isEmpty()) return;
        String blob = (fc.getPath() + " " + fc.getModule() + " " + fc.getCategory()).toLowerCase(Locale.ROOT);
        for (ChecklistItem item : checklist) {
            if (item.getProjectType() != null && !item.getProjectType().isBlank()
                    && !item.getProjectType().equalsIgnoreCase(projectType)
                    && !"OTHER".equalsIgnoreCase(item.getProjectType())) {
                continue;
            }
            List<String> kws = splitKeywords(item.getKeywords());
            if (kws.isEmpty() && item.getTitle() != null) {
                kws = splitKeywords(item.getTitle());
            }
            for (String kw : kws) {
                if (kw.length() < 2) continue;
                if (blob.contains(kw.toLowerCase(Locale.ROOT))) {
                    fc.setChecklistHit(true);
                    String id = item.getId() != null ? item.getId() : item.getTitle();
                    if (id != null && !fc.getMatchedChecklistIds().contains(id)) {
                        fc.getMatchedChecklistIds().add(id);
                    }
                    break;
                }
            }
        }
    }

    private List<ModuleAgg> aggregateModules(List<FileChange> files) {
        Map<String, ModuleAgg> map = new LinkedHashMap<>();
        for (FileChange f : files) {
            String m = f.getModule() != null ? f.getModule() : "(root)";
            ModuleAgg a = map.computeIfAbsent(m, k -> {
                ModuleAgg x = new ModuleAgg();
                x.setModule(k);
                return x;
            });
            a.setFileCount(a.getFileCount() + 1);
            if (f.getCategory() != null && !a.getCategories().contains(f.getCategory())) {
                a.getCategories().add(f.getCategory());
            }
            if (f.isChecklistHit()) a.setChecklistHit(true);
        }
        return new ArrayList<>(map.values());
    }

    private List<GapHint> buildGaps(List<ProjectResult> projects, List<ChecklistItem> checklist) {
        List<GapHint> gaps = new ArrayList<>();
        Set<String> hitIds = new HashSet<>();
        for (ProjectResult p : projects) {
            if (!p.isOk()) continue;
            for (ModuleAgg m : p.getModules()) {
                boolean looksRisky = m.getCategories().stream()
                        .anyMatch(c -> Set.of("UI", "API", "SQL").contains(c));
                if (looksRisky && !m.isChecklistHit() && checklist != null && !checklist.isEmpty()) {
                    GapHint g = new GapHint();
                    g.setKind("MODULE_UNCOVERED");
                    g.setProjectName(p.getName());
                    g.setTitle("模块有改动但未命中必测清单: " + m.getModule());
                    g.setDetail("类别=" + String.join(",", m.getCategories()) + "，文件数=" + m.getFileCount());
                    g.setSeverity("warn");
                    gaps.add(g);
                } else if (looksRisky && (checklist == null || checklist.isEmpty())) {
                    GapHint g = new GapHint();
                    g.setKind("MODULE_UNCOVERED");
                    g.setProjectName(p.getName());
                    g.setTitle("建议人工确认是否必测: " + m.getModule());
                    g.setDetail("类别=" + String.join(",", m.getCategories()) + "（尚未导入必测清单）");
                    g.setSeverity("info");
                    gaps.add(g);
                }
            }
            for (FileChange f : p.getFiles()) {
                hitIds.addAll(f.getMatchedChecklistIds());
            }
        }
        if (checklist != null) {
            for (ChecklistItem item : checklist) {
                String id = item.getId() != null ? item.getId() : item.getTitle();
                if (id == null || hitIds.contains(id)) continue;
                GapHint g = new GapHint();
                g.setKind("CHECKLIST_NO_DIFF");
                g.setTitle("必测项本周 diff 未命中: " + item.getTitle());
                g.setDetail("关键字=" + item.getKeywords() + "（可能未改到相关代码，或关键字需调整）");
                g.setSeverity("info");
                gaps.add(g);
            }
        }
        return gaps;
    }

    private DbCompareResult compareDb(List<ProjectResult> projects, String releaseDbNotes) {
        DbCompareResult r = new DbCompareResult();
        List<String> sqlFiles = new ArrayList<>();
        for (ProjectResult p : projects) {
            if (!p.isOk()) continue;
            for (FileChange f : p.getFiles()) {
                if ("SQL".equals(f.getCategory()) || f.getPath().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                    sqlFiles.add("[" + p.getName() + "] " + f.getPath());
                    r.getSqlFilesInDiff().add(f.getPath());
                }
            }
        }
        String notes = releaseDbNotes != null ? releaseDbNotes : "";
        String notesLower = notes.toLowerCase(Locale.ROOT);
        for (String f : r.getSqlFilesInDiff()) {
            String base = Path.of(f).getFileName().toString().toLowerCase(Locale.ROOT);
            String stem = base.replace(".sql", "");
            if (notes.isBlank() || (!notesLower.contains(base) && !notesLower.contains(stem)
                    && !notesLower.contains(f.toLowerCase(Locale.ROOT)))) {
                r.getSqlFilesMissingInNotes().add(f);
            }
        }
        if (!notes.isBlank()) {
            for (String line : notes.split("\\R")) {
                String t = line.trim();
                if (t.length() < 8) continue;
                if (!looksLikeSqlHint(t)) continue;
                boolean hit = r.getSqlFilesInDiff().stream().anyMatch(f -> {
                    String base = Path.of(f).getFileName().toString().toLowerCase(Locale.ROOT);
                    return t.toLowerCase(Locale.ROOT).contains(base)
                            || t.toLowerCase(Locale.ROOT).contains(base.replace(".sql", ""));
                });
                if (!hit && (t.toLowerCase(Locale.ROOT).contains("create ")
                        || t.toLowerCase(Locale.ROOT).contains("alter ")
                        || t.contains("库下执行"))) {
                    r.getNoteLinesWithoutFileHint().add(t.length() > 120 ? t.substring(0, 120) + "…" : t);
                }
            }
        }
        if (sqlFiles.isEmpty() && notes.isBlank()) {
            r.setSummary("本周无 SQL 文件改动，也未提供发版 DB 备注");
        } else if (!r.getSqlFilesMissingInNotes().isEmpty()) {
            r.setSummary("代码中有 SQL 改动，但发版 DB 备注可能未覆盖（" + r.getSqlFilesMissingInNotes().size() + " 个文件）");
        } else if (!notes.isBlank() && sqlFiles.isEmpty()) {
            r.setSummary("发版备注有 DB 变更，但本周 diff 未扫到 .sql 文件（请确认 DB 仓路径/分支）");
        } else {
            r.setSummary("SQL 文件与发版备注大致可对照");
        }
        return r;
    }

    private static boolean looksLikeSqlHint(String t) {
        String l = t.toLowerCase(Locale.ROOT);
        return l.contains("create ") || l.contains("alter ") || l.contains("drop ")
                || l.contains("insert ") || l.contains(".sql") || t.contains("库下执行");
    }

    Path resolveAllowedRepo(String rawPath) throws Exception {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("仓库路径不能为空");
        }
        if (rawPath.contains("..")) {
            throw new IllegalArgumentException("路径不允许包含 ..");
        }
        Path p = Path.of(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("路径不是目录: " + p);
        }
        if (!Files.isDirectory(p.resolve(".git")) && !isGitWorktree(p)) {
            throw new IllegalArgumentException("不是 git 仓库（缺少 .git）: " + p);
        }
        List<String> roots = props.getAllowedRoots();
        if (roots != null && !roots.isEmpty()) {
            boolean under = false;
            for (String root : roots) {
                if (root == null || root.isBlank()) continue;
                Path allowed = Path.of(root.trim()).toAbsolutePath().normalize();
                if (p.startsWith(allowed)) {
                    under = true;
                    break;
                }
            }
            if (!under) {
                throw new IllegalArgumentException("路径不在 change-review.allowed-roots 白名单内: " + p);
            }
        } else {
            Path home = Path.of(System.getProperty("user.home", "/")).toAbsolutePath().normalize();
            Path tmp = Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize();
            if (!p.startsWith(home) && !p.startsWith(tmp) && !p.startsWith(Path.of("/Users").toAbsolutePath())
                    && !p.startsWith(Path.of("/home").toAbsolutePath())
                    && !p.startsWith(Path.of("/opt").toAbsolutePath())
                    && !p.startsWith(Path.of("/data").toAbsolutePath())
                    && !p.startsWith(Path.of("/var").toAbsolutePath())) {
                // 仍允许常见工程目录；若都不匹配则拒绝极端路径如 /
                if (p.getNameCount() < 2) {
                    throw new IllegalArgumentException("拒绝过短或不安全的路径: " + p);
                }
            }
        }
        return p;
    }

    private boolean isGitWorktree(Path p) {
        try {
            String out = runGit(p, List.of("rev-parse", "--is-inside-work-tree"));
            return out.trim().equalsIgnoreCase("true");
        } catch (Exception e) {
            return false;
        }
    }

    private void tryFetch(Path root, String base, String head) {
        try {
            runGit(root, List.of("fetch", "origin", base, head, "--no-tags"));
        } catch (Exception e) {
            log.info("git fetch 跳过/失败: {}", e.getMessage());
        }
    }

    private void ensureRefExists(Path root, String ref) throws Exception {
        try {
            runGit(root, List.of("rev-parse", "--verify", ref));
        } catch (Exception e) {
            // 尝试 origin/ref
            try {
                runGit(root, List.of("rev-parse", "--verify", "origin/" + ref));
            } catch (Exception e2) {
                throw new IllegalArgumentException("本地找不到 ref: " + ref + "（请先 git fetch / checkout）");
            }
        }
    }

    private String resolveRef(Path root, String ref) throws Exception {
        try {
            runGit(root, List.of("rev-parse", "--verify", ref));
            return ref;
        } catch (Exception e) {
            runGit(root, List.of("rev-parse", "--verify", "origin/" + ref));
            return "origin/" + ref;
        }
    }

    private Map<String, int[]> parseNumstat(Path root, String base, String head, List<String> pathspecs) throws Exception {
        String b = resolveRef(root, base);
        String h = resolveRef(root, head);
        String out = runGit(root, buildDiffArgs(b, h, pathspecs, "--numstat"));
        Map<String, int[]> map = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            Matcher m = NUMSTAT.matcher(line.trim());
            if (!m.matches()) continue;
            int a = "-".equals(m.group(1)) ? 0 : Integer.parseInt(m.group(1));
            int d = "-".equals(m.group(2)) ? 0 : Integer.parseInt(m.group(2));
            map.put(m.group(3), new int[]{a, d});
        }
        return map;
    }

    private Map<String, String> parseNameStatus(Path root, String base, String head, List<String> pathspecs) throws Exception {
        String b = resolveRef(root, base);
        String h = resolveRef(root, head);
        String out = runGit(root, buildDiffArgs(b, h, pathspecs, "--name-status"));
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : out.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+", 2);
            if (parts.length < 2) continue;
            String status = parts[0];
            String path = parts[1];
            // renames: R100 old new
            if (status.startsWith("R") || status.startsWith("C")) {
                String[] rp = t.split("\\s+");
                if (rp.length >= 3) {
                    status = rp[0].substring(0, 1);
                    path = rp[rp.length - 1];
                }
            }
            map.put(path, status.substring(0, 1));
        }
        return map;
    }

    private List<String> buildDiffArgs(String base, String head, List<String> pathspecs, String mode) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        args.add(mode);
        args.add(base + "..." + head);
        if (pathspecs != null && !pathspecs.isEmpty()) {
            args.add("--");
            args.addAll(pathspecs);
        }
        return args;
    }

    String runGit(Path cwd, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=false");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        boolean finished = p.waitFor(Math.max(5, props.getTimeoutSec()), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git 超时: " + String.join(" ", args));
        }
        int code = p.exitValue();
        if (code != 0) {
            String msg = sb.toString();
            throw new IllegalStateException(msg.isBlank() ? ("git 失败 exit=" + code) : msg);
        }
        return sb.toString();
    }

    static String categorize(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.endsWith(".sql") || p.contains("/migration") || p.contains("/migrations/")
                || p.contains("/db/") || p.contains("/liquibase") || p.contains("/flyway")) {
            return "SQL";
        }
        if (p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties")
                || p.endsWith(".env") || p.contains("/config/") || p.endsWith(".conf")
                || p.endsWith(".toml") || p.endsWith(".ini")) {
            return "CONFIG";
        }
        if (p.contains("/controller/") || p.contains("/api/") || p.contains("/handler/")
                || p.contains("/router/") || p.contains("/endpoint") || p.contains("/rpc/")
                || p.contains("/grpc/") || p.endsWith("controller.java") || p.endsWith("controller.go")
                || p.endsWith("controller.kt")) {
            return "API";
        }
        if (p.contains("/res/layout") || p.contains("/res/drawable") || p.endsWith(".vue")
                || p.endsWith(".tsx") || p.endsWith(".jsx") || p.contains("/pages/")
                || p.contains("/components/") || p.contains("/views/") || p.contains("/fragment")
                || p.contains("/activity") || p.endsWith(".xml") && p.contains("/layout")
                || p.endsWith(".swift") || p.endsWith(".storyboard") || p.endsWith(".xib")) {
            return "UI";
        }
        return "OTHER";
    }

    static String moduleOf(String path) {
        String p = path.replace('\\', '/');
        String[] parts = p.split("/");
        if (parts.length >= 2) return parts[0] + "/" + parts[1];
        if (parts.length == 1) return parts[0];
        return "(root)";
    }

    static String normalizeType(String type) {
        if (type == null || type.isBlank()) return "OTHER";
        String t = type.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "APP", "ANDROID", "IOS", "CLIENT" -> "APP";
            case "H5", "WEB", "FRONTEND" -> "H5";
            case "JAVA", "JVM", "SPRING" -> "JAVA";
            case "GO", "GOLANG" -> "GOLANG";
            case "DB", "SQL", "DATABASE" -> "DB";
            default -> t;
        };
    }

    static String guessType(String repo, String branch) {
        String blob = ((repo != null ? repo : "") + " " + (branch != null ? branch : "")).toLowerCase(Locale.ROOT);
        if (blob.contains("android") || blob.contains("ios") || blob.contains("client") || blob.contains("app-")) {
            return "APP";
        }
        if (blob.contains("h5") || blob.contains("web") || blob.contains("fe")) return "H5";
        if (blob.contains("sql") || blob.contains("db") || blob.contains("migration")) return "DB";
        if (blob.contains("go") || blob.contains("golang")) return "GOLANG";
        if (blob.contains("java") || blob.contains("api") || blob.contains("service") || blob.contains("backend")) {
            return "JAVA";
        }
        return "OTHER";
    }

    private static List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,，\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String blankTo(String s, String d) {
        return s == null || s.isBlank() ? d : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
