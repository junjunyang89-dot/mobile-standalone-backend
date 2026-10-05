package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import com.mobile.standalone.mapper.ApiProjectMapper;
import com.mobile.standalone.service.ApiProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给没有 Controller 的接口（抓包收进来的）到本地仓库里找声明它的方法，补上 handler / 源文件。
 * <p>
 * 抓包只知道 method + path，用例集合按 Controller 分组时全挤在 UnknownController 里；
 * 顺带也回答「这个接口到底是不是这个项目的」——仓库里找不到，多半是归错了项目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControllerBackfillService {

    private final ApiProjectService projectService;
    private final ApiProjectMapper projectMapper;
    private final ApiEndpointService endpointService;
    private final ApiEndpointMapper endpointMapper;

    /** 本地各后端仓库的父目录（和 Cursor 问答用的是同一个） */
    @Value("${cursor.ask.cwd:${user.home}/projects/qa_coding/app_coding}")
    private String workspace;

    /**
     * @param ids 只补这几个；空＝项目下所有还没有源文件的接口
     */
    public Map<String, Object> backfill(String project, List<String> ids) throws java.io.IOException {
        ApiProject p = projectService.get(project);
        if (p == null) {
            throw new IllegalArgumentException("项目不存在: " + project);
        }
        boolean guessed = false;
        Path root = configuredRepo(p);
        if (root == null) {
            root = guessRepo(p.getCode());
            guessed = root != null;
        }
        if (root == null) {
            throw new IllegalArgumentException("本地没找到 " + p.getCode() + " 的仓库（" + workspace
                    + " 下没有同名目录），去项目管理里配一下仓库路径");
        }

        List<ApiEndpoint> targets = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                ApiEndpoint e = endpointMapper.findById(id);
                if (e != null && p.getCode().equals(e.getProject())) {
                    targets.add(e);
                }
            }
        } else {
            for (ApiEndpoint e : endpointService.search(p.getCode(), null, null, null)) {
                if (e.getSourceFile() == null || e.getSourceFile().isBlank()) {
                    targets.add(e);
                }
            }
        }

        var scan = SourceEndpointScanner.scan(root);
        // 第一次靠猜找到的仓库记到项目上：之后的源码扫描、AI 生成、对比分析都直接用
        if (guessed && !targets.isEmpty()) {
            try {
                projectMapper.updateRepoPath(p.getCode(), root.toString());
            } catch (Exception ex) {
                log.debug("[controller-backfill] 记仓库路径失败 {}: {}", p.getCode(), ex.getMessage());
            }
        }

        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        List<Map<String, Object>> notFound = new ArrayList<>();
        for (ApiEndpoint e : targets) {
            var r = SourceControllerMatcher.match(e.getHttpMethod(), e.getPath(), scan.endpoints());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("method", e.getHttpMethod());
            row.put("path", e.getPath());
            switch (r.kind()) {
                case EXACT, SUFFIX -> {
                    var hit = r.hit();
                    e.setHandler(hit.handler());
                    e.setSourceFile(hit.file());
                    if ((e.getSummary() == null || e.getSummary().isBlank())
                            && hit.summary() != null && !hit.summary().isBlank()) {
                        e.setSummary(hit.summary());
                    }
                    endpointMapper.update(e);
                    row.put("controller", ApiTestCollectionService.controllerOfEndpoint(e));
                    row.put("handler", hit.handler());
                    row.put("file", hit.file());
                    row.put("codePath", hit.path());
                    row.put("how", r.kind().name());
                    matched.add(row);
                }
                case AMBIGUOUS -> {
                    row.put("candidates", r.candidates().stream()
                            .map(c -> c.file() + " " + c.httpMethod() + " " + c.path()).toList());
                    ambiguous.add(row);
                }
                default -> notFound.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", p.getCode());
        out.put("repo", root.toString());
        out.put("repoGuessed", guessed);
        out.put("scannedEndpoints", scan.endpoints().size());
        out.put("total", targets.size());
        out.put("matched", matched);
        out.put("ambiguous", ambiguous);
        out.put("notFound", notFound);
        return out;
    }

    /** 项目上配的仓库路径，没配就按项目码在工作区里猜；都没有返回 null */
    public Path repoOf(ApiProject p) {
        Path root = configuredRepo(p);
        return root != null ? root : guessRepo(p.getCode());
    }

    private static Path configuredRepo(ApiProject p) {
        if (p.getRepoPath() == null || p.getRepoPath().isBlank()) {
            return null;
        }
        Path dir = Path.of(p.getRepoPath().trim()).toAbsolutePath().normalize();
        return Files.isDirectory(dir) ? dir : null;
    }

    /** 项目码和本地目录名的几种常见对应：app-engine、engine、app_engine… */
    Path guessRepo(String code) {
        if (code == null || code.isBlank() || workspace == null || workspace.isBlank()) {
            return null;
        }
        Path ws = Path.of(workspace.trim());
        if (!Files.isDirectory(ws)) {
            return null;
        }
        for (String name : repoNameCandidates(code)) {
            Path dir = ws.resolve(name);
            if (looksLikeSourceRepo(dir)) {
                return dir.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /** Vue / H5 仓库常常没有顶层 src，只有 frontend/ 或 package.json */
    public static boolean looksLikeSourceRepo(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        return Files.exists(dir.resolve(".git"))
                || Files.isDirectory(dir.resolve("src"))
                || Files.isDirectory(dir.resolve("frontend"))
                || Files.isRegularFile(dir.resolve("package.json"))
                || Files.isRegularFile(dir.resolve("pom.xml"));
    }

    public static List<String> repoNameCandidates(String code) {
        String c = code.trim();
        String bare = c.replaceFirst("(?i)^app[-_]", "");
        Set<String> out = new LinkedHashSet<>();
        out.add(c);
        out.add(c.replace('-', '_'));
        out.add(bare);
        out.add(bare.replace('-', '_'));
        return new ArrayList<>(out);
    }
}
