package com.mobile.standalone.service.convention;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.convention.ConventionRules.Baseline;
import com.mobile.standalone.service.convention.ConventionRules.Observation;
import com.mobile.standalone.service.convention.ConventionRules.Result;
import com.mobile.standalone.service.convention.ConventionRules.Rule;
import com.mobile.standalone.service.convention.ConventionRules.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 对本机业务仓库跑 {@link ConventionRules}。不要设备，秒级。
 * <p>
 * 报告自成一类（{@code kind=CONVENTION}），不进用例通过率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConventionCheckService {

    private final ObjectMapper objectMapper;

    @Value("${convention-check.workspace:}")
    private String workspace;

    @Value("${case-ask.workspace:}")
    private String caseAskWorkspace;

    @Value("${app.data-dir:${user.home}/.mobile-standalone/data}")
    private String dataDir;

    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : ConventionRules.DEFAULTS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("title", r.title());
            m.put("why", r.why());
            m.put("type", r.type());
            m.put("repo", r.repo());
            out.add(m);
        }
        return out;
    }

    public synchronized Map<String, Object> run() {
        Map<String, Baseline> baselines = loadBaselines();
        List<Result> results = new ArrayList<>();
        Map<String, String> commits = new TreeMap<>();
        for (Rule rule : ConventionRules.DEFAULTS) {
            Path root = repoRoot(rule.repo());
            if (root == null) {
                results.add(ConventionRules.skipped(rule, "本机找不到仓库 " + rule.repo()
                        + "（配置 convention-check.workspace 指向 app_coding 目录）"));
                continue;
            }
            commits.computeIfAbsent(rule.repo(), k -> headCommit(root));
            try {
                results.add(ConventionRules.evaluate(rule, ConventionRules.observe(rule, root),
                        baselines.get(rule.id())));
            } catch (IOException e) {
                results.add(ConventionRules.skipped(rule, "扫描失败：" + e.getMessage()));
            }
        }
        return report(results, commits);
    }

    /**
     * @param ruleIds 为空表示全部
     * @param force   允许把计数类 baseline 往上锁
     */
    public synchronized Map<String, Object> lockBaseline(List<String> ruleIds, boolean force, String reason) {
        Map<String, Baseline> baselines = loadBaselines();
        List<String> locked = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Rule rule : ConventionRules.DEFAULTS) {
            if (ruleIds != null && !ruleIds.isEmpty() && !ruleIds.contains(rule.id())) {
                continue;
            }
            Path root = repoRoot(rule.repo());
            if (root == null) {
                refused.add(rule.id() + "：本机找不到仓库 " + rule.repo());
                continue;
            }
            try {
                Observation obs = ConventionRules.observe(rule, root);
                if (!obs.missingPaths().isEmpty()) {
                    refused.add(rule.id() + "：文件不在 " + String.join("、", obs.missingPaths()));
                    continue;
                }
                Baseline next = ConventionRules.lock(rule, obs, baselines.get(rule.id()), force, reason,
                        headCommit(root));
                if (next != null) {
                    baselines.put(rule.id(), next);
                    locked.add(rule.id());
                }
            } catch (IllegalStateException | IOException e) {
                refused.add(e.getMessage());
            }
        }
        saveBaselines(baselines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locked", locked);
        out.put("refused", refused);
        out.put("report", run());
        return out;
    }

    private static Map<String, Object> report(List<Result> results, Map<String, String> commits) {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0);
        }
        results.forEach(r -> counts.merge(r.status(), 1, Integer::sum));

        String verdict;
        if (counts.get(Status.REGRESSED) > 0) {
            verdict = "RISK";
        } else if (counts.get(Status.SKIPPED) > 0 || counts.get(Status.NO_BASELINE) > 0) {
            verdict = "INCOMPLETE";
        } else {
            verdict = "CLEAN";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "CONVENTION");
        out.put("countsTowardPassRate", false);
        out.put("note", "约定检查产出的是风险（暴露面变大），不是缺陷；不计入用例通过率");
        out.put("verdict", verdict);
        out.put("summary", counts);
        out.put("commits", commits);
        out.put("ranAt", OffsetDateTime.now().toString());
        out.put("results", results);
        return out;
    }

    private Path repoRoot(String repo) {
        List<Path> candidates = new ArrayList<>();
        for (String w : new String[]{workspace, caseAskWorkspace}) {
            if (w != null && !w.isBlank()) {
                Path ws = Path.of(w.trim());
                candidates.add(ws.resolve(repo));
                candidates.add(ws);
            }
        }
        Path here = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (here.getParent() != null) {
            candidates.add(here.getParent().resolve("app_coding").resolve(repo));
        }
        candidates.add(Path.of(System.getProperty("user.home", ""), "projects/qa_coding/app_coding", repo));
        for (Path p : candidates) {
            if (Files.isDirectory(p) && p.getFileName() != null && p.getFileName().toString().equals(repo)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String headCommit(Path root) {
        try {
            Process p = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Path baselineFile() {
        return Path.of(dataDir, "convention-baseline.json");
    }

    private Map<String, Baseline> loadBaselines() {
        Path f = baselineFile();
        if (!Files.isRegularFile(f)) {
            return new TreeMap<>();
        }
        try {
            return new TreeMap<>(objectMapper.readValue(f.toFile(), new TypeReference<Map<String, Baseline>>() {
            }));
        } catch (IOException e) {
            // 读坏了当成没有 baseline：所有计数规则会显示 NO_BASELINE，而不是悄悄变绿
            log.warn("[convention] baseline 读取失败 {}: {}", f, e.getMessage());
            return new TreeMap<>();
        }
    }

    private void saveBaselines(Map<String, Baseline> baselines) {
        Path f = baselineFile();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), baselines);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("baseline 写入失败：" + e.getMessage(), e);
        }
    }
}
