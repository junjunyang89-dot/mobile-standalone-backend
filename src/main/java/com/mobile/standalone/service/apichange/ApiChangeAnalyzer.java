package com.mobile.standalone.service.apichange;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 分支 vs 基线的接口变更分析：本次提测动了哪些接口、返回体多了/少了哪些字段、涉及哪些表。
 * <p>
 * 解决的是「测试环境数据都由后端返回，字段又多，每次都要抓包一个个看，还得判断是前端问题
 * 还是后端问题」。抓包看到的是<b>结果</b>，这里给的是<b>差异</b>——只看变了的那几个字段，
 * 而且类型变了会显式标出来（那种最容易让前端静默挂掉）。
 * <p>
 * 全程只读：只跑 {@code git diff} 与 {@code git show}，不碰工作区、不切分支、不 fetch。
 * 你正在那个仓库上开发也不会被影响。
 */
@Slf4j
@Service
public class ApiChangeAnalyzer {

    /**
     * {@link OtherChange#getTrivial()} 的一种取值：这个文件在基线最新版里已经一模一样。
     * <p>
     * 跟「只改了日志」不是一回事：那是比过了、确实没变；这是<b>没得比</b>——
     * 改动已经合进基线，拿合并后的基线去比当然一样，不能据此说「不用测」。
     */
    public static final String ALREADY_IN_BASE = "基线里已经是同一份代码（多半已合进基线），没有差异";

    private static final int GIT_TIMEOUT_SEC = 60;
    /** 单个仓库的变更文件上限：正常一次提测几十个文件，几千个多半是选错了基线 */
    private static final int MAX_CHANGED_FILES = 3000;

    // ==================== 对外结构 ====================

    @Data
    public static class Report {
        /** 落库后回填，便于前端关联历史 */
        private Long reportId;
        private String project;
        private String repoPath;
        private String baseRef;
        private String headRef;
        /** 变更的接口 */
        private List<EndpointChange> endpoints = new ArrayList<>();
        /** 涉及的表 */
        private List<TableChange> tables = new ArrayList<>();
        /** 改了但没能归到任何接口上的返回体（可能是被别处引用的公共 DTO） */
        private List<DtoChange> orphanDtos = new ArrayList<>();
        /** 改了、但整个待测代码里没有别处引用的返回体文件：改了也影响不到任何接口，不进 orphanDtos */
        private List<String> unusedDtos = new ArrayList<>();
        /**
         * 不是接口变更、但会影响测试的改动：定时任务改了会改变测试环境有什么数据，
         * 缓存 key 变了会出现新老数据混用。这些静默丢掉会让人以为「报告干净＝不用测」。
         */
        private List<OtherChange> otherChanges = new ArrayList<>();
        /**
         * 方法级的逻辑改动：哪个方法的判断、公式、配置默认值、定时规则、写库调用改了，以及每一处怎么测。
         * 文件级的「XxxServiceImpl 改了」回答不了「测什么」，测试还得自己读 diff。
         */
        private List<MethodLogicDiff.Change> logicChanges = new ArrayList<>();
        private int changedFiles;
        /**
         * 改过的文件在基线最新版里全都一模一样：改动已经合进基线，这份报告比不出任何东西。
         * 下游据此不能给「低风险、冒烟即可」，而要提示换合并前的基线重新分析。
         */
        private boolean alreadyInBase;
        /**
         * 深度分析（接口对比分析2 用）：连没改的 Controller 也顺着改了的 Service 找出来、方法级逻辑对比、
         * 识别「已合进基线」。接口对比分析页不开，结果保持原样。只在分析过程中用，不落库。
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private boolean deep;
        /** 改了但没连到接口的业务类 → 改了哪些方法、谁在调（深度分析才有）；「没连到接口」不等于没人用 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private Map<String, String> implCallers = new LinkedHashMap<>();
        /** 新读 / 改读的配置在各环境配置文件里配没配（深度分析才有） */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<ConfigPresence> configPresence = new ArrayList<>();
        /** 改动文件清单（ADDED / MODIFIED / DELETED），测试评估报告「改动范围」用 */
        private List<FileChange> files = new ArrayList<>();
        /** 说不清楚的地方都写在这，别让人以为分析是完备的 */
        private List<String> notes = new ArrayList<>();
        /** 从代码里能确证的安全与字段设计问题 */
        private List<ApiRiskScanner.Finding> findings = new ArrayList<>();
        /**
         * 哪些接口需要并发测试。单独一份而不是并进 findings：
         * 它回答的是「要不要并发测」，findings 回答的是「有没有安全/设计问题」，
         * 混在一起人就没法只看其中一类。
         */
        private List<ConcurrencyRiskScanner.Risk> concurrencyRisks = new ArrayList<>();
        /**
         * 这份报告同步进接口清单的结果，{@code {created, updated, deprecated}}。
         * <p>
         * 回给前端是为了让人当场看见「清单已经跟着更新了」——
         * 不显示的话，同步做没做过全靠猜，而猜错的方向是「以为同步了其实没有」。
         */
        private Map<String, Object> catalogSync;
        /**
         * 这份报告关联到了哪些需求单、挂了几个接口。
         * 分支名里带单号时自动产生，{@code {requirements:[APP-8928], linked:7}}
         */
        private Map<String, Object> requirementLink;
        /**
         * 必测清单：先测谁、为什么、这一条要注意什么。
         * 从 endpoints / tables / concurrency / otherChanges 收束，不另跑 git。
         */
        private MustTestGuide.Guide mustTest;
        /** 分析前拉代码的结果；拉失败时页面要提示「比的是旧代码」 */
        private Map<String, Object> repoSync;
        /** 表结构从哪读的、为什么没读到（没关联库、连不上） */
        private String schemaNote;
    }

    @Data
    public static class EndpointChange {
        private String changeType;   // ADDED / REMOVED / MODIFIED
        /**
         * 风险档：BREAKING（删字段/改类型/新增必填入参）> NEW（新接口）
         * > IMPL（签名没变但实现改了）> COMPATIBLE（只加了返回字段）。
         * 几十个变更平铺，人不知道从哪看起。
         */
        private String risk;
        private String httpMethod;
        private String path;
        private String summary;
        private String handler;
        private String payloadType;
        private String file;
        private List<FieldChange> fields = new ArrayList<>();
        /**
         * 返回体字段差异的<b>归并</b>——一个要人做的决定一行，而不是一条字段差异一行。
         * <p>
         * 前端默认渲染这个；{@link #fields} 留着给「展开看全部」。
         * 见 {@link DtoChangeRollup}。
         */
        private List<DtoChangeRollup.Group> fieldGroups = new ArrayList<>();
        /** 入参变化：新增必填入参会让老客户端直接 400，比返回体多个字段严重得多 */
        private List<ParamChange> params = new ArrayList<>();
        /** 请求体（@RequestBody）的字段变化 */
        private List<FieldChange> bodyFields = new ArrayList<>();
        /** 请求体字段差异的归并，同 {@link #fieldGroups} */
        private List<DtoChangeRollup.Group> bodyFieldGroups = new ArrayList<>();
        /** 签名没变但这些实现类改了——逻辑可能变了，仍需回归 */
        private List<String> changedImpls = new ArrayList<>();
        /** 这个接口用到的全部 Service（不只是改了的）：顺着它才能连到表和缓存 key */
        private List<String> usedServices = new ArrayList<>();
        /** 已有的接口用例条数；0 表示本次要补 */
        private int testCases;
        /**
         * 待测那一侧的<b>完整</b>返回体字段清单（含嵌套），抓包时逐字段对照用。
         * 变更的字段会在里面标出来，不用两个列表来回看。
         */
        private List<FieldTreeBuilder.FieldNode> responseFields = new ArrayList<>();
        /** 待测那一侧的完整请求体字段清单 */
        private List<FieldTreeBuilder.FieldNode> requestFields = new ArrayList<>();
        /** 这个接口（经由 Service）读写的表 */
        private List<String> tables = new ArrayList<>();
        /** 这个接口用到的缓存 key——数据跟预期不符时先看这里，省得猜是逻辑还是缓存 */
        private List<EndpointDependencyResolver.RedisKeyRef> redisKeys = new ArrayList<>();
        /** tables 里前多少个是本次变更涉及的；后面的是这个接口本来就在用的 */
        private int relatedTableCount;
    }

    @Data
    public static class ParamChange {
        private String changeType;   // ADDED / REMOVED / TYPE_CHANGED / REQUIRED_CHANGED
        private String source;       // QUERY / PATH / BODY / HEADER
        private String name;
        private String oldType;
        private String newType;
        private boolean required;
        /** 仅 TYPE_CHANGED 有值，见 {@link FieldChange#getWireEffect()} */
        private String wireEffect;
        private String wireNote;
    }

    @Data
    public static class OtherChange {
        /** SCHEDULE / CACHE / CONFIG / MAPPER / SERVICE / OTHER */
        private String kind;
        private String file;
        /**
         * 文件名（不含目录）。
         * <p>
         * 界面上这一列的宽度只够显示路径开头，于是每一行都是
         * {@code data-center-service/src/main/java/com/steamcn/datacenter/…} ——
         * 这一列唯一的信息（<b>是哪个文件</b>）正好被截掉了。
         * 前端用这个当主显示，完整路径收进 tooltip。
         */
        private String fileName;
        private String hint;
        /**
         * 没有实质改动时的说明（只挪了位置 / 只改了日志 / 只改了注释空行），否则为 null。
         * 这类改动照样列出来但排在最后、不进必测清单，免得人对着一行日志去回归。
         */
        private String trivial;
    }

