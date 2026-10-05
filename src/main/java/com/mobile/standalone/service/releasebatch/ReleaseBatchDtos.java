package com.mobile.standalone.service.releasebatch;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个版本（如「10.9 国内H5」）的批量对比：拉到的单子、每单涉及的项目分支、分析结果、测试评估报告。
 * 整份按 JSON 落库，页面关掉再打开还在。
 */
public final class ReleaseBatchDtos {

    private ReleaseBatchDtos() {
    }

    @Data
    public static class Batch {
        private Long id;
        /** 版本定位文案，和 Wiki 汇总页标题一致，如 10.9 国内H5 */
        private String version;
        /** WIKI：从汇总页拉的；MANUAL：手动粘贴的 */
        private String source;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<Ticket> tickets = new ArrayList<>();
        private List<String> logs = new ArrayList<>();
    }

    @Data
    public static class Ticket {
        private String key;
        private String url;
        private String summary;
        private String status;
        private String assignee;
        private String qaAssignee;
        /** 备注 + 描述原文（截断），解析分支用，也给人核对 */
        private String notes;
        /** 备注里写的 SQL / 配置，评估报告「数据库变更」「其他变更」直接引用 */
        private String dbChanges;
        private String configChanges;
        private List<Target> targets = new ArrayList<>();
        /** 按分支自动找到、又被人工删掉的项目编码，再分析时不再加回来 */
        private Set<String> skipBranchProjects = new LinkedHashSet<>();
        /** 关系图上的节点：一个项目的前端、后端各算一个（app-admin 这种同仓库前后端是两个） */
        private List<ProjectNode> nodes = new ArrayList<>();
        /** 项目之间谁调谁，按 RISK → JOINT → CHECK → INFO 排好 */
        private List<Link> links = new ArrayList<>();
        /**
         * 单子里写的列名、配置 key 在待测后端代码里被几个文件提到；null = 没搜过（没拿到代码）。
         * 分析时搜一次存下来，改项目 / 重建评估时不用再读代码。
         */
        private Map<String, Integer> codeMentions;
        /** 线上版本的前端 / 客户端扛不扛得住这次后端改动；null = 没检查（后端没有不兼容改动或没分析成功） */
        private CompatCheck compat;
        private Assessment assessment;
        private LocalDateTime analyzedAt;
        /** AI 把方法级逻辑改动翻译成的业务规则，key = 项目名|类名.方法名；重新分析后改动变了的会被清掉 */
        private Map<String, LogicExplain> logicExplains = new LinkedHashMap<>();
    }

    /** 一个方法的改动翻译成业务语言：旧规则 / 新规则 / 影响 / 测试重点 */
    @Data
    public static class LogicExplain {
        private String title;
        private List<String> oldRule = new ArrayList<>();
        private List<String> newRule = new ArrayList<>();
        /** 计算因子的增减，如 点赞分 新增：ln(1+点赞数)×权重 */
        private List<Factor> factors = new ArrayList<>();
        private String impact;
        private List<String> focus = new ArrayList<>();
        /** 和需求单对一下：单子怎么说、代码是否一致；单子没提时为空 */
        private String vsTicket;
        /** 代码看不全 / 拿不准的地方 */
        private String unsure;
        /** 读到了什么代码：完整方法 / 只有改动行 */
        private String codeSeen;
        /** 生成时这组改动的指纹，改动变了就作废 */
        private String fingerprint;
        private String model;
        private LocalDateTime at;
    }

    @Data
    public static class Factor {
        private String name;
        /** 新增 / 去掉 / 改了 */
        private String change;
        private String detail;
    }

