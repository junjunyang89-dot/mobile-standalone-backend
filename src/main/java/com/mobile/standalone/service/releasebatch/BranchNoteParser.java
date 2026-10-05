package com.mobile.standalone.service.releasebatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 JIRA 备注里认出「哪个项目、哪个分支」。
 * <p>
 * 开发写法五花八门，这里认这几种：
 * <pre>
 * app-swift-v3 : cj/app-9078                      项目: 分支
 * comment-service、app-admin：agent/x 按顺序发版    多个项目共用一个分支，后面的中文是备注
 * 1.app-admin, app-activity, app-video:           项目在一行，分支在下一行
 *   agent/2026-09-11/split-commentary-from-bgm
 * commerce-channel                                项目单独一行、分支单独一行（没有冒号）
 * zhaojinfeng/2026-09-10/doudian
 * [project]:app_esports_android                   方括号写法
 * [branch]:dev20260924
 * Android：dev20260924 / iOS 分支：dev20261015      客户端只写端名
 * </pre>
 * 认不出的行直接跳过，不猜。
 */
public final class BranchNoteParser {

    private BranchNoteParser() {
    }

    /** 客户端只写端名时用的占位项目名，由 {@link ProjectMatcher} 映射到真实仓库 */
    public static final String ANDROID = "Android";
    public static final String IOS = "iOS";

    public record BranchRef(String project, String branch, String note) {
    }

    private static final Pattern NUMBERING = Pattern.compile("^(?:\\d+\\s*[.、)）]\\s*|[*\\-•#]+\\s*)");
    private static final Pattern BRACKET_PROJECT = Pattern.compile("(?i)^\\[\\s*project\\s*]\\s*:\\s*(\\S+)");
    private static final Pattern BRACKET_BRANCH = Pattern.compile("(?i)^\\[\\s*branch\\s*]\\s*:\\s*(\\S+)");
    private static final Pattern CLIENT = Pattern.compile(
            "(?i)^(android|安卓|ios|苹果)\\s*(?:客户端)?\\s*(?:分支)?\\s*:\\s*(.*)$");
    /** 「项目[,、 ]项目 : 剩余」；冒号前只能是项目名列表 */
    private static final Pattern PROJECTS_COLON = Pattern.compile(
            "^([A-Za-z][\\w.\\-]*(?:\\s*[,，、/和&]\\s*[A-Za-z][\\w.\\-]*)*)\\s*:\\s*(.*)$");
    /** 单独一行的项目名；不要求带 - 或 _（commentservice 这种连写），靠「下一行是带 / 的分支」兜住误认 */
    private static final Pattern PROJECT_ONLY = Pattern.compile("^[A-Za-z][\\w.\\-]{2,}$");
    private static final Pattern PROJECT_TOKEN = Pattern.compile("[A-Za-z][\\w.\\-]*");
    /** 分支：带斜杠的路径，或 dev20260924 这种带日期的 */
    private static final Pattern BRANCH_PATH = Pattern.compile("^[A-Za-z0-9][\\w.\\-]*(?:/[\\w.\\-]+)+$");
    private static final Pattern BRANCH_FLAT = Pattern.compile(
            "(?i)^(?:dev|release|feature|feat|hotfix|fix|test|testing|rc)[\\w.\\-]*\\d{3,}[\\w.\\-]*$");

    private static final Set<String> NOT_PROJECT = Set.of(
            "http", "https", "qa", "sql", "url", "apollo", "spring", "redis", "mysql", "nacos", "key", "value",
            "prod", "test", "dev", "uat", "jdbc", "xxl-job", "note", "remark", "tips", "branch", "project",
            "mq", "kafka", "db", "ddl", "config", "nginx", "cdn", "api", "h5", "pc", "web", "id", "ps");
    /** 单独一行出现时更像分支 / 流程词而不是项目 */
    private static final Set<String> BRANCH_WORDS = Set.of(
            "master", "main", "develop", "release", "hotfix", "feature", "testing", "merge", "gitlab",
            "jira", "wiki", "done", "todo", "ok", "mr", "pr");

    public static List<BranchRef> parse(String text) {
        List<BranchRef> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] lines = clean(text).split("\n");
        Map<String, BranchRef> seen = new LinkedHashMap<>();
        boolean[] used = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            if (used[i]) {
                continue;
            }
            String line = lines[i];
            if (line.isEmpty() || line.contains("http://") || line.contains("https://")) {
                continue;
            }