    @Data
    public static class FileChange {
        private String path;
        /** ADDED / MODIFIED / DELETED */
        private String status;
    }

    @Data
    public static class DtoChange {
        private String type;
        private String file;
        private List<FieldChange> fields = new ArrayList<>();
        /** 归并后的分组，前端默认渲染这个；见 {@link DtoChangeRollup} */
        private List<DtoChangeRollup.Group> groups = new ArrayList<>();
    }

    @Data
    public static class FieldChange {
        private String changeType;   // ADDED / REMOVED / TYPE_CHANGED
        private String name;
        private String comment;
        private String oldType;
        private String newType;
        /**
         * 仅 TYPE_CHANGED 有值：这次类型变更在 <b>JSON 线上</b>到底变了什么。
         * SHAPE / NULLABILITY / WIDENING / SAME / UNKNOWN，见 {@link FieldTypeChange}。
         * <p>
         * 没有它的话，{@code int → Integer} 和 {@code JSONArray → JSONObject}
         * 在报告里长得一模一样，而前者 JSON 完全没变、后者客户端必崩。
         */
        private String wireEffect;
        /** 给人看的一句话，{@link FieldTypeChange#describe()} */
        private String wireNote;
    }

    @Data
    public static class TableChange {
        private String table;
        /** 哪些文件提到了它：mapper XML、.sql 脚本 */
        private List<String> files = new ArrayList<>();
        /** 本次 diff 里是否出现了 DDL（ALTER/CREATE），有的话基本是真动了表结构 */
        private boolean ddl;
        /** 具体加了哪列、什么类型——只报表名的话人还得自己翻 SQL */
        private List<DdlChange> ddlDetails = new ArrayList<>();
        /** 建表语句：优先从项目关联的测试库现读，读不到再用手动录入的 */
        private String ddlText;
        /** 建表语句的来源，如「测试库 app_admin」「手动录入」 */
        private String ddlSource;
        /** 从建表语句推导出来的测试注意事项 */
        private List<TableTestHints.Hint> testHints = new ArrayList<>();
        /** 人工补充的注意事项 */
        private String remark;
        /** 哪些接口读写这张表——表的注意事项要能落到具体接口上，否则还得人自己翻代码 */
        private List<String> usedByEndpoints = new ArrayList<>();
        /** 代码这次开始读写的新列（深度分析才有），见 {@link NewColumnScanner} */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        private List<NewColumn> newColumns = new ArrayList<>();
    }

    @Data
    public static class NewColumn {
        private String column;
        /** 从哪个文件看出来的 */
        private String file;
        /** 分支里的 .sql 有给它加列的语句 */
        private boolean ddlInBranch;
    }

    // ==================== 两种来源 ====================

    /**
     * 「某一侧的代码」从哪儿读。
     * <p>
     * 抽出来是因为拿不到内网时只能拿两个本地目录比，而 diff 逻辑跟走 git 完全一样——
     * 只有「怎么读到这个文件的那一版」不同。两条路各写一遍必然漂。
     */
    private interface SideSource {
        /** 相对路径 -> 内容；文件在这一侧不存在时返回 null */
        String read(String relPath);
    }

    // ==================== 目录对目录 ====================

    /**
     * 直接比两个本地目录，不需要 git。
     * <p>
     * 用于拿不到内网、只能把两个分支各拷一份到本地的情况。
     */
    public Report analyzeDirs(String project, String baseDir, String headDir) {
        return analyzeDirs(project, baseDir, headDir, false);
    }

    /** @param deep 见 {@link Report#isDeep()} */
    public Report analyzeDirs(String project, String baseDir, String headDir, boolean deep) {
        Path base = projectRoot(requireDir(baseDir, "基线目录"));
        Path head = projectRoot(requireDir(headDir, "待测目录"));

        Report report = new Report();
        report.setDeep(deep);
        report.setProject(project);
        report.setRepoPath(head.toString());
        report.setBaseRef("目录:" + base.getFileName());
        report.setHeadRef("目录:" + head.getFileName());

        List<String> changed = changedFilesBetweenDirs(base, head);
        report.setChangedFiles(changed.size());
        if (changed.isEmpty()) {
            report.getNotes().add("两个目录里的源码没有差异。确认一下是不是拷了同一份，"
                    + "或者改动不在 .java / mapper .xml / .sql 里。");
            return report;
        }
        if (!base.equals(requireDir(baseDir, "基线目录"))
                || !head.equals(requireDir(headDir, "待测目录"))) {
            report.getNotes().add("目录里套了一层工程根，已自动定位到 "
                    + base.getFileName() + " / " + head.getFileName() + "。");
        }
        java.util.Map<String, String> headIndex = classIndex(head);
        java.util.Map<String, String> baseIndex = classIndex(base);
        java.util.Map<String, String> xmlIndex = xmlIndex(head);
        fill(report, changed, rel -> readFile(base, rel), rel -> readFile(head, rel),
                rel -> diffAddedByLine(readFile(base, rel), readFile(head, rel)),
                new ClassLocator() {
                    @Override
                    public String source(String simple) {
                        String rel = headIndex.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : readFile(head, rel);
                    }

                    @Override
                    public String baseSource(String simple) {
                        String rel = baseIndex.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : readFile(base, rel);
                    }

                    @Override
                    public String sourceXml(String simple) {
                        String rel = xmlIndex.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : readFile(head, rel);
                    }

                    private List<String> headFiles;

                    @Override
                    public Boolean referenced(String simple, String selfFile) {
                        if (headFiles == null) {
                            headFiles = listFiles(head);
                        }
                        Pattern word = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b");
                        for (String rel : headFiles) {
                            if (isProductionRef(rel, selfFile, deep)) {
                                String src = readFile(head, rel);
                                if (src != null && word.matcher(src).find()) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }

                    @Override
                    public List<String> filesMentioningAny(Set<String> simpleNames) {
                        if (headFiles == null) {
                            headFiles = listFiles(head);
                        }
                        Pattern word = anyWord(simpleNames);
                        List<String> out = new ArrayList<>();
                        for (String rel : headFiles) {
                            if (!rel.endsWith(".java")) {
                                continue;
                            }
                            String src = readFile(head, rel);
                            if (src != null && word.matcher(src).find()) {
                                out.add(rel);
                            }
                        }
                        return out;
                    }

                    @Override
                    public List<String> configFiles() {
                        List<String> out = new ArrayList<>();
                        try (java.util.stream.Stream<Path> walk = Files.walk(head)) {
                            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                                String rel = head.relativize(p).toString().replace('\\', '/');
                                if (!rel.startsWith(".git/") && ConfigPresence.isConfigFile(rel)) {
                                    out.add(rel);
                                }
                            }
                        } catch (Exception e) {
                            return null;
                        }
                        return out;
                    }
                });
        return report;
    }

    private static Pattern anyWord(Set<String> words) {
        StringBuilder sb = new StringBuilder("\\b(?:");
        boolean first = true;
        for (String w : words) {
            if (!first) {
                sb.append('|');
            }
            sb.append(Pattern.quote(w));
            first = false;
        }
        return Pattern.compile(sb.append(")\\b").toString());
    }

    /** 目录里若没有 pom.xml，但恰好套着一层工程根，就下沉进去——省得人记这层嵌套 */
    static Path projectRoot(Path dir) {
        if (Files.isRegularFile(dir.resolve("pom.xml")) || Files.isRegularFile(dir.resolve("build.gradle"))) {
            return dir;
        }
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            List<Path> dirs = children.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .toList();
            for (Path d : dirs) {
                if (Files.isRegularFile(d.resolve("pom.xml")) || Files.isRegularFile(d.resolve("build.gradle"))) {
                    return d;
                }
            }
        } catch (Exception ignored) {
            // 列不出来就按原路径处理，大不了扫不到文件
        }
        return dir;
    }

