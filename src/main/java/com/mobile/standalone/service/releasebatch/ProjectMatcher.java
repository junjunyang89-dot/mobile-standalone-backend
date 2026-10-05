package com.mobile.standalone.service.releasebatch;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.apitest.ApiAppCatalog;
import com.mobile.standalone.service.apitest.ApiProjectKind;

import java.util.List;
import java.util.Locale;

/**
 * 备注里写的项目名 → 项目管理里的项目。
 * <p>
 * 依次试：编码 / 名称 / GitLab 仓库名原样相等 → 规范化后相等（app_ 前缀、下划线、历史别名）→
 * 编辑距离 ≤ 2（app-engnie 这种手误）。Android / iOS 找名字里带对应端的客户端仓库。
 */
public final class ProjectMatcher {

    private ProjectMatcher() {
    }

    public record Match(ApiProject project, String how) {
    }

    public static Match match(String raw, List<ApiProject> projects) {
        if (raw == null || raw.isBlank() || projects == null || projects.isEmpty()) {
            return null;
        }
        String token = raw.trim();
        if (BranchNoteParser.ANDROID.equalsIgnoreCase(token) || BranchNoteParser.IOS.equalsIgnoreCase(token)) {
            return matchClient(token.toLowerCase(Locale.ROOT), projects);
        }
        for (ApiProject p : projects) {
            for (String k : keysOf(p)) {
                if (k.equalsIgnoreCase(token)) {
                    return new Match(p, "EXACT");
                }
            }
        }
        // 先按「补 app- 前缀」找真实项目，再走历史别名：
        // comment-service 有了独立的 app-comment-service 后，不能还被老别名带去 app-message-center
        String prefixed = withAppPrefix(token);
        for (ApiProject p : projects) {
            for (String k : keysOf(p)) {
                if (withAppPrefix(k).equals(prefixed)) {
                    return new Match(p, "ALIAS");
                }
            }
        }
        // 开发少写 -、写成 _、直接连写：commentservice / comment_service / appcommentservice 都当一个
        String compactWant = compact(token);
        if (compactWant.length() >= 3) {
            for (ApiProject p : projects) {
                for (String k : keysOf(p)) {
                    if (compact(k).equals(compactWant)) {
                        return new Match(p, "ALIAS");
                    }
                }
            }
        }
        String want = norm(token);
        for (ApiProject p : projects) {
            for (String k : keysOf(p)) {
                if (norm(k).equals(want)) {
                    return new Match(p, "ALIAS");
                }
            }
        }
        if (compactWant.length() >= 5) {
            ApiProject best = null;
            int bestD = 3;
            for (ApiProject p : projects) {
                for (String k : keysOf(p)) {
                    int d = Math.min(distance(norm(k), want), distance(compact(k), compactWant));
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
            if (best != null) {
                return new Match(best, "FUZZY");
            }
        }
        return null;
    }

    private static Match matchClient(String side, List<ApiProject> projects) {
        ApiProject fallback = null;
        for (ApiProject p : projects) {
            boolean hit = keysOf(p).stream().anyMatch(k -> {
                String n = k.toLowerCase(Locale.ROOT);
                return side.equals("ios") ? n.matches(".*(^|[-_\\s(（])ios($|[-_\\s)）]).*") || n.endsWith("ios")
                        : n.contains("android") || n.contains("安卓");
            });
            if (!hit) {
                continue;
            }
            if (ApiProjectKind.CLIENT.equals(ApiProjectKind.of(p))) {
                return new Match(p, "CLIENT");
            }
            if (fallback == null) {
                fallback = p;
            }
        }
        return fallback == null ? null : new Match(fallback, "CLIENT");
    }

    private static List<String> keysOf(ApiProject p) {
        List<String> keys = new java.util.ArrayList<>();
        if (p.getCode() != null && !p.getCode().isBlank()) {
            keys.add(p.getCode().trim());
        }
        if (p.getName() != null && !p.getName().isBlank()) {
            String n = p.getName().trim();
            keys.add(n);
            // 「app-swift-v3（电竞 H5 v3）」括号前那段才是仓库名
            int paren = n.indexOf('（') >= 0 ? n.indexOf('（') : n.indexOf('(');
            if (paren > 0) {
                keys.add(n.substring(0, paren).trim());
            }
        }
        String repo = repoName(p.getGitUrl());
        if (repo != null) {
            keys.add(repo);
        }
        return keys;
    }

    static String repoName(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return null;
        }
        String s = gitUrl.trim().replaceAll("/+$", "");
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String name = s.substring(cut + 1);
        return name.isBlank() ? null : name;
    }

    /** 只留字母数字、去掉开头的 app：app-comment-service / commentservice / App_Comment_Service → commentservice */
    static String compact(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return t.startsWith("app") && t.length() > 5 ? t.substring(3) : t;
    }

    static String withAppPrefix(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replaceAll("\\s+", "-");
        return t.startsWith("app-") ? t : "app-" + t;
    }

    /** 小写、下划线当横线、按历史别名归一（comment-service → app-message-center，robot → app-robot） */
    static String norm(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replaceAll("\\s+", "-");
        return ApiAppCatalog.canonicalCode(t).toLowerCase(Locale.ROOT);
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
