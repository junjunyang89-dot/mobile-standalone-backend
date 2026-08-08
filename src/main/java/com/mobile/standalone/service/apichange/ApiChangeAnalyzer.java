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
        /**
         * 不是接口变更、但会影响测试的改动：定时任务改了会改变测试环境有什么数据，
         * 缓存 key 变了会出现新老数据混用。这些静默丢掉会让人以为「报告干净＝不用测」。
         */
        private List<OtherChange> otherChanges = new ArrayList<>();
        private int changedFiles;
        /** 说不清楚的地方都写在这，别让人以为分析是完备的 */
        private List<String> notes = new ArrayList<>();
        /** 从代码里能确证的安全与字段设计问题 */
        private List<ApiRiskScanner.Finding> findings = new ArrayList<>();
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
        /** 入参变化：新增必填入参会让老客户端直接 400，比返回体多个字段严重得多 */
        private List<ParamChange> params = new ArrayList<>();
        /** 请求体（@RequestBody）的字段变化 */
        private List<FieldChange> bodyFields = new ArrayList<>();
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
    }

    @Data
    public static class OtherChange {
        /** SCHEDULE / CACHE / CONFIG / MAPPER / SERVICE / OTHER */
        private String kind;
        private String file;
        private String hint;
    }

    @Data
    public static class DtoChange {
        private String type;
        private String file;
        private List<FieldChange> fields = new ArrayList<>();
    }

    @Data
    public static class FieldChange {
        private String changeType;   // ADDED / REMOVED / TYPE_CHANGED
        private String name;
        private String comment;
        private String oldType;
        private String newType;
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
        /** 录进来的建表语句；没录过就是空 */
        private String ddlText;
        /** 从建表语句推导出来的测试注意事项 */
        private List<TableTestHints.Hint> testHints = new ArrayList<>();
        /** 人工补充的注意事项 */
        private String remark;
        /** 哪些接口读写这张表——表的注意事项要能落到具体接口上，否则还得人自己翻代码 */
        private List<String> usedByEndpoints = new ArrayList<>();
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
        Path base = projectRoot(requireDir(baseDir, "基线目录"));
        Path head = projectRoot(requireDir(headDir, "待测目录"));

        Report report = new Report();
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
                });
        return report;
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
        Path repo = validateRepo(repoPath);
        String base = blankTo(baseRef, "master");
        String head = blankTo(headRef, currentBranch(repo));

        Report report = new Report();
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
        for (String f : changed) {
            if (f.endsWith(".java")) {
                changedTypes.add(simpleName(f));
            }
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

        // 没挂到接口上的返回体单独列：可能是公共 DTO 被别处引用，也可能是我没能把它连回接口
        for (Map.Entry<String, DtoChange> e : dtoByType.entrySet()) {
            if (!usedDtoTypes.contains(e.getKey())) {
                report.getOrphanDtos().add(e.getValue());
            }
        }

        report.setTables(collectTables(changed, base, head, addedLines));
        if (locator != null) {
            attachFullFields(report, locator);
            // 必须在 setTables 之后：反向关联要写回 report.tables，
            // 早一步执行等于往一个还是空的列表里写，结果静默丢失
            attachDependencies(report, locator, base, head, changed);
            scanRisks(report, changed, head);
        }
        report.setOtherChanges(collectOtherChanges(changed, head, base, report));
        for (EndpointChange e : report.getEndpoints()) {
            e.setRisk(riskOf(e));
        }
        report.getEndpoints().sort(java.util.Comparator.comparingInt(e -> RISK_ORDER.indexOf(e.getRisk())));
        if (!report.getEndpoints().isEmpty() || !report.getOrphanDtos().isEmpty()) {
            report.getNotes().add("字段按「返回体文件」归集：文件里若有内部类，它们的字段会一并算在该返回体下，"
                    + "层级不做区分。判断「本次多了哪些字段」够用，要精确到某个内嵌对象请点开文件看。");
        }
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
            boolean touched = dto != null || bodyDto != null || !pc.isEmpty()
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

    private static boolean sameSignature(ApiEndpoint a, ApiEndpoint b) {
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
    static String riskOf(EndpointChange e) {
        // 新接口的入参当然「全是新增必填」，但没有老客户端在调它——判成不兼容是纯噪音，
        // 会把真正的破坏性变更淹掉。这是拿 APP-8928 跑出来才看见的：6 个新接口全被标红。
        if ("ADDED".equals(e.getChangeType())) {
            return "NEW";
        }
        boolean breaking = e.getFields().stream()
                .anyMatch(f -> "REMOVED".equals(f.getChangeType()) || "TYPE_CHANGED".equals(f.getChangeType()));
        breaking = breaking || e.getBodyFields().stream()
                .anyMatch(f -> "REMOVED".equals(f.getChangeType()) || "TYPE_CHANGED".equals(f.getChangeType()));
        breaking = breaking || e.getParams().stream().anyMatch(p ->
                ("ADDED".equals(p.getChangeType()) && p.isRequired())
                        || "REMOVED".equals(p.getChangeType())
                        || "TYPE_CHANGED".equals(p.getChangeType()));
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
        List<OtherChange> out = new ArrayList<>();
        for (String file : changed) {
            if (shown.contains(file)) {
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
            c.setHint(hint);
            out.add(c);
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
            throw new IllegalArgumentException("请先在「接口项目」里配置该项目的本地仓库路径");
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
            return p.exitValue() == 0 ? sb.toString() : null;
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