    private static Path requireDir(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请填写" + what);
        }
        Path p = Path.of(raw.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(what + "不存在: " + p);
        }
        return p;
    }

    /** 只关心可能影响接口的文件；全量比对上万个文件既慢又全是噪音 */
    private static boolean interesting(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java")
                || lower.endsWith(".sql")
                || (lower.endsWith(".xml") && lower.contains("mapper"));
    }

    private List<String> changedFilesBetweenDirs(Path base, Path head) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(listFiles(head));
        all.addAll(listFiles(base));
        List<String> changed = new ArrayList<>();
        for (String rel : all) {
            String a = readFile(base, rel);
            String b = readFile(head, rel);
            if (!java.util.Objects.equals(a, b)) {
                changed.add(rel);
            }
        }
        return changed;
    }

    /** 简单类名 -> 相对路径。只走一遍目录名，不读内容，1000 个文件也是毫秒级 */
    private static java.util.Map<String, String> classIndex(Path root) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".java") || rel.contains("/target/") || rel.startsWith("target/")) {
                    continue;
                }
                String name = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - 5);
                out.putIfAbsent(FieldTreeBuilder.normalizeKey(name), rel);
            }
        } catch (Exception e) {
            log.warn("[api-change] 建类索引失败 {}: {}", root, e.getMessage());
        }
        return out;
    }

    /** mapper XML 索引：文件名（不含扩展名）-> 相对路径 */
    private static java.util.Map<String, String> xmlIndex(Path root) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".xml") || rel.contains("/target/")) {
                    continue;
                }
                String name = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - 4);
                out.putIfAbsent(FieldTreeBuilder.normalizeKey(name), rel);
            }
        } catch (Exception ignored) {
            // 索引不到就查不到表名，不该让整份报告失败
        }
        return out;
    }

    private static List<String> listFiles(Path root) {
        List<String> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // target/ 里是编译产物，比它没有意义还很慢
                if (rel.startsWith("target/") || rel.contains("/target/") || rel.startsWith(".git/")) {
                    continue;
                }
                if (interesting(rel)) {
                    out.add(rel);
                }
            }
        } catch (Exception e) {
            log.warn("[api-change] 遍历目录失败 {}: {}", root, e.getMessage());
        }
        return out;
    }

    private static String readFile(Path root, String rel) {
        Path p = root.resolve(rel);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return normalizeText(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 行尾与 BOM 归一。
     * <p>
     * 两个目录常来自不同机器：一份 LF、一份 CRLF，逐字节比就是「每个文件都变了」。
     * 实测 data-center vs APP-8928 是 1149 个文件全变、83 张表——报告直接失去意义。
     * 这不是代码差异，是换行符差异，必须在比对之前抹平。
     */
    static String normalizeText(String s) {
        if (s == null) {
            return null;
        }
        String v = s.replace("\r\n", "\n").replace("\r", "\n");
        return v.isEmpty() || v.charAt(0) != '\uFEFF' ? v : v.substring(1);
    }

    /** 没有 git 时的「新增行」：拿两版按行取差集，够用来认表名和 DDL */
    static String diffAddedByLine(String before, String after) {
        if (after == null) {
            return "";
        }
        Set<String> old = new LinkedHashSet<>();
        if (before != null) {
            for (String l : before.split("\n")) {
                old.add(l.trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : after.split("\n")) {
            if (!old.contains(l.trim())) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== 主流程 ====================

    public Report analyze(String project, String repoPath, String baseRef, String headRef) {
        return analyze(project, repoPath, baseRef, headRef, false);
    }

    /** @param deep 见 {@link Report#isDeep()} */
    public Report analyze(String project, String repoPath, String baseRef, String headRef, boolean deep) {
        Path repo = validateRepo(repoPath);
        String base = blankTo(baseRef, "master");
        String head = blankTo(headRef, currentBranch(repo));

        Report report = new Report();
        report.setDeep(deep);
        report.setProject(project);
        report.setRepoPath(repo.toString());
        report.setBaseRef(base);
        report.setHeadRef(head);

        requireRef(repo, base);
        requireRef(repo, head);

        List<String> changed = changedFiles(repo, base, head);
        report.setChangedFiles(changed.size());
        if (changed.isEmpty()) {
            report.getNotes().add("两个分支之间没有文件差异，确认一下基线和分支选对了没有。");
            return report;
        }
        if (changed.size() >= MAX_CHANGED_FILES) {
            report.getNotes().add("变更文件超过 " + MAX_CHANGED_FILES
                    + " 个，只分析了前 " + MAX_CHANGED_FILES + " 个。这么大的差异通常是基线选错了。");
            changed = changed.subList(0, MAX_CHANGED_FILES);
        }

        java.util.Map<String, String> headIdx = gitFileIndex(repo, head, ".java");
        java.util.Map<String, String> baseIdx = gitFileIndex(repo, base, ".java");
        java.util.Map<String, String> xmlIdx = gitFileIndex(repo, head, ".xml");
        fill(report, changed, rel -> show(repo, base, rel), rel -> show(repo, head, rel),
                rel -> addedLines(repo, base, head, rel),
                new ClassLocator() {
                    @Override
                    public String source(String simple) {
                        String rel = headIdx.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : show(repo, head, rel);
                    }

                    @Override
                    public String baseSource(String simple) {
                        String rel = baseIdx.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : show(repo, base, rel);
                    }

                    @Override
                    public String sourceXml(String simple) {
                        String rel = xmlIdx.get(FieldTreeBuilder.normalizeKey(simple));
                        return rel == null ? null : show(repo, head, rel);
                    }

                    @Override
                    public Boolean referenced(String simple, String selfFile) {
                        List<String> hits = gitGrepFiles(repo, head, simple);
                        return hits == null ? null : hits.stream().anyMatch(f -> isProductionRef(f, selfFile, deep));
                    }

                    @Override
                    public List<String> filesMentioningAny(Set<String> simpleNames) {
                        return gitGrepFilesAny(repo, head, simpleNames);
                    }

                    @Override
                    public List<String> configFiles() {
                        String raw = git(repo, "ls-tree", "-r", "--name-only", head);
                        if (raw == null) {
                            return null;
                        }
                        return java.util.Arrays.stream(raw.split("\n")).map(String::trim)
                                .filter(ConfigPresence::isConfigFile).toList();
                    }
                });
        if (report.getEndpoints().isEmpty() && report.getOrphanDtos().isEmpty()) {
            report.getNotes().add("没扫到接口或返回体变更。若确有接口改动，可能是写法不在已识别的范围内"
                    + "（当前识别 @RestController + @GetMapping/@PostMapping 等），把文件发我加规则。");
        }
        return report;
    }

    /**
     * 按简单类名找源码。
     * <p>
     * 完整字段清单要展开的返回体<b>未必在本次变更里</b>（接口没改、但你仍然要知道它有哪些字段），
     * 所以不能只在变更文件里找，得有一份全量索引。只记路径不读内容，用到哪个才读。
     */
    private interface ClassLocator {
        /** 待测侧的 Java 源码 */
        String source(String simpleName);

        /** 基线侧的 Java 源码；用来判断缓存 key 常量的值有没有变 */
        default String baseSource(String simpleName) {
            return null;
        }

        /** 同名的 mapper XML；表名只写在 SQL 里，Java 接口上通常没有 */
        default String sourceXml(String simpleName) {
            return null;
        }

        /**
         * 待测侧除 selfFile 和测试代码外，还有没有文件提到这个类名。
         * 查不了时返回 null，调用方按「有引用」处理——宁可多报一条，也不能把真改动藏掉。
         */
        default Boolean referenced(String simpleName, String selfFile) {
            return null;
        }

        /**
         * 待测侧提到这些类名里任意一个的 .java 文件（整词匹配）。查不了时返回 null。
         * 只是候选：是不是真注入了、哪个方法用到，调用方再解析确认。
         */
        default List<String> filesMentioningAny(Set<String> simpleNames) {
            return null;
        }

        /** 待测侧的 application* / bootstrap* 配置文件（相对路径）；查不了时返回 null */
        default List<String> configFiles() {
            return null;
        }
    }

    /** git 与目录两种来源共用的装配：解析返回体 -> 关联接口 -> 收表 */
    private void fill(Report report, List<String> changed,
                      SideSource base, SideSource head,
                      java.util.function.Function<String, String> addedLines) {
        fill(report, changed, base, head, addedLines, null);
    }

    private void fill(Report report, List<String> changed,
                      SideSource base, SideSource head,
                      java.util.function.Function<String, String> addedLines,
                      ClassLocator locator) {
        // 变更文件里出现的所有 Java 简单类名——接口靠它判断「签名没变但实现改了」
        Set<String> changedTypes = new LinkedHashSet<>();
        int sameAsBase = 0;
        for (String f : changed) {
            if (f.endsWith(".java")) {
                changedTypes.add(simpleName(f));
            }
            FileChange fc = new FileChange();
            fc.setPath(f);
            String baseText = base.read(f);
            String headText = head.read(f);
            fc.setStatus(baseText == null ? "ADDED" : headText == null ? "DELETED" : "MODIFIED");
            report.getFiles().add(fc);
            if (baseText != null && normalizeText(baseText).equals(normalizeText(headText))) {
                sameAsBase++;
            }
        }
        if (report.isDeep() && sameAsBase > 0 && sameAsBase == changed.size()) {
            report.setAlreadyInBase(true);
            report.getNotes().add("这个分支改过的 " + sameAsBase + " 个文件，基线最新版里已经一模一样——改动多半已经合进基线，"
                    + "所以比不出差异。这不代表不用测：如果还没测，把基线换成合并前的提交或上一次发版的 tag 再分析。");
        }
        // 返回体先解析好：接口要靠类型名反查字段
        Map<String, DtoChange> dtoByType = new LinkedHashMap<>();
        Set<String> usedDtoTypes = new LinkedHashSet<>();
        for (String file : changed) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String headSrc = head.read(file);
            String baseSrc = base.read(file);
            if (!JavaApiSourceParser.looksLikeDto(file, headSrc == null ? baseSrc : headSrc)) {
                continue;
            }
            List<FieldChange> fc = diffFields(
                    JavaApiSourceParser.parseFields(baseSrc),
                    JavaApiSourceParser.parseFields(headSrc));
            if (fc.isEmpty()) {
                continue;
            }
            DtoChange dc = new DtoChange();
            dc.setType(simpleName(file));
            dc.setFile(file);
            dc.setFields(fc);
            dc.setGroups(DtoChangeRollup.of(dc.getType(), fc));
            dtoByType.put(dc.getType(), dc);
        }

        for (String file : changed) {
            if (!file.endsWith(".java")) {
                continue;
            }
            String headSrc = head.read(file);
            String baseSrc = base.read(file);
            if (!JavaApiSourceParser.looksLikeController(headSrc == null ? "" : headSrc)
                    && !JavaApiSourceParser.looksLikeController(baseSrc == null ? "" : baseSrc)) {
                continue;
            }
            report.getEndpoints().addAll(
                    diffEndpoints(file, baseSrc, headSrc, dtoByType, usedDtoTypes, changedTypes));
        }
        if (locator != null && report.isDeep()) {
            attachUnchangedControllers(report, changed, base, head, locator, dtoByType, usedDtoTypes);
        }

        // 没挂到接口上的返回体单独列：可能是公共 DTO 被别处引用，也可能是我没能把它连回接口
        for (Map.Entry<String, DtoChange> e : dtoByType.entrySet()) {
            if (usedDtoTypes.contains(e.getKey())) {
                continue;
            }
            DtoChange d = e.getValue();
            // 被删掉的类没法再「被引用」，照常报
            if (locator != null && head.read(d.getFile()) != null
                    && Boolean.FALSE.equals(locator.referenced(d.getType(), d.getFile()))) {
                report.getUnusedDtos().add(d.getFile());
            } else {
                report.getOrphanDtos().add(d);
            }
        }

        report.setTables(collectTables(changed, base, head, addedLines));
        if (report.isDeep()) {
            attachNewColumns(report, changed, base, head);
        }
        if (locator != null) {
            attachFullFields(report, locator);
            // 必须在 setTables 之后：反向关联要写回 report.tables，
            // 早一步执行等于往一个还是空的列表里写，结果静默丢失
            attachDependencies(report, locator, base, head, changed);
            scanRisks(report, changed, head);
        }
        report.setOtherChanges(collectOtherChanges(changed, head, base, report));
        if (report.isDeep()) {
            report.setLogicChanges(collectLogicChanges(report, changed, base, head));
            List<String> cfgFiles = locator == null ? null : locator.configFiles();
            report.setConfigPresence(ConfigPresence.of(report.getLogicChanges(), cfgFiles, head::read));
            if (cfgFiles == null && !report.getConfigPresence().isEmpty()) {
                report.getNotes().add("没能列出待测分支的配置文件，新配置项在各环境有没有配上没检查");
            }
        }
        for (EndpointChange e : report.getEndpoints()) {
            e.setRisk(riskOf(e));
            // 归并放在这里而不是各个 setFields 旁边：上面有三处 setFields/setBodyFields，
            // 分开写迟早漏一处，而漏掉的表现是那个接口的报告突然退回「一条差异一行」
            e.setFieldGroups(DtoChangeRollup.of(e.getPayloadType(), e.getFields()));
            e.setBodyFieldGroups(DtoChangeRollup.of(e.getPayloadType(), e.getBodyFields()));
        }
        report.getEndpoints().sort(java.util.Comparator.comparingInt(e -> RISK_ORDER.indexOf(e.getRisk())));
        if (report.getEndpoints().isEmpty() && report.getOrphanDtos().isEmpty()) {
            report.getNotes().add("没扫到接口或返回体变更。若确有接口改动，可能是写法不在已识别的范围内"
                    + "（当前识别 @RestController + @GetMapping/@PostMapping 等），把文件发我加规则。");
        }
    }

    // ==================== 接口 diff ====================

    private List<EndpointChange> diffEndpoints(String file, String baseSrc, String headSrc,
                                               Map<String, DtoChange> dtoByType,
                                               Set<String> usedDtoTypes,
                                               Set<String> changedTypes) {
        Map<String, ApiEndpoint> before = byKey(baseSrc);
        Map<String, ApiEndpoint> after = byKey(headSrc);

        List<EndpointChange> out = new ArrayList<>();
        for (Map.Entry<String, ApiEndpoint> e : after.entrySet()) {
            ApiEndpoint now = e.getValue();
            ApiEndpoint was = before.get(e.getKey());
            DtoChange dto = now.payloadType() == null ? null : dtoByType.get(now.payloadType());
            if (dto != null) {
                usedDtoTypes.add(now.payloadType());
            }
            List<ParamChange> pc = was == null
                    ? List.of()
                    : diffParams(was.params(), now.params());
            DtoChange bodyDto = now.bodyType() == null ? null : dtoByType.get(now.bodyType());
            if (bodyDto != null) {
                usedDtoTypes.add(now.bodyType());
            }
            // 签名和返回体都没变，但它调的 Service 本次改了——逻辑可能变了，仍要回归。
            // 少了这一步，报告会漏掉整批「只改实现」的接口，而人会以为报告干净＝不用测。
            List<String> impls = new ArrayList<>();
            for (String svc : now.usedServices()) {
                if (changedTypes.contains(svc)) {
                    impls.add(svc);
                } else if (changedTypes.contains(svc + "Impl")) {
                    impls.add(svc + "Impl");
                }
            }
            // was == null 必须排在最前：新增的接口没有「之前」，
            // 而 sameSignature 会去读 was.returnType()。放在后面时，只有当前面几项
            // 恰好都为 false 才会走到它——也就是「新增了一个接口、而它的返回类型没变化」，
            // 那正是最常见的情形（新接口复用已有 DTO），一走到就 NPE，整份报告直接失败
            boolean touched = was == null || dto != null || bodyDto != null || !pc.isEmpty()
                    || !impls.isEmpty() || !sameSignature(was, now);
            if (was == null) {
                EndpointChange c = toChange("ADDED", now, file, dto);
                c.setParams(diffParams(List.of(), now.params()));
                if (bodyDto != null) c.setBodyFields(bodyDto.getFields());
                c.setChangedImpls(impls);
                c.setUsedServices(new ArrayList<>(now.usedServices()));
                out.add(c);
            } else if (touched) {
                EndpointChange c = toChange("MODIFIED", now, file, dto);
                c.setParams(pc);
                if (bodyDto != null) c.setBodyFields(bodyDto.getFields());
                c.setChangedImpls(impls);
                c.setUsedServices(new ArrayList<>(now.usedServices()));
                out.add(c);
            }
        }
        for (Map.Entry<String, ApiEndpoint> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                out.add(toChange("REMOVED", e.getValue(), file, null));
            }
        }
        return out;
    }

    /**
     * 只改了 Service、Controller 一行没动的接口。
     * <p>
     * 上面那轮只遍历变更文件里的 Controller，而「改业务逻辑不改签名」恰恰是最常见的改动形态——
     * 不反查的话这批接口在报告里一条都没有，只剩「其它改动」里一句连不回接口的 SERVICE，
     * 人会以为报告干净＝不用测。
     * <p>
     * 只追一层：Controller 直接注入、且在接口方法体里调用了的类。Service 再调 Service 的链路不追，
     * 追下去公共 Service 一改就会把半个仓库的接口拉进来，必测清单反而没人看。
     */
    private void attachUnchangedControllers(Report report, List<String> changed,
                                            SideSource base, SideSource head, ClassLocator locator,
                                            Map<String, DtoChange> dtoByType, Set<String> usedDtoTypes) {
        Set<String> implTypes = new LinkedHashSet<>();
        Set<String> words = new LinkedHashSet<>();
        // 类型名（实现类和接口两种写法）→ 改了的方法；值为 null 表示说不清，按整个类都改了算
        Map<String, Set<String>> touched = new LinkedHashMap<>();
        for (String f : changed) {
            if (!f.endsWith(".java") || JavaApiSourceParser.isTestPath(f)) {
                continue;
            }
            String now = head.read(f);
            // 删掉的类要是还被注入，Controller 自己也必然改了，上一轮已经报过
            if (now == null || JavaApiSourceParser.looksLikeController(now)) {
                continue;
            }
            // 只改注释/日志/挪位置的不算：否则补一次注释就拉进一串「实现改了」的接口
            if (trivialReason(base.read(f), now) != null) {
                continue;
            }
            String type = simpleName(f);
            implTypes.add(type);
            words.add(type);
            Set<String> methods = MethodLogicDiff.touchedMethods(base.read(f), now);
            touched.put(type, methods);
            // 改的是 XxxServiceImpl，Controller 里注入的是接口 XxxService
            if (type.endsWith("Impl") && type.length() > 4) {
                words.add(type.substring(0, type.length() - 4));
                touched.putIfAbsent(type.substring(0, type.length() - 4), methods);
            }
        }
        if (words.isEmpty()) {
            return;
        }
        List<String> candidates = locator.filesMentioningAny(words);
        if (candidates == null) {
            report.getNotes().add("没能反查「只改了 Service、Controller 没动」的接口（全仓搜索失败），"
                    + "这类接口不在下面的清单里，请对照「其它改动」里的业务实现手动补。");
            return;
        }
        Set<String> changedFiles = new LinkedHashSet<>(changed);
        int found = 0;
        int notCalling = 0;
        Set<String> linked = new LinkedHashSet<>();
        for (String file : candidates) {
            if (changedFiles.contains(file) || JavaApiSourceParser.isTestPath(file)) {
                continue;
            }
            String src = head.read(file);
            if (!JavaApiSourceParser.looksLikeController(src)) {
                continue;
            }
            Map<String, String> injected = JavaApiSourceParser.injectedFields(src);
            Map<String, MethodLogicDiff.Member> members = MethodLogicDiff.members(src);
            for (ApiEndpoint ep : JavaApiSourceParser.parseEndpoints(src)) {
                List<String> impls = new ArrayList<>();
                String body = handlerBody(members, ep.handler());
                boolean skipped = false;
                for (String svc : ep.usedServices()) {
                    String hit = implTypes.contains(svc) ? svc : implTypes.contains(svc + "Impl") ? svc + "Impl" : null;
                    if (hit == null) {
                        continue;
                    }
                    if (!callsTouched(body, injected, svc, touched.get(svc))) {
                        skipped = true;
                        continue;
                    }
                    impls.add(hit);
                    linked.add(hit);
                }
                if (impls.isEmpty()) {
                    if (skipped) {
                        notCalling++;
                    }
                    continue;
                }
                DtoChange dto = ep.payloadType() == null ? null : dtoByType.get(ep.payloadType());
                if (dto != null) {
                    usedDtoTypes.add(ep.payloadType());
                }
                DtoChange bodyDto = ep.bodyType() == null ? null : dtoByType.get(ep.bodyType());
                if (bodyDto != null) {
                    usedDtoTypes.add(ep.bodyType());
                }
                EndpointChange c = toChange("MODIFIED", ep, file, dto);
                if (bodyDto != null) {
                    c.setBodyFields(bodyDto.getFields());
                }
                c.setChangedImpls(impls);
                c.setUsedServices(new ArrayList<>(ep.usedServices()));
                report.getEndpoints().add(c);
                found++;
            }
        }
        if (found > 0) {
            report.getNotes().add("有 " + found + " 个接口的 Controller 没改、但它调用的 Service 改了，已按「实现改了」列入。"
                    + "只追了 Controller 直接注入的那一层；Service 再调 Service 的更深链路没追，"
                    + "「其它改动」里剩下的业务实现仍需扫一眼。");
        }
        if (notCalling > 0) {
            report.getNotes().add("有 " + notCalling + " 个接口注入了改动的 Service，但接口里调的是它没改的方法，没列入必测。");
        }
        for (String type : implTypes) {
            Set<String> methods = touched.get(type);
            if (linked.contains(type) || methods == null || methods.isEmpty()) {
                continue;
            }
            Set<String> callers = new LinkedHashSet<>();
            Pattern call = Pattern.compile("\\.\\s*(?:" + String.join("|", methods.stream().map(Pattern::quote).toList()) + ")\\s*\\(");
            for (String file : candidates) {
                if (JavaApiSourceParser.isTestPath(file) || simpleName(file).equals(type)
                        || simpleName(file).equals(type.replaceFirst("Impl$", ""))) {
                    continue;
                }
                String src = head.read(file);
                if (src != null && call.matcher(src).find()) {
                    callers.add(simpleName(file));
                }
            }
            report.getImplCallers().put(type, "改的是 " + String.join("、", methods.stream().map(m -> m + "()").toList())
                    + (callers.isEmpty() ? "，没找到谁在调" : "，调用方：" + String.join("、", callers)));
        }
    }

    /** 接口方法体；同名重载的拼在一起 */
    private static String handlerBody(Map<String, MethodLogicDiff.Member> members, String handler) {
        if (handler == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MethodLogicDiff.Member m : members.values()) {
            if ("METHOD".equals(m.kind()) && handler.equals(m.name()) && m.body() != null) {
                sb.append(m.body()).append('\n');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 接口里是不是通过注入的 svc 字段调到了改了的方法；方法说不清或方法体找不到时按「调到了」 */
    private static boolean callsTouched(String body, Map<String, String> injected, String svc, Set<String> methods) {
        if (methods == null || body == null) {
            return true;
        }
        for (Map.Entry<String, String> e : injected.entrySet()) {
            if (!svc.equals(e.getValue())) {
                continue;
            }
            Set<String> calls = methods.stream().map(m -> e.getKey() + "." + m).collect(java.util.stream.Collectors.toSet());
            if (MethodLogicDiff.mentionsAny(body.replaceAll("\\s+", ""), calls, true)) {
                return true;
            }
        }
        return false;
    }

    /** 任一侧为空视为「不同」——新增或删除本来就是变化，而不是崩溃的理由 */
    private static boolean sameSignature(ApiEndpoint a, ApiEndpoint b) {
        if (a == null || b == null) {
            return false;
        }
        return java.util.Objects.equals(a.returnType(), b.returnType());
    }

    private static EndpointChange toChange(String type, ApiEndpoint ep, String file, DtoChange dto) {
        EndpointChange c = new EndpointChange();
        c.setChangeType(type);
        c.setHttpMethod(ep.httpMethod());
        c.setPath(ep.path());
        c.setSummary(ep.summary());
        c.setHandler(ep.handler());
        c.setPayloadType(ep.payloadType());
        c.setFile(file);
        if (dto != null) {
            c.setFields(dto.getFields());
        }
        return c;
    }

    private static Map<String, ApiEndpoint> byKey(String source) {
        Map<String, ApiEndpoint> m = new LinkedHashMap<>();
        if (source == null) {
            return m;
        }
        for (ApiEndpoint e : JavaApiSourceParser.parseEndpoints(source)) {
            m.putIfAbsent(e.key(), e);
        }
        return m;
    }

    /**
     * 给每个接口挂上待测侧的完整字段清单。
     * <p>
     * 只展开待测那一侧：测的是新版本，基线侧的字段清单对测试没意义。
     */
    private void attachFullFields(Report report, ClassLocator locator) {
        for (EndpointChange e : report.getEndpoints()) {
            java.util.Map<String, String> changedResp = new LinkedHashMap<>();
            for (FieldChange f : e.getFields()) {
                changedResp.put(f.getName(), f.getChangeType());
            }
            if (e.getPayloadType() != null) {
                e.setResponseFields(FieldTreeBuilder.build(
                        e.getPayloadType(), locator::source, changedResp));
            }
            java.util.Map<String, String> changedReq = new LinkedHashMap<>();
            for (FieldChange f : e.getBodyFields()) {
                changedReq.put(f.getName(), f.getChangeType());
            }
            String bodyType = bodyTypeOf(e);
            if (bodyType != null) {
                e.setRequestFields(FieldTreeBuilder.build(bodyType, locator::source, changedReq));
            }
        }
    }

    /** @RequestBody 的类型；入参列表里 source=BODY 的那个 */
    private static String bodyTypeOf(EndpointChange e) {
        for (ParamChange p : e.getParams()) {
            if ("BODY".equals(p.getSource())) {
                return p.getNewType() != null ? p.getNewType() : p.getOldType();
            }
        }
        return null;
    }

    /**
     * 顺着 Controller → Service → (Mapper | RedisConstants) 把接口连到表和缓存 key，
     * 再反向标出每张表被哪些接口用到。
     */
    private void attachDependencies(Report report, ClassLocator locator,
                                    SideSource base, SideSource head, List<String> changed) {
        Set<String> changedTables = new LinkedHashSet<>();
        report.getTables().forEach(t -> changedTables.add(t.getTable()));
        Set<String> changedTypes = new LinkedHashSet<>();
        for (String f : changed) {
            if (f.endsWith(".java")) {
                changedTypes.add(simpleName(f));
            }
        }
        // Mapper -> 表：XML 里的 SQL 才有表名，Java 接口里通常没有
        java.util.Map<String, Set<String>> mapperTables = new LinkedHashMap<>();
        java.util.function.Function<String, Set<String>> tablesOf = mapperName -> {
            Set<String> cached = mapperTables.get(mapperName);
            if (cached != null) {
                return cached;
            }
            Set<String> found = new LinkedHashSet<>();
            String xml = locator.source(mapperName + ".xml");
            if (xml == null) {
                xml = locator.sourceXml(mapperName);
            }
            if (xml != null) {
                found.addAll(JavaApiSourceParser.parseTables(xml));
            }
            String java = locator.source(mapperName);
            if (java != null) {
                found.addAll(JavaApiSourceParser.parseTables(java));
            }
            mapperTables.put(mapperName, found);
            return found;
        };

        java.util.Map<String, Set<String>> tableUsers = new LinkedHashMap<>();
        for (EndpointChange e : report.getEndpoints()) {
            Set<String> services = new LinkedHashSet<>(e.getChangedImpls());
            services.addAll(e.getUsedServices());
            EndpointDependencyResolver.Deps deps = EndpointDependencyResolver.resolve(
                    services, locator::source, locator::baseSource, tablesOf,
                    changedTables, changedTypes);
            e.setTables(deps.getTables());
            e.setRelatedTableCount(deps.getRelatedTableCount());
            e.setRedisKeys(deps.getRedisKeys());
            for (String t : deps.getTables()) {
                tableUsers.computeIfAbsent(t, k -> new LinkedHashSet<>())
                        .add(e.getHttpMethod() + " " + e.getPath());
            }
        }
        for (TableChange t : report.getTables()) {
            Set<String> users = tableUsers.get(t.getTable());
            if (users != null) {
                t.setUsedByEndpoints(new ArrayList<>(users));
            }
        }
    }

    /** 安全与字段设计：只报能从代码确证的，宁可漏也不错报 */
    private void scanRisks(Report report, List<String> changed, SideSource head) {
        List<ApiRiskScanner.Finding> out = new ArrayList<>();
        for (EndpointChange e : report.getEndpoints()) {
            ApiEndpoint ep = new ApiEndpoint(e.getHttpMethod(), e.getPath(), e.getHandler(),
                    null, e.getSummary(), e.getPayloadType(), toParams(e), Set.of());
            out.addAll(ApiRiskScanner.scanSecurity(ep, e.getResponseFields()));
            out.addAll(ApiRiskScanner.scanFieldDesign(
                    e.getHttpMethod() + " " + e.getPath(), e.getResponseFields()));
        }
        for (String file : changed) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xml") && lower.contains("mapper")) {
                out.addAll(ApiRiskScanner.scanSqlInjection(file, head.read(file)));
            }
        }
        // 安全在前、风险在前：这份清单人只会从上往下扫，顺序就是优先级
        out.sort((a, b) -> {
            int k = Integer.compare(rank(a.kind()), rank(b.kind()));
            return k != 0 ? k : Integer.compare(rank(a.level()), rank(b.level()));
        });
        report.setFindings(out);
    }

    private static int rank(String v) {
        return switch (v) {
            case "SECURITY", "RISK" -> 0;
            default -> 1;
        };
    }

    /** 风险扫描只关心入参的名字与来源，把变更结构还原成入参列表即可 */
    private static List<ApiParam> toParams(EndpointChange e) {
        List<ApiParam> out = new ArrayList<>();
        for (ParamChange p : e.getParams()) {
            out.add(new ApiParam(p.getSource(), p.getName(),
                    p.getNewType() != null ? p.getNewType() : p.getOldType(), p.isRequired()));
        }
        for (FieldTreeBuilder.FieldNode f : e.getRequestFields()) {
            out.add(new ApiParam("BODY", f.getName(), f.getType(), false));
        }
        return out;
    }

    /** 风险从高到低；报告按这个排序，几十条平铺时人才知道从哪看起 */
    private static final List<String> RISK_ORDER = List.of("BREAKING", "NEW", "IMPL", "COMPATIBLE");

    /**
     * 风险分档。
     * <p>
     * 删字段、改类型、<b>新增必填入参</b>归为 BREAKING——最后一类尤其容易被忽略：
     * 老客户端不带这个参数就是直接 400，比返回体多个字段严重得多。
     */
    /**
     * 这条字段变更要不要进「可能不兼容」。
     *
     * <p>删字段一定要;<b>类型变更要看它在 JSON 线上到底变了什么</b>——
     * 原来是「TYPE_CHANGED 一律不兼容」,于是一个 DTO 整体换包装类型
     * 会一次产出二三十条,实测一次提测 79 条里真要问开发的只有一条,
     * 而噪音的代价是整份报告失去可信度。
     *
     * <p>{@link FieldTypeChange#UNKNOWN} 仍然算不兼容:这次改动是降噪,
     * 它最危险的失败方式就是把真问题一起降没。
     */
    private static boolean fieldBreaking(FieldChange f) {
        if ("REMOVED".equals(f.getChangeType())) {
            return true;
        }
        if (!"TYPE_CHANGED".equals(f.getChangeType())) {
            return false;
        }
        return FieldTypeChange.classify(f.getOldType(), f.getNewType()).breaking();
    }

    static String riskOf(EndpointChange e) {
        // 新接口的入参当然「全是新增必填」，但没有老客户端在调它——判成不兼容是纯噪音，
        // 会把真正的破坏性变更淹掉。这是拿 APP-8928 跑出来才看见的：6 个新接口全被标红。
        if ("ADDED".equals(e.getChangeType())) {
            return "NEW";
        }
        boolean breaking = e.getFields().stream().anyMatch(ApiChangeAnalyzer::fieldBreaking);
        breaking = breaking || e.getBodyFields().stream().anyMatch(ApiChangeAnalyzer::fieldBreaking);
        breaking = breaking || e.getParams().stream().anyMatch(p ->
                ("ADDED".equals(p.getChangeType()) && p.isRequired())
                        || "REMOVED".equals(p.getChangeType())
                        || ("TYPE_CHANGED".equals(p.getChangeType())
                            && FieldTypeChange.classify(p.getOldType(), p.getNewType()).breaking()));
        if (breaking || "REMOVED".equals(e.getChangeType())) {
            return "BREAKING";
        }
        // 返回体没变，只是实现改了：不算不兼容，但必须回归
        if (e.getFields().isEmpty() && e.getBodyFields().isEmpty() && e.getParams().isEmpty()
                && !e.getChangedImpls().isEmpty()) {
            return "IMPL";
        }
        return "COMPATIBLE";
    }

    /** 入参 diff。必填与否也算一种变化：把可选改成必填同样会打挂老客户端 */
    static List<ParamChange> diffParams(List<ApiParam> before, List<ApiParam> after) {
        Map<String, ApiParam> b = new LinkedHashMap<>();
        before.forEach(x -> b.put(x.key(), x));
        Map<String, ApiParam> a = new LinkedHashMap<>();
        after.forEach(x -> a.put(x.key(), x));

        List<ParamChange> out = new ArrayList<>();
        for (ApiParam p : after) {
            ApiParam old = b.get(p.key());
            if (old == null) {
                out.add(param("ADDED", p, null, p.type(), p.required()));
            } else if (!java.util.Objects.equals(old.type(), p.type())) {
                out.add(param("TYPE_CHANGED", p, old.type(), p.type(), p.required()));
            } else if (old.required() != p.required()) {
                out.add(param("REQUIRED_CHANGED", p, old.type(), p.type(), p.required()));
            }
        }
        for (ApiParam p : before) {
            if (!a.containsKey(p.key())) {
                out.add(param("REMOVED", p, p.type(), null, p.required()));
            }
        }
        return out;
    }

    private static ParamChange param(String type, ApiParam p, String oldType, String newType, boolean required) {
        ParamChange c = new ParamChange();
        c.setChangeType(type);
        c.setSource(p.source());
        c.setName(p.name());
        c.setOldType(oldType);
        c.setNewType(newType);
        c.setRequired(required);
        if ("TYPE_CHANGED".equals(type)) {
            FieldTypeChange w = FieldTypeChange.classify(oldType, newType);
            c.setWireEffect(w.name());
            c.setWireNote(w.describe());
        }
        return c;
    }

    /**
     * 不是接口变更、但会影响测试的改动。
     * <p>
     * 定时任务改了会改变测试环境里有什么数据（正是「测试环境数据都由后端返回」的来源）；
     * 缓存 key 变了会出现新老数据混用。这些默默丢掉，人会以为报告干净就不用测。
     */
    private List<OtherChange> collectOtherChanges(List<String> changed, SideSource head,
                                                  SideSource base, Report report) {
        Set<String> shown = new LinkedHashSet<>();
        for (EndpointChange e : report.getEndpoints()) {
            shown.add(e.getFile());
            for (String impl : e.getChangedImpls()) {
                // 注入的是接口 XxxService，改的文件常是 XxxServiceImpl，两个名字都要记上，
                // 否则同一个实现会既出现在「实现改动」又出现在「其它改动」里
                shown.add(impl);
                shown.add(impl + "Impl");
                if (impl.endsWith("Impl")) {
                    shown.add(impl.substring(0, impl.length() - 4));
                }
            }
        }
        for (DtoChange d : report.getOrphanDtos()) {
            shown.add(d.getFile());
        }
        shown.addAll(report.getUnusedDtos());
        List<OtherChange> out = new ArrayList<>();
        for (String file : changed) {
            // 单测改了不影响线上行为
            if (shown.contains(file) || isTest(file, report.isDeep())) {
                continue;
            }
            String src = head.read(file);
            if (src == null) {
                src = base.read(file);
            }
            String kind = JavaApiSourceParser.classifyFile(file, src);
            String hint = OTHER_HINTS.get(kind);
            if (hint == null) {
                continue;
            }
            // 已经作为某个接口的「实现改了」报过的，不再重复列
            if (shown.contains(simpleName(file))) {
                continue;
            }
            OtherChange c = new OtherChange();
            c.setKind(kind);
            c.setFile(file);
            c.setFileName(file == null ? null
                    : file.substring(file.lastIndexOf('/') + 1));
            String callers = report.getImplCallers().get(simpleName(file));
            c.setHint(callers != null && kind.startsWith("SERVICE")
                    ? "业务实现改了，没有接口直接调到改了的方法：" + callers + "——从调用方入口测" : hint);
            if (file.endsWith(".java")) {
                c.setTrivial(trivialReason(base.read(file), src));
            }
            out.add(c);
        }
        // 没实质改动的排最后
        out.sort(java.util.Comparator.comparing(c -> c.getTrivial() != null));
        return out;
    }

    /** 一份报告里逻辑改动的上限：上百条时人只会全部跳过 */
    private static final int MAX_LOGIC_CHANGES = 300;

    /**
     * 逐个改了的 Java 文件做方法级对比，并把每条改动挂到经过它的接口上。
     * 返回体 / 单测 / 只改了注释日志的文件不看：前两个有专门的分析，后一个没有逻辑变化。
     */
    private List<MethodLogicDiff.Change> collectLogicChanges(Report report, List<String> changed,
                                                             SideSource base, SideSource head) {
        List<MethodLogicDiff.Change> out = new ArrayList<>();
        for (String file : changed) {
            if (!file.endsWith(".java") || JavaApiSourceParser.isTestPath(file)) {
                continue;
            }
            String now = head.read(file);
            String was = base.read(file);
            if (now == null || JavaApiSourceParser.looksLikeDto(file, now) || trivialReason(was, now) != null) {
                continue;
            }
            List<MethodLogicDiff.Change> found = MethodLogicDiff.diff(file, was, now);
            boolean controller = JavaApiSourceParser.looksLikeController(now);
            for (MethodLogicDiff.Change c : found) {
                for (EndpointChange e : report.getEndpoints()) {
                    boolean hit = controller
                            ? file.equals(e.getFile()) && (c.getMember().equals(e.getHandler()) || isField(c))
                            : e.getChangedImpls().contains(c.getClassName());
                    if (hit) {
                        c.getEndpoints().add(e.getHttpMethod() + " " + e.getPath());
                    }
                }
            }
            out.addAll(found);
            if (out.size() >= MAX_LOGIC_CHANGES) {
                report.getNotes().add("逻辑改动超过 " + MAX_LOGIC_CHANGES + " 条，只列了前 " + MAX_LOGIC_CHANGES
                        + " 条。改动这么大通常是基线选错了，或者这一单本身就该拆开测。");
                return new ArrayList<>(out.subList(0, MAX_LOGIC_CHANGES));
            }
        }
        return out;
    }

    private static boolean isField(MethodLogicDiff.Change c) {
        return "CONFIG".equals(c.getKind()) || "CONSTANT".equals(c.getKind());
    }

    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(?:log|logger|LOG|LOGGER)\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\("
                    + "(?:[^;\"']|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')*\\)\\s*;"
                    + "|\\bSystem\\.(?:out|err)\\.print(?:ln)?\\s*\\((?:[^;\"]|\"(?:[^\"\\\\]|\\\\.)*\")*\\)\\s*;");

    /**
     * 两版 Java 源码之间如果没有实质改动，返回一句说明；有实质改动返回 null。
     * <p>
     * 认三种：只改了注释 / 空行 / 缩进 / import；整块方法挪了位置；只增删改了日志语句。
     * 拿不准的一律算实质改动——语句换了顺序就是逻辑变了，不能当成「挪位置」。
     */
    static String trivialReason(String before, String after) {
        if (before == null || after == null) {
            return null;
        }
        // 变更清单按分叉点算，比对读的是基线最新版：分支上的改动已经合进基线时两边一字不差
        if (normalizeText(before).equals(normalizeText(after))) {
            return ALREADY_IN_BASE;
        }
        List<String> b = codeLines(before);
        List<String> a = codeLines(after);
        if (b.equals(a)) {
            return "只改了注释、空行或 import，逻辑没变";
        }
        if (membersReordered(b, a)) {
            return "只是挪了方法位置，内容没变";
        }
        List<String> bn = codeLines(LOG_CALL.matcher(before).replaceAll(" "));
        List<String> an = codeLines(LOG_CALL.matcher(after).replaceAll(" "));
        if (bn.equals(an) || membersReordered(bn, an)) {
            return "只增删或改了日志，逻辑没变";
        }
        return null;
    }

    /**
     * 按类成员（方法、字段、内部类，各自连同注解）切开比：类头一样、成员集合一样，只是顺序不同。
     * 只能整块挪——方法体里语句换了顺序，那个成员本身就不一样了。
     */
    static boolean membersReordered(List<String> b, List<String> a) {
        List<List<String>> sb = splitMembers(b);
        List<List<String>> sa = splitMembers(a);
        if (sb == null || sa == null || !sb.get(0).equals(sa.get(0))) {
            return false;
        }
        List<String> mb = new ArrayList<>(sb.get(1));
        List<String> ma = new ArrayList<>(sa.get(1));
        java.util.Collections.sort(mb);
        java.util.Collections.sort(ma);
        return mb.equals(ma);
    }

    /** [类头/类尾这些顶层行, 成员文本]；括号对不上时返回 null */
    private static List<List<String>> splitMembers(List<String> lines) {
        List<String> frame = new ArrayList<>();
        List<String> members = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (String line : lines) {
            int before = depth;
            depth += braceDelta(line);
            if (depth < 0) {
                return null;
            }
            if (before == 0) {
                frame.add(line);
                continue;
            }
            if (before == 1 && depth == 0) {
                if (cur.length() > 0) {
                    members.add(cur.toString());
                    cur.setLength(0);
                }
                frame.add(line);
                continue;
            }
            cur.append(line).append('\n');
            if (depth == 1 && (line.endsWith(";") || line.endsWith("}"))) {
                members.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (depth != 0) {
            return null;
        }
        if (cur.length() > 0) {
            members.add(cur.toString());
        }
        return List.of(frame, members);
    }

    /** 一行里 { 比 } 多几个；字符串和字符字面量里的不算 */
    private static int braceDelta(String line) {
        int d = 0;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    private static List<String> codeLines(String src) {
        List<String> out = new ArrayList<>();
        for (String line : JavaApiSourceParser.stripCommentsKeepStrings(normalizeText(src)).split("\n")) {
            String t = line.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty() && !t.startsWith("import ")) {
                out.add(t);
            }
        }
        return out;
    }

    private static final Map<String, String> OTHER_HINTS = Map.of(
            "SCHEDULE", "定时任务改了——它决定测试环境里会生成什么数据，改完可能要等下一轮或手动触发",
            "CACHE", "缓存 key / 缓存逻辑改了——注意新老缓存混用导致的「改了没生效」",
            "CONFIG", "配置改了——确认测试环境的配置是否同步",
            "MAPPER", "数据访问层改了——查询条件或字段映射可能变了",
            "SERVICE", "业务实现改了，但没能连回具体接口——可能被别处调用，建议扫一眼",
            "SERVICE_SCHEDULED", "业务实现改了，且这个类里有定时任务方法——"
                    + "既可能影响接口返回，也可能改变测试环境的数据生成");

    // ==================== 字段 diff ====================

    /**
     * 三种变化都要报，其中<b>类型变了</b>最要紧：字段名还在，抓包看着一切正常，
     * 但 int 变 String 会让前端静默挂掉，这种问题最难定位到后端。
     */
    static List<FieldChange> diffFields(List<DtoField> before, List<DtoField> after) {
        Map<String, DtoField> b = new LinkedHashMap<>();
        before.forEach(f -> b.put(f.name(), f));
        Map<String, DtoField> a = new LinkedHashMap<>();
        after.forEach(f -> a.put(f.name(), f));

        List<FieldChange> out = new ArrayList<>();
        for (DtoField f : after) {
            DtoField old = b.get(f.name());
            if (old == null) {
                out.add(field("ADDED", f.name(), f.comment(), null, f.type()));
            } else if (!java.util.Objects.equals(old.type(), f.type())) {
                out.add(field("TYPE_CHANGED", f.name(), f.comment(), old.type(), f.type()));
            }
        }
        for (DtoField f : before) {
            if (!a.containsKey(f.name())) {
                out.add(field("REMOVED", f.name(), f.comment(), f.type(), null));
            }
        }
        return out;
    }

    private static FieldChange field(String type, String name, String comment, String oldType, String newType) {
        FieldChange c = new FieldChange();
        c.setChangeType(type);
        c.setName(name);
        c.setComment(comment);
        c.setOldType(oldType);
        c.setNewType(newType);
        // 在工厂里算一次：散在各处的话，迟早有一条路径忘了算，
        // 而忘了算的表现是 wireEffect 为空 → 按 UNKNOWN 处理 → 又变回「全是不兼容」
        if ("TYPE_CHANGED".equals(type)) {
            FieldTypeChange w = FieldTypeChange.classify(oldType, newType);
            c.setWireEffect(w.name());
            c.setWireNote(w.describe());
        }
        return c;
    }

    // ==================== 表 ====================

    private List<TableChange> collectTables(List<String> changed, SideSource base, SideSource head,
                                            java.util.function.Function<String, String> addedLines) {
        Map<String, TableChange> out = new LinkedHashMap<>();
        for (String file : changed) {
            String lower = file.toLowerCase(Locale.ROOT);
            boolean isSql = lower.endsWith(".sql");
            boolean isMapper = lower.endsWith(".xml") && lower.contains("mapper");
            if (!isSql && !isMapper) {
                continue;
            }
            // 只看新增的部分：整份 mapper 里的表几十个，全报出来等于没报
            String added = addedLines.apply(file);
            Set<String> tables = JavaApiSourceParser.parseTables(added);
            if (tables.isEmpty()) {
                String full = head.read(file);
                tables = JavaApiSourceParser.parseTables(full == null ? base.read(file) : full);
            }
            boolean ddl = looksLikeDdl(added);
            List<DdlChange> details = JavaApiSourceParser.parseDdl(added);
            for (String t : tables) {
                TableChange tc = out.computeIfAbsent(t, k -> {
                    TableChange x = new TableChange();
                    x.setTable(k);
                    return x;
                });
                if (!tc.getFiles().contains(file)) {
                    tc.getFiles().add(file);
                }
                tc.setDdl(tc.isDdl() || ddl);
                for (DdlChange d : details) {
                    if (d.table().equalsIgnoreCase(t) && !tc.getDdlDetails().contains(d)) {
                        tc.getDdlDetails().add(d);
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private void attachNewColumns(Report report, List<String> changed, SideSource base, SideSource head) {
        List<NewColumnScanner.Hit> hits = NewColumnScanner.scan(changed, base::read, head::read);
        if (hits.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder();
        for (String f : changed) {
            if (f.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                String s = head.read(f);
                if (s != null) {
                    sql.append(s).append("\n;\n");
                }
            }
        }
        for (NewColumnScanner.Hit h : hits) {
            TableChange tc = report.getTables().stream().filter(x -> x.getTable().equalsIgnoreCase(h.table()))
                    .findFirst().orElse(null);
            if (tc == null) {
                tc = new TableChange();
                tc.setTable(h.table());
                tc.getFiles().add(h.file());
                report.getTables().add(tc);
            }
            NewColumn c = new NewColumn();
            c.setColumn(h.column());
            c.setFile(h.file());
            c.setDdlInBranch(NewColumnScanner.declaredIn(sql.toString(), h.table(), h.column()));
            tc.getNewColumns().add(c);
        }
    }

    private static boolean looksLikeDdl(String diffAdded) {
        if (diffAdded == null) {
            return false;
        }
        String s = diffAdded.toUpperCase(Locale.ROOT);
        return s.contains("ALTER TABLE") || s.contains("CREATE TABLE") || s.contains("DROP TABLE")
                || s.contains("ADD COLUMN") || s.contains("MODIFY COLUMN");
    }

    /** 取这个文件在本次 diff 里新增的行；表名只从新增部分认，否则整份 mapper 的表全被报出来 */
    private String addedLines(Path repo, String base, String head, String file) {
        String diff = git(repo, "diff", "--unified=0", base + "..." + head, "--", file);
        if (diff == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                sb.append(line.substring(1)).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== git ====================

    /**
     * 变更文件清单。用三点 {@code base...head}：那是「从两个分支的共同祖先算起，
     * 这个分支干了什么」——正是「本周提测改了什么」的语义。两点会把基线自己的新提交也算进来。
     */
    List<String> changedFiles(Path repo, String base, String head) {
        String out = git(repo, "diff", "--name-only", base + "..." + head);
        List<String> files = new ArrayList<>();
        if (out == null) {
            return files;
        }
        for (String line : out.split("\n")) {
            String f = line.trim();
            if (!f.isEmpty()) {
                files.add(f);
            }
        }
        return files;
    }

    /** git 侧的文件索引：一条 ls-tree 拿到该版本全部路径 */
    private java.util.Map<String, String> gitFileIndex(Path repo, String ref, String ext) {
        java.util.Map<String, String> out = new LinkedHashMap<>();
        String raw = git(repo, "ls-tree", "-r", "--name-only", ref);
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String rel = line.trim();
            if (!rel.endsWith(ext)) {
                continue;
            }
            String name = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - ext.length());
            out.putIfAbsent(FieldTreeBuilder.normalizeKey(name), rel);
        }
        return out;
    }

    /** 取某个版本下的文件内容；文件在该版本不存在（新增/删除）时返回 null */
    String show(Path repo, String ref, String file) {
        return git(repo, "show", ref + ":" + file);
    }

    /** 本地 + 远程分支列表，省得人手打分支名（远程的写成 origin/xxx） */
    public List<String> branches(String repoPath) {
        Path repo = validateRepo(repoPath);
        List<String> out = new ArrayList<>();
        String raw = git(repo, "for-each-ref", "--format=%(refname:short)",
                "--sort=-committerdate", "refs/heads", "refs/remotes");
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String b = line.trim();
            // origin/HEAD 是个符号引用，不是真分支
            if (!b.isEmpty() && !b.endsWith("/HEAD")) {
                out.add(b);
            }
        }
        return out;
    }

    public String currentBranchOf(String repoPath) {
        return currentBranch(validateRepo(repoPath));
    }

    /**
     * 刚 fetch 过的仓库里，本地 master 可能还停在上周，origin/master 才是最新的。
     * 有同名远程分支就用远程的；没有（纯本地分支、提交号）原样返回。
     */
    public String preferRemote(String repoPath, String ref) {
        if (ref == null || ref.isBlank() || ref.startsWith("origin/")) {
            return ref;
        }
        Path repo = validateRepo(repoPath);
        String remote = "origin/" + ref.trim();
        return git(repo, "rev-parse", "--verify", "--quiet", remote + "^{commit}") != null ? remote : ref;
    }

    public enum MergeStatus { MERGED, OPEN, GONE }

    /**
     * 分支是否已经合进基线。只是给「分支已上线」按钮一个提示，上线仍由人确认：
     * 刚拉出来还没提交的分支也会被判成 MERGED，但那种分支不会有分析出来的改动。
     */
    public MergeStatus mergeStatus(String repoPath, String branch, String base) {
        Path repo = validateRepo(repoPath);
        String head = preferRemote(repoPath, branch);
        if (git(repo, "rev-parse", "--verify", "--quiet", head + "^{commit}") == null) {
            return MergeStatus.GONE;
        }
        String b = preferRemote(repoPath, base == null || base.isBlank() ? "master" : base.trim());
        return git(repo, "merge-base", "--is-ancestor", head, b) != null ? MergeStatus.MERGED : MergeStatus.OPEN;
    }

    /** 分支下拉用：origin/xxx 和本地 xxx 只出一条，按最近提交排 */
    public static List<String> displayBranches(List<String> raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (raw != null) {
            for (String b : raw) {
                if (b == null || b.isBlank()) {
                    continue;
                }
                out.add(b.startsWith("origin/") ? b.substring("origin/".length()) : b);
            }
        }
        return new ArrayList<>(out);
    }

    private String currentBranch(Path repo) {
        String b = git(repo, "rev-parse", "--abbrev-ref", "HEAD");
        return b == null || b.isBlank() ? "HEAD" : b.trim();
    }

    private void requireRef(Path repo, String ref) {
        if (git(repo, "rev-parse", "--verify", ref + "^{commit}") == null) {
            throw new IllegalArgumentException("分支或提交不存在: " + ref
                    + "。远程分支要写成 origin/xxx，或先在该仓库执行 git fetch。");
        }
    }

    private Path validateRepo(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            throw new IllegalArgumentException("这个项目还没有本地仓库：去「项目管理」填 GitLab 地址，或点「同步代码」");
        }
        Path p = Path.of(repoPath.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("目录不存在: " + p);
        }
        if (!Files.isDirectory(p.resolve(".git")) && !Files.isRegularFile(p.resolve(".git"))) {
            throw new IllegalArgumentException("不是 git 仓库（缺少 .git）: " + p);
        }
        return p;
    }

    /** 只读地跑一条 git 命令；失败返回 null（文件在该版本不存在是正常情况，不该抛异常） */
    private String git(Path repo, String... args) {
        return git(repo, false, args);
    }

    /** 待测版本里提到这个类名的文件；git 出错时返回 null */
    private List<String> gitGrepFiles(Path repo, String ref, String word) {
        return gitGrepFiles(repo, ref, List.of("grep", "-l", "-w", "-F", "-e", word, ref, "--", "*.java", "*.xml"));
    }

    /** 待测版本里提到这些类名中任意一个的 .java 文件；git 出错时返回 null */
    private List<String> gitGrepFilesAny(Path repo, String ref, Set<String> words) {
        List<String> args = new ArrayList<>(List.of("grep", "-l", "-w", "-F"));
        for (String w : words) {
            args.add("-e");
            args.add(w);
        }
        args.addAll(List.of(ref, "--", "*.java"));
        return gitGrepFiles(repo, ref, args);
    }

    private List<String> gitGrepFiles(Path repo, String ref, List<String> args) {
        String raw = git(repo, true, args.toArray(new String[0]));
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        String prefix = ref + ":";
        for (String line : raw.split("\n")) {
            String f = line.trim();
            if (!f.isEmpty()) {
                out.add(f.startsWith(prefix) ? f.substring(prefix.length()) : f);
            }
        }
        return out;
    }

    static boolean isProductionRef(String file, String selfFile, boolean deep) {
        return !file.equals(selfFile) && !isTest(file, deep)
                && (file.endsWith(".java") || file.endsWith(".xml"));
    }

    /** 接口对比分析页沿用原来的判断（只认 /src/test/）；深度分析连单模块仓库根下的 src/test/ 也算 */
    private static boolean isTest(String file, boolean deep) {
        return deep ? JavaApiSourceParser.isTestPath(file) : file.contains("/src/test/");
    }

    /** grepStyle：退出码 1 表示「没搜到」而不是出错（git grep 的约定） */
    private String git(Path repo, boolean grepStyle, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[api-change] git 超时: {}", String.join(" ", cmd));
                return null;
            }
            if (p.exitValue() == 0) {
                return sb.toString();
            }
            return grepStyle && p.exitValue() == 1 ? "" : null;
        } catch (Exception e) {
            log.warn("[api-change] git 执行失败 {}: {}", String.join(" ", args), e.getMessage());
            return null;
        }
    }

    private static String simpleName(String file) {
        String f = file.replace('\\', '/');
        int slash = f.lastIndexOf('/');
        String name = slash >= 0 ? f.substring(slash + 1) : f;
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