    /** 单子里认出的一个「项目 + 分支」 */
    @Data
    public static class Target {
        /** 备注里的原写法，如 app-engnie、Android */
        private String rawProject;
        private String branch;
        /** 和哪个分支比；空 = 项目设置里的基线（一般 master） */
        private String baseBranch;
        /** 分支后面跟的话，如「按顺序发版」 */
        private String note;
        /** 匹配到的项目编码；null = 项目管理里没找到 */
        private String projectCode;
        private String projectName;
        /** BACKEND / FRONTEND / CLIENT */
        private String kind;
        /** EXACT / ALIAS / FUZZY / CLIENT / MANUAL / BRANCH（备注没写，按分支在仓库里找到） */
        private String matchHow;
        /** 后端才有：是否参与接口对比（网关等不扫接口的为 false） */
        private boolean analyzable;
        /** 本地目录对比用；两个都填了就按目录比，不走 git */
        private String baseDir;
        private String headDir;
        private String collectionId;
        private Long reportId;
        private String reportSummary;
        private String error;
        /** 拉代码的结果，拉失败要在报告里说「比的是旧代码」 */
        private String syncMessage;
        /** 前端 / 客户端代码（含后端仓库里自带的前端目录）的对比结果；没有前端代码为 null */
        private FrontChange front;
        /** 为什么没拿到这个项目的代码；拿到了为 null。关联分析里涉及它的结论都只能算推测 */
        private String codeMissing;
        /** 最近一次「AI 生成用例到分支」的任务；任务只在内存里，服务重启后查不到 */
        private String aiJobId;
    }

    /** 一个调用点：代码里写着某个接口路径的地方 */
    @Data
    public static class CallSite {
        private String file;
        private int line;
        /** 归一后的路径：小写、去 host 和 query、变量换成 {}，拿来和接口比 */
        private String path;
        /** 代码里的原写法 */
        private String raw;
        private String code;
        /** FRONT：js / ts / vue 这类；BACK：Java 里的 Feign / http 调用；CLIENT：客户端代码 */
        private String side;
        /** 看着像接口请求（写在 api 目录、request / axios 旁边）；路由、图片路径不算 */
        private boolean apiLike;
    }

    @Data
    public static class FrontChange {
        private int changedFiles;
        private List<FileRow> files = new ArrayList<>();
        /** 改到的页面 / 组件，给人看「去哪个页面点」 */
        private List<String> pages = new ArrayList<>();
        /** 改动文件里新写上的接口调用 */
        private List<CallSite> addedCalls = new ArrayList<>();
        /** 改动文件里删掉的接口调用 */
        private List<CallSite> removedCalls = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
    }

    /** 关系图节点 */
    @Data
    public static class ProjectNode {
        /** projectCode:FRONT / projectCode:BACK / projectCode:CLIENT */
        private String id;
        private String projectCode;
        private String name;
        /** FRONT / BACK / CLIENT */
        private String side;
        private String branch;
        /** ANALYZED / NO_CODE / ERROR / NOT_FOUND */
        private String status;
        /** 这一侧这次有没有代码改动 */
        private boolean changed;
        private String summary;
    }

    /** 两个项目之间的一条关系：谁调了谁的哪个接口，两边各改没改 */
    @Data
    public static class Link {
        private String fromNode;
        private String toNode;
        private String fromName;
        private String toName;
        private String method;
        private String path;
        private String summary;
        /** 被调接口这次怎么变的：ADDED / MODIFIED / REMOVED；没改为 null */
        private String endpointChange;
        private String endpointRisk;
        /** 调用方这边：NEW_CALL 新写的调用 / CHANGED_FILE 调用所在文件改了 / UNCHANGED 没动 / REMOVED_CALL 删掉了 */
        private String callChange;
        /** RISK 风险 / JOINT 联调点 / CHECK 待确认 / INFO 知道一下 */
        private String level;
        private String title;
        private List<String> refs = new ArrayList<>();
        /** 有一侧缺代码，结论是推出来的 */
        private boolean inferred;
    }

    /** 勾选项：label 是模板里的选项，basis 说明为什么勾 / 不勾 */
    public record Check(String label, boolean checked, String basis) {
    }

    @Data
    public static class FileRow {
        private String project;
        private String path;
        /** ADDED / MODIFIED / DELETED */
        private String status;
    }

    /** 见 {@link CallerCompatProbe} */
    @Data
    public static class CompatCheck {
        private List<CompatHit> hits = new ArrayList<>();
        /** 拿到 master 代码、检查过的调用方 */
        private List<String> checked = new ArrayList<>();
        /** 没拿到代码的调用方和原因：它们受不受影响不知道 */
        private List<String> unchecked = new ArrayList<>();
        /** 超出上限没列出来的条数 */
        private int truncated;
    }