            Matcher bp = BRACKET_PROJECT.matcher(line);
            if (bp.find()) {
                int j = nextNonEmpty(lines, i);
                if (j > 0 && j - i <= 3) {
                    Matcher bb = BRACKET_BRANCH.matcher(lines[j]);
                    if (bb.find() && isBranch(bb.group(1))) {
                        add(seen, bp.group(1), bb.group(1), null);
                        used[j] = true;
                    }
                }
                continue;
            }

            Matcher cm = CLIENT.matcher(line);
            if (cm.matches()) {
                String side = cm.group(1).toLowerCase(Locale.ROOT);
                String project = side.equals("android") || side.equals("安卓") ? ANDROID : IOS;
                String[] bn = branchAndNote(cm.group(2));
                if (bn == null) {
                    int j = nextNonEmpty(lines, i);
                    if (j > 0 && (bn = branchAndNote(lines[j])) != null) {
                        used[j] = true;
                    }
                }
                if (bn != null) {
                    add(seen, project, bn[0], bn[1]);
                }
                continue;
            }

            Matcher pc = PROJECTS_COLON.matcher(line);
            if (pc.matches()) {
                List<String> projects = projectsOf(pc.group(1));
                if (projects.isEmpty()) {
                    continue;
                }
                String[] bn = branchAndNote(pc.group(2));
                if (bn == null && pc.group(2).isBlank()) {
                    int j = nextNonEmpty(lines, i);
                    if (j > 0 && (bn = branchAndNote(lines[j])) != null) {
                        used[j] = true;
                    }
                }
                if (bn != null) {
                    for (String p : projects) {
                        add(seen, p, bn[0], bn[1]);
                    }
                }
                continue;
            }

            if (PROJECT_ONLY.matcher(line).matches() && !NOT_PROJECT.contains(line.toLowerCase(Locale.ROOT))
                    && !BRANCH_WORDS.contains(line.toLowerCase(Locale.ROOT)) && !BRANCH_FLAT.matcher(line).matches()) {
                int j = nextNonEmpty(lines, i);
                if (j > 0) {
                    String[] bn = branchAndNote(lines[j]);
                    if (bn != null && bn[0].contains("/")) {
                        add(seen, line, bn[0], bn[1]);
                        used[j] = true;
                    }
                }
            }
        }
        out.addAll(seen.values());
        return out;
    }

    /** 全角冒号、Jira 标记、编号、行首符号统一掉 */
    static String clean(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ').replace('\u3000', ' ')
                .replace('：', ':')
                .replaceAll("\\{(?:code|noformat|quote|color)[^}]*}", "\n")
                .replaceAll("\\[([^|\\]\\n]+)\\|[^\\]\\n]+]", "$1");
        StringBuilder sb = new StringBuilder();
        for (String raw : t.split("\n")) {
            String line = raw.trim().replaceAll("^\\*+|\\*+$", "").trim();
            line = NUMBERING.matcher(line).replaceFirst("").trim();
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static int nextNonEmpty(String[] lines, int i) {
        for (int j = i + 1; j < lines.length; j++) {
            if (!lines[j].isEmpty()) {
                return j;
            }
        }
        return -1;
    }

    private static List<String> projectsOf(String part) {
        List<String> out = new ArrayList<>();
        Matcher m = PROJECT_TOKEN.matcher(part);
        while (m.find()) {
            String p = m.group();
            // 带点的是配置项（spring.redis.host），不是仓库名
            if (p.length() >= 3 && !p.contains(".") && !NOT_PROJECT.contains(p.toLowerCase(Locale.ROOT))) {
                out.add(p);
            }
        }
        return out;
    }

    /** 「agent/2026-09-17/comment-score 按顺序发版」→ [分支, 备注]；不是分支返回 null */
    private static String[] branchAndNote(String rest) {
        String s = rest == null ? "" : rest.trim();
        if (s.isEmpty()) {
            return null;
        }
        int cut = 0;
        while (cut < s.length() && !Character.isWhitespace(s.charAt(cut)) && !isCjk(s.charAt(cut))
                && "，,;；（(".indexOf(s.charAt(cut)) < 0) {
            cut++;
        }
        String branch = s.substring(0, cut).replaceAll("[.。]+$", "");
        if (!isBranch(branch)) {
            return null;
        }
        String note = s.substring(cut).replaceAll("^[\\s，,;；]+", "").trim();
        return new String[]{branch, note.isEmpty() ? null : note};
    }

    static boolean isBranch(String s) {
        return s != null && (BRANCH_PATH.matcher(s).matches() || BRANCH_FLAT.matcher(s).matches());
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    private static void add(Map<String, BranchRef> seen, String project, String branch, String note) {
        String key = project.toLowerCase(Locale.ROOT) + "|" + branch;
        seen.putIfAbsent(key, new BranchRef(project, branch, note));
    }
}
