package com.mobile.standalone.service.releasemerge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合并上线用到的纯规则：解析粘贴的「项目：分支」、识别分支里夹带的别的分支、
 * SQL / 配置改动和危险 SQL、GitLab 地址换算。不碰 git、不碰网络，方便单测。
 */
public final class MergeRules {

    private MergeRules() {
    }

    public record Line(int no, String raw, String project, String branch, String error) {
    }

    private static final Pattern PROJECT_BRANCH = Pattern.compile("^(.+?)\\s*[:：]\\s*(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·]|\\d+[.、)）])\\s*");
    private static final Pattern NOISE = Pattern.compile("[，,（(].*$");
    private static final Pattern BRANCH_OK = Pattern.compile("^[A-Za-z0-9._/\\-]+$");

    /** 每行一个「项目：分支」；也认「项目 分支」「项目<TAB>分支」，开头的序号、横杠去掉 */
    public static List<Line> parseLines(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty()) {
                continue;
            }
            String s = BULLET.matcher(raw).replaceFirst("").trim();
            String project;
            String rest;
            Matcher m = PROJECT_BRANCH.matcher(s);
            if (m.matches() && !s.matches("^[a-z]+://.*")) {
                project = m.group(1).trim();
                rest = m.group(2).trim();
            } else {
                String[] parts = s.split("\\s+", 2);
                if (parts.length < 2) {
                    out.add(new Line(i + 1, raw, null, null, "没看出项目和分支，按「项目：分支」写"));
                    continue;
                }
                project = parts[0].trim();
                rest = parts[1].trim();
            }
            String branch = NOISE.matcher(rest).replaceFirst("").trim().split("\\s+")[0];
            String err = validBranch(branch) ? null : "分支名不对：" + branch;
            for (String one : project.split("\\s*[、,，+]\\s*")) {
                if (!one.isBlank()) {
                    out.add(new Line(i + 1, raw, one.trim(), err == null ? branch : null, err));
                }
            }
        }
        return out;
    }

    public static boolean validBranch(String b) {
        return b != null && !b.isBlank() && BRANCH_OK.matcher(b).matches()
                && !b.startsWith("-") && !b.startsWith("/") && !b.endsWith("/")
                && !b.contains("..") && !b.contains("//") && !b.endsWith(".lock");
    }

    private static final List<Pattern> MERGE_SUBJECT = List.of(
            Pattern.compile("^Merge (?:remote-tracking )?branch '([^']+)'"),
            Pattern.compile("^Merge branch \"([^\"]+)\""));

    /**
     * 分支历史里合进过哪些别的分支（不含目标分支和它自己）。
     * 开发从别人没上线的分支拉、或者把 test / dev 环境分支合进来，一合 master 就把别人的代码带上了。
     */
    public static List<String> mergedInBranches(List<String> mergeSubjects, String target, String self) {
        Set<String> out = new LinkedHashSet<>();
        for (String subj : mergeSubjects == null ? List.<String>of() : mergeSubjects) {
            for (Pattern p : MERGE_SUBJECT) {
                Matcher m = p.matcher(subj.trim());
                if (m.find()) {
                    String name = m.group(1).replaceFirst("^origin/", "");
                    if (!name.equalsIgnoreCase(target) && !name.equals(self)) {
                        out.add(name);
                    }
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    public record Files(int added, int modified, int deleted, List<String> sql, List<String> config, List<String> all) {
    }

    /** {@code git diff --name-status} 的输出 → 增删改计数 + SQL 文件 + 配置文件 */
    public static Files classify(List<String> nameStatus) {
        int a = 0, m = 0, d = 0;
        List<String> sql = new ArrayList<>();
        List<String> config = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (String line : nameStatus == null ? List.<String>of() : nameStatus) {
            String[] parts = line.trim().split("\t");
            if (parts.length < 2) {
                continue;
            }
            String st = parts[0];
            String path = parts[parts.length - 1];
            all.add(st.charAt(0) + " " + path);
            switch (st.charAt(0)) {
                case 'A' -> a++;
                case 'D' -> d++;
                default -> m++;
            }
            if (st.charAt(0) == 'D') {
                continue;
            }
            if (isSql(path)) {
                sql.add(path);
            } else if (isConfig(path)) {
                config.add(path);
            }
        }
        return new Files(a, m, d, sql, config, all);
    }

    static boolean isSql(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".sql") || p.contains("/db/migration/") || p.contains("changelog") && (p.endsWith(".xml") || p.endsWith(".yaml"));
    }

    static boolean isConfig(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (p.contains("/test/") || p.contains("/mock/")) {
            return false;
        }
        return name.matches("(application|bootstrap)([-.][\\w-]+)?\\.(ya?ml|properties)")
                || name.matches("\\.env(\\.[\\w-]+)?")
                || name.matches("config(\\.[\\w-]+)?\\.(ya?ml|toml|ini)")
                || p.contains("/nacos/") || p.contains("/apollo/");
    }

    private static final Map<String, Pattern> RISKY_SQL = Map.of(
            "删表 DROP TABLE", Pattern.compile("\\bdrop\\s+table\\b"),
            "删列 DROP COLUMN", Pattern.compile("\\balter\\s+table\\b[^;]*\\bdrop\\s+(column\\s+)?(?!index|key|primary|foreign|constraint)`?\\w"),
            "改列名 / 改表名", Pattern.compile("\\brename\\s+(column|to|table)\\b|\\bchange\\s+(column\\s+)?`?\\w+`?\\s+`?\\w"),
            "清空表 TRUNCATE", Pattern.compile("\\btruncate\\s+(table\\s+)?`?\\w"),
            "没带 WHERE 的 DELETE / UPDATE", Pattern.compile("\\b(delete\\s+from\\s+`?\\w+`?|update\\s+`?\\w+`?\\s+set\\s+[^;]*?)\\s*;"));

    /**
     * 老代码还在跑时先执行会让老服务出错、或者执行了回不去的 SQL。
     * 只做文本匹配，标出来是提醒去和开发确认，不代表一定有问题。
     */
    public static List<String> riskySql(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)(--|#).*$", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : RISKY_SQL.entrySet()) {
            Matcher m = e.getValue().matcher(s);
            while (m.find()) {
                if (e.getKey().startsWith("没带 WHERE") && m.group().contains(" where ")) {
                    continue;
                }
                out.add(e.getKey());
                break;
            }
        }
        Matcher add = ADD_COLUMN.matcher(s);
        while (add.find()) {
            String def = add.group(1);
            if (def.contains("not null") && !def.contains("default") && !def.contains("auto_increment")) {
                out.add("加了不带默认值的非空列");
                break;
            }
        }
        out.sort(null);
        return out;
    }

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "\\badd\\s+(?:column\\s+)?(?!index|key|unique|primary|foreign|constraint|fulltext|spatial)(`?\\w+`?\\s+[^;,]*)");

    private static final List<Pattern> CONFIG_KEY = List.of(
            Pattern.compile("@(?:Value|NacosValue)\\(\\s*(?:value\\s*=\\s*)?\"\\$\\{([^}:\"]+)"),
            Pattern.compile("@ConfigurationProperties\\(\\s*(?:prefix\\s*=\\s*|value\\s*=\\s*)?\"([^\"]+)\""),
            Pattern.compile("getProperty\\(\\s*\"([\\w.-]+)\""));

    /** diff 里新增行（+ 开头）新读的配置项：代码读了、发版时配置中心没加，上线就会拿不到值 */
    public static List<String> newConfigKeys(List<String> diffLines) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : diffLines == null ? List.<String>of() : diffLines) {
            if (!l.startsWith("+") || l.startsWith("+++")) {
                continue;
            }
            for (int i = 0; i < CONFIG_KEY.size(); i++) {
                Matcher m = CONFIG_KEY.get(i).matcher(l);
                while (m.find()) {
                    out.add(i == 1 ? m.group(1) + ".*" : m.group(1));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** GitLab 接口根地址：设置里填了就用填的，否则按仓库地址的主机拼 https://主机 */
    public static String apiBase(String gitUrl, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim().replaceAll("/+$", "").replaceAll("/api/v4$", "");
        }
        if (gitUrl == null || gitUrl.isBlank()) {
            return null;
        }
        String s = gitUrl.trim();
        Matcher http = Pattern.compile("^(https?)://(?:[^@/]+@)?([^/]+)/").matcher(s);
        if (http.find()) {
            return http.group(1) + "://" + http.group(2);
        }
        Matcher ssh = Pattern.compile("^ssh://(?:[^@/]+@)?([^/:]+)").matcher(s);
        if (ssh.find()) {
            return "https://" + ssh.group(1);
        }
        Matcher scp = Pattern.compile("^(?:[^@/]+@)?([^/:]+):").matcher(s);
        if (scp.find()) {
            return "https://" + scp.group(1);
        }
        return null;
    }

    /** 仓库地址 → GitLab 上的「组/项目」路径 */
    public static String projectPath(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return null;
        }
        String s = gitUrl.trim().replaceAll("/+$", "");
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        if (s.contains("://")) {
            s = s.replaceFirst("^[a-z+]+://", "").replaceFirst("^[^@/]+@", "");
            int slash = s.indexOf('/');
            return slash < 0 ? null : s.substring(slash + 1);
        }
        int colon = s.indexOf(':');
        return colon < 0 ? null : s.substring(colon + 1);
    }

    /** GitLab detailed_merge_status → 人话 */
    public static String gitlabStatusText(String status) {
        if (status == null) {
            return "GitLab 没给出原因";
        }
        return switch (status) {
            case "not_approved" -> "合并请求还没审批，要先找人审批";
            case "conflict", "broken_status", "cannot_be_merged" -> "和 master 有冲突，要开发先解决";
            case "ci_must_pass", "ci_still_running" -> "流水线还没通过，项目要求流水线通过才能合";
            case "discussions_not_resolved" -> "合并请求里还有没解决的评论";
            case "draft_status" -> "合并请求是草稿状态";
            case "need_rebase" -> "项目要求先 rebase 到最新 master";
            case "not_open" -> "合并请求已关闭";
            case "blocked_status" -> "被别的合并请求挡住了";
            case "jira_association_missing" -> "项目要求标题或描述里带 JIRA 单号";
            case "requested_changes" -> "审批人要求修改";
            default -> "GitLab 状态：" + status;
        };
    }
}