    /** 某个调用方的线上版本调了一个有不兼容改动的接口 */
    @Data
    public static class CompatHit {
        /** RISK 线上会出错 / CHECK 要看一眼 */
        private String level;
        private String caller;
        /** FRONTEND / CLIENT */
        private String callerKind;
        private boolean inTicket;
        private String backend;
        private String endpoint;
        private String problem;
        private List<String> callRefs = new ArrayList<>();
        private String field;
        /** FOUND / NOT_FOUND / TOO_COMMON；不涉及返回字段为 null */
        private String fieldFound;
        private List<String> fieldRefs = new ArrayList<>();
        private String advice;
    }

    /** 全栈影响概览里的一格 */
    public record Stat(String key, String label, int count, String detail, String level) {
    }

    /** 测试清单按端分组：H5 / App / 接口 / 数据库 / 定时任务 / 缓存·消息·外部调用 / 配置 / 安全 */
    @Data
    public static class EndGroup {
        private String key;
        private String label;
        private List<EndItem> items = new ArrayList<>();
        /** 没有条目时说明为什么（没改到 / 没拿到代码） */
        private String note;
        /** 测试指引的影响面才填：YES 涉及 / NO 不涉及 / UNKNOWN 没分析到 */
        private String state;
    }

    /** 测试指引里的一个改动接口：要不要测、怎么测、涉及什么，一处看全 */
    @Data
    public static class EndpointCard {
        private String project;
        private String method;
        private String path;
        private String summary;
        private String changeType;
        private String risk;
        /** NEW 新接口 / BREAKING 不兼容 / OFFLINE 下线 / SHAPE 出入参变了 / ADD 只改实现、还没用例 / RERUN 只改实现、有用例 */
        private String verdict;
        /** 一句话：这个接口该怎么处理 */
        private String action;
        private String priority;
        private int testCases;
        /** 入参 / 出参 / 实现的变化 */
        private List<String> changes = new ArrayList<>();
        /** 挂到这个接口上的方法级逻辑改动（标题），前后对比在逻辑改动那一节 */
        private List<String> logic = new ArrayList<>();
        private List<String> tests = new ArrayList<>();
        private List<String> security = new ArrayList<>();
        private List<String> risks = new ArrayList<>();
        /** 并发 / 重复提交 / 丢更新：既是风险点又是测法，单独成块，不再在 tests、risks 里各写一遍 */
        private List<Focus> focus = new ArrayList<>();
        private List<String> tables = new ArrayList<>();
        private List<String> cacheKeys = new ArrayList<>();
    }

    /**
     * @param label 并发 / 重复提交 / 丢更新
     * @param risk  为什么会出问题
     * @param how   怎么测，含期望结果
     * @param keys  涉及的缓存 key 个数；key 本身在卡片的 cacheKeys 里
     */
    public record Focus(String label, String title, String risk, String how, int keys) {
    }

    /**
     * 上线清单的一步。测试也负责发版，这份清单就是发版时照着做的顺序。
     *
     * @param phase BEFORE 代码上线前要做完 / ORDER 发版顺序 / AFTER 发版后
     * @param must  不做会出线上问题（启动失败、报错、数据丢）；false 是要核对一下
     */
    public record ReleaseStep(String phase, String what, String why, boolean must) {
    }

    /** what：怎么测；why：为什么要测这一条 */
    public record EndItem(String what, String why) {
    }

    /** {@link com.mobile.standalone.service.apichange.MethodLogicDiff.Change} 加上所属项目 */
    @Data
    public static class LogicItem {
        private String project;
        private String className;
        private String member;
        private String kind;
        private String title;
        private String before;
        private String after;
        private String hint;
        private List<String> endpoints = new ArrayList<>();
    }

    @Data
    public static class EndpointDiff {
        private String project;
        private String method;
        private String path;
        private String summary;
        /** ADDED / REMOVED / MODIFIED */
        private String changeType;
        /** BREAKING / NEW / IMPL / COMPATIBLE */
        private String risk;
        private List<String> inputs = new ArrayList<>();
        private List<String> outputs = new ArrayList<>();
        private List<String> impls = new ArrayList<>();
        private int testCases;
    }

    /** 按《代码 Diff 对比分析与测试评估报告》模板组织；manual 是人工填 / 改过的部分 */
    @Data
    public static class Assessment {
        // ---- 基础信息 ----
        private String projectName;
        private String version;
        private String issueKey;
        private String summary;
        private String modules;
        private String developer;
        private String analyst;
        private String date;
        private List<Check> changeTypes = new ArrayList<>();

        // ---- 全栈影响概览 ----
        private List<Stat> overview = new ArrayList<>();
        /** 发版 / 执行顺序，按先后排；{@link #releasePlan} 的纯文字版，导出文档用 */
        private List<String> releaseOrder = new ArrayList<>();
        /** 上线清单：上线前必做（SQL、配置、MQ）→ 发版顺序 → 发版后 */
        private List<ReleaseStep> releasePlan = new ArrayList<>();
        /** 线上版本调用方兼容检查，原样带给前端 */
        private CompatCheck compat;

        // ---- 需求单 ↔ 代码 对账 ----
        /** 表 / 字段 / 配置 / 定时任务 / 项目：单子写的和代码改的对不对得上 */
        private List<TicketCodeCheck.Item> ticketChecks = new ArrayList<>();

        // ---- 一、改动范围 ----
        private List<FileRow> files = new ArrayList<>();
        private List<String> classes = new ArrayList<>();
        private List<String> frontends = new ArrayList<>();
        private List<String> clients = new ArrayList<>();
        private List<String> endpoints = new ArrayList<>();
        private List<String> tables = new ArrayList<>();

        // ---- 二、变更内容详情 ----
        private List<String> logicChanges = new ArrayList<>();
        /** 方法级逻辑改动（带项目名），前端按「类.方法」分组展示前后对比和怎么测 */
        private List<LogicItem> logicDetails = new ArrayList<>();
        private List<EndpointDiff> apiChanges = new ArrayList<>();
        private List<String> dbChanges = new ArrayList<>();
        private String sql;
        private List<String> otherChanges = new ArrayList<>();

        // ---- 三、依赖与关联模块 ----
        private Check publicChange;
        private List<String> publicItems = new ArrayList<>();
        private List<String> relatedModules = new ArrayList<>();
        private List<String> compatNotes = new ArrayList<>();
        private Check needMigration;
        private List<String> migrationNotes = new ArrayList<>();

        // ---- 四、风险点 ----
        private List<String> logicRisks = new ArrayList<>();
        private List<String> compatRisks = new ArrayList<>();
        private List<String> hiddenRisks = new ArrayList<>();
        private List<String> otherRisks = new ArrayList<>();

        // ---- 五、测试建议 ----
        private List<String> normalCases = new ArrayList<>();
        private List<String> boundaryCases = new ArrayList<>();
        private List<String> errorCases = new ArrayList<>();
        private List<String> mustRegress = new ArrayList<>();
        private List<String> relatedRegress = new ArrayList<>();
        private List<String> noRegress = new ArrayList<>();
        private List<Check> methods = new ArrayList<>();
        private List<String> testData = new ArrayList<>();
        private Check automation;
        private List<String> automationRefs = new ArrayList<>();
        private Check baseline;
        private List<String> baselineRefs = new ArrayList<>();
        /** 同一批测试点按端分组的视图 */
        private List<EndGroup> endGroups = new ArrayList<>();

        // ---- 测试指引 ----
        /** 老数据没有下面两项；页面按它判断要不要提示重新分析 */
        private int guideVersion;
        private List<EndpointCard> endpointCards = new ArrayList<>();
        /** 数据库 / 缓存 / 定时任务 / 消息与外部调用 / 配置 / 安全 / 老功能牵连 */
        private List<EndGroup> impacts = new ArrayList<>();

        // ---- 六、待确认 ----
        private List<String> questions = new ArrayList<>();
        private List<String> limits = new ArrayList<>();

        // ---- 七、结论 ----
        /** HIGH / MEDIUM / LOW */
        private String riskLevel;
        private List<String> riskReasons = new ArrayList<>();
        private String conclusion;

        /** 人工填写 / 修改：oldLogic、newLogic、conclusion、questions、notes …… 重新分析不会覆盖 */
        private Map<String, String> manual = new LinkedHashMap<>();
    }
}
