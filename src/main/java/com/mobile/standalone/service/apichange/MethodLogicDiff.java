package com.mobile.standalone.service.apichange;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方法级的逻辑对比：一个类里<b>哪个方法</b>改了、<b>改了什么</b>（判断条件、计算公式、配置默认值、
 * 定时规则、事务、写库 / 缓存 / 消息调用、报错），以及<b>针对这一处怎么测</b>。
 * <p>
 * 只报「某某 ServiceImpl 改了」的话，测试还得自己读 diff 才知道测什么——而读 diff 正是测试最缺时间做的事。
 * 实例：评论得分公式那次改动，文件级只能看到「CommonServiceImpl 改了」；
 * 方法级能看到「点赞改成取对数」「配置项从 6 个值变成 7 个，少配一个得分全为 0」「44 天上限去掉了」。
 * <p>
 * 和别处一样用正则 + 括号配对，不引入 Java 解析器。认不出的写法宁可少报：
 * 每个改了的方法至少会有一条「改动行」兜底，不会因为没认出具体类别就整个消失。
 */
public final class MethodLogicDiff {

    private MethodLogicDiff() {
    }

    @Data
    public static class Change {
        private String file;
        private String className;
        /** 方法名或字段名 */
        private String member;
        /**
         * METHOD_CHANGED / METHOD_ADDED / METHOD_REMOVED / CONDITION / FORMULA / CALC / LITERAL / EXCEPTION /
         * WRITE / CACHE / MQ / REMOTE / TRANSACTION / SCHEDULE / ASYNC / CONFIG / CONSTANT
         * <p>
         * CALC 是「多算了几项、原来的结果没变」：拆中间变量、顺手记一份明细，不能和 FORMULA 混为一谈——
         * 后者会让人去担心已有数据的排序跳变。
         */
        private String kind;
        /** 一句话：改了什么 */
        private String title;
        private String before;
        private String after;
        /** 怎么测：具体到取什么值、查什么、找谁确认 */
        private String hint;
        /** CONFIG 是配置项名，MQ 是 topic（认得出时）：拿去和需求单、别的服务对账 */
        private String ref;
        /** CONFIG：这个列表配置至少要配几个值（代码按下标取到了第几个）；没有这个要求时为 null */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Integer needCount;
        /** CONFIG：代码里没写默认值，各环境不配就启动失败 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        private boolean noDefault;
        /** SCHEDULE / METHOD_ADDED：XxlJob 的 JobHandler 名，调度中心建任务时填 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String jobHandler;
        /** 经由这个方法 / 类的接口（METHOD PATH），由 {@link ApiChangeAnalyzer} 回填 */
        private List<String> endpoints = new ArrayList<>();
    }

    /** 每个文件最多报多少条；新加的大类一次几十个方法，全列出来就没人看了 */
    private static final int MAX_PER_FILE = 40;
    private static final int MAX_LINES = 8;

    // ==================== 入口 ====================

    public static List<Change> diff(String file, String baseSrc, String headSrc) {
        List<Change> out = new ArrayList<>();
        if (headSrc == null) {
            return out;
        }
        String cls = simpleName(file);
        Map<String, Member> before = baseSrc == null ? Map.of() : members(baseSrc);
        Map<String, Member> after = members(headSrc);
        Map<String, Member> renamed = matchSignatureChanges(before, after);
        String propsPrefix = configPropsPrefix(headSrc);
        Map<String, String> consts = stringConstants(after);

        for (Member now : after.values()) {
            Member was = before.containsKey(now.key()) ? before.get(now.key()) : renamed.get(now.key());
            switch (now.kind()) {
                case "FIELD" -> fieldChanges(out, file, cls, was, now, propsPrefix);
                case "METHOD" -> {
                    if (was == null) {
                        out.add(methodAdded(file, cls, now));
                    } else {
                        methodChanges(out, file, cls, was, now, consts);
                    }
                }
                default -> {
                }
            }
        }
        Set<String> matchedOld = new LinkedHashSet<>();
        renamed.values().forEach(m -> matchedOld.add(m.key()));
        for (Member was : before.values()) {
            if (after.containsKey(was.key()) || matchedOld.contains(was.key())) {
                continue;
            }
            if ("METHOD".equals(was.kind())) {
                out.add(change(file, cls, was.name(), "METHOD_REMOVED", "删除了方法 " + was.name() + "()",
                        firstLine(was.decl()), null,
                        "编译能过只说明 Java 代码里没人调了；还要确认定时任务名、MQ 监听、反射 / 配置里没有按名字引用它"));
            } else if ("FIELD".equals(was.kind())) {
                ValueRef v = ValueRef.of(was.annotations());
                if (v != null) {
                    out.add(ref(change(file, cls, was.name(), "CONFIG", "不再读取配置 " + v.key(), v.describe(), null,
                            "配置中心里的 " + v.key() + " 改了也不会再生效；确认这是有意的，并通知运维清理"), v.key()));
                }
            }
        }
        listIndexChanges(out, file, cls, before, after);
        return out.size() > MAX_PER_FILE ? new ArrayList<>(out.subList(0, MAX_PER_FILE)) : out;
    }

    /**
     * 类里叫这个名字的方法（重载都算）/ 字段的完整源码，去掉注释；没有返回 null。
     * 给 AI 翻译逻辑用：改动行只截了前几行，看不全公式。
     */
    public static String memberSource(String src, String name) {
        if (src == null || name == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Member m : members(src).values()) {
            if (!name.equals(m.name())) {
                continue;
            }
            String annos = m.annotations().isEmpty() ? "" : String.join("\n", m.annotations()) + "\n";
            out.add("METHOD".equals(m.kind()) ? annos + m.decl() + " " + m.body() : annos + m.decl());
        }
        return out.isEmpty() ? null : String.join("\n\n", out);
    }

    // ==================== 改了哪些方法（给接口反查用） ====================

    /**
     * 这个类这次改了的方法名，加上本类里直接 / 间接调到它们（或用到改了的字段）的方法。
     * 接口只调了这个 Service 的别的方法时，不该因为「Service 改了」就被拉进必测。
     *
     * @return null 表示说不清（新类、内部类 / 初始化块改了），调用方按「整个类都改了」处理
     */
    static Set<String> touchedMethods(String baseSrc, String headSrc) {
        if (baseSrc == null || headSrc == null) {
            return null;
        }
        Map<String, Member> before = members(baseSrc);
        Map<String, Member> after = members(headSrc);
        if (after.isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        for (Member m : after.values()) {
            Member o = before.get(m.key());
            if (o != null && norm(o.decl()).equals(norm(m.decl())) && norm(o.body()).equals(norm(m.body()))
                    && norm(o.init()).equals(norm(m.init())) && o.annotations().equals(m.annotations())) {
                continue;
            }
            switch (m.kind()) {
                case "METHOD" -> names.add(m.name());
                case "FIELD" -> fields.add(m.name());
                default -> {
                    return null;
                }
            }
        }
        for (Member o : before.values()) {
            if (after.containsKey(o.key())) {
                continue;
            }
            switch (o.kind()) {
                case "METHOD" -> names.add(o.name());
                case "FIELD" -> fields.add(o.name());
                default -> {
                    return null;
                }
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Member m : after.values()) {
                if ("METHOD".equals(m.kind()) && !names.contains(m.name())
                        && (mentionsAny(m.body(), names, true) || mentionsAny(m.body(), fields, false))) {
                    names.add(m.name());
                    grew = true;
                }
            }
        }
        return names;
    }

    static boolean mentionsAny(String text, Set<String> words, boolean asCall) {
        if (text == null || words.isEmpty()) {
            return false;
        }
        String alt = String.join("|", words.stream().map(Pattern::quote).toList());
        return Pattern.compile("(?<![\\w$])(?:" + alt + ")" + (asCall ? "\\s*\\(" : "(?![\\w$])")).matcher(text).find();
    }

    // ==================== 列表配置：代码按下标取到了第几个 ====================

    private static final Pattern LIST_GET = Pattern.compile("\\b(\\w+)\\s*\\.\\s*get\\s*\\(\\s*(\\d+)\\s*\\)");

    /**
     * {@code @Value} 读进来的列表，代码里取的最大下标变大了：配置的值个数不跟着加，新代码一取就越界。
     * 评论得分那次就是 6 个系数变 7 个，少配一个整段算分失败。
     */
    private static void listIndexChanges(List<Change> out, String file, String cls,
                                         Map<String, Member> before, Map<String, Member> after) {
        for (Member f : after.values()) {
            if (!"FIELD".equals(f.kind())) {
                continue;
            }
            ValueRef v = ValueRef.of(f.annotations());
            if (v == null || !v.isList()) {
                continue;
            }
            int was = maxIndex(before.values(), f.name());
            int now = maxIndex(after.values(), f.name());
            if (now <= was) {
                continue;
            }
            int need = now + 1;
            Change c = ref(change(file, cls, f.name(), "CONFIG", "配置 " + v.key() + " 至少要配 " + need + " 个值",
                    was < 0 ? null : "代码最多取到第 " + (was + 1) + " 个值",
                    "现在要取第 " + need + " 个值（" + f.name() + ".get(" + now + ")）",
                    "上线前各环境的 " + v.key() + " 都要补到 " + need + " 个值：少一个时取第 " + need + " 个会越界报错，"
                            + "用到它的计算整段失败或走兜底"
                            + (v.count() > 0 && v.count() < need ? "；代码里的默认值只有 " + v.count() + " 个，也不够" : "")), v.key());
            c.setNeedCount(need);
            out.add(c);
        }
    }

    private static int maxIndex(java.util.Collection<Member> members, String field) {
        int max = -1;
        for (Member m : members) {
            String text = "METHOD".equals(m.kind()) ? m.body() : m.init();
            if (text == null) {
                continue;
            }
            Matcher g = LIST_GET.matcher(text);
            while (g.find()) {
                if (g.group(1).equals(field)) {
                    max = Math.max(max, Integer.parseInt(g.group(2)));
                }
            }
        }
        return max;
    }

    private static final Pattern CONFIG_PROPS = Pattern.compile(
            "@ConfigurationProperties\\s*\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([^\"]+)\"");

    /** 类上 {@code @ConfigurationProperties("x.y")} 的前缀；字段就是 x.y.字段名 这些配置 */
    static String configPropsPrefix(String src) {
        if (src == null) {
            return null;
        }
        Matcher tm = TYPE_DECL.matcher(src);
        String header = tm.find() ? src.substring(0, tm.start()) : src;
        Matcher m = CONFIG_PROPS.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    private static Map<String, String> stringConstants(Map<String, Member> members) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Member m : members.values()) {
            if ("FIELD".equals(m.kind()) && m.isConstant() && m.init() != null && m.init().matches("\"[^\"]*\"")) {
                out.put(m.name(), m.init().substring(1, m.init().length() - 1));
            }
        }
        return out;
    }

    /** camelCase → kebab-case，Spring 配置文件里通常这么写 */
    static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    // ==================== 字段：配置、常量 ====================

    private static void fieldChanges(List<Change> out, String file, String cls, Member was, Member now, String propsPrefix) {
        ValueRef v = ValueRef.of(now.annotations());
        ValueRef old = was == null ? null : ValueRef.of(was.annotations());
        if (v != null && old == null) {
            Change c = ref(change(file, cls, now.name(), "CONFIG", "新增配置项 " + v.key(), null, v.describe(),
                    "确认测试 / 线上配置中心要不要加 " + v.key() + "、配什么值；不配时走代码默认值"
                            + (v.defaultValue() == null ? "（这里没写默认值，不配启动会失败）" : " " + v.defaultValue())), v.key());
            c.setNoDefault(v.defaultValue() == null);
            out.add(c);
        } else if (v != null && !v.key().equals(old.key())) {
            Change c = ref(change(file, cls, now.name(), "CONFIG", "配置项换了名字 " + old.key() + " → " + v.key(),
                    old.describe(), v.describe(),
                    "老配置项 " + old.key() + " 不再生效；新的 " + v.key() + " 各环境都要配上，否则走默认值"), v.key());
            c.setNoDefault(v.defaultValue() == null);
            out.add(c);
        } else if (v != null && !java.util.Objects.equals(v.defaultValue(), old.defaultValue())) {
            String count = "";
            if (v.isList() && old.isList() && v.count() != old.count()) {
                count = "值的个数 " + old.count() + " → " + v.count() + "：配置中心里还是旧的个数时，"
                        + "新代码可能取不到新增的那一位、直接走异常分支；";
            }
            out.add(ref(change(file, cls, now.name(), "CONFIG", "配置项 " + v.key() + " 的默认值改了",
                    old.defaultValue(), v.defaultValue(),
                    count + "配置中心里配了 " + v.key() + " 的话以配置为准、代码默认值不生效——"
                            + "去测试 / 线上配置中心核对这个 key 的实际值和新逻辑是否匹配"), v.key()));
        }
        if (propsPrefix != null && was == null && v == null && !now.isConstant()
                && !now.decl().matches("(?s).*\\bstatic\\b.*")) {
            String key = propsPrefix + "." + kebab(now.name());
            out.add(ref(change(file, cls, now.name(), "CONFIG", "新增配置项 " + key, null,
                    key + (now.init() == null ? "（没写默认值）" : "，默认 " + norm(now.init())),
                    "各环境配置文件 / 配置中心要不要加 " + key + "；不配时是"
                            + (now.init() == null ? " null（或基本类型的 0 / false），用到的地方可能空指针" : "默认值 " + norm(now.init()))), key));
        }
        if (was != null && now.isConstant() && was.isConstant()
                && !norm(now.init()).equals(norm(was.init()))) {
            boolean key = now.name().matches("(?i).*(KEY|PREFIX|CACHE).*");
            out.add(change(file, cls, now.name(), "CONSTANT", "常量 " + now.name() + " 的值改了",
                    norm(was.init()), norm(now.init()),
                    key ? "像是缓存 key：上线后老缓存读不到（或读到旧格式），重点测上线前已有数据的展示和过期后的重新加载"
                            : "所有用到 " + now.name() + " 的地方都会跟着变，按新值取边界测"));
        }
    }

    /** {@code @Value("${key:default}")}，也认 {@code #{'${key:a,b}'.split(',')}} 这种列表写法 */
    record ValueRef(String key, String defaultValue, boolean isList) {
        private static final Pattern P = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

        static ValueRef of(List<String> annotations) {
            for (String a : annotations) {
                if (!a.startsWith("@Value")) {
                    continue;
                }
                Matcher m = P.matcher(a);
                if (m.find()) {
                    String def = m.group(2);
                    return new ValueRef(m.group(1).trim(), def, a.contains("split(") || (def != null && def.contains(",")));
                }
            }
            return null;
        }

        int count() {
            return defaultValue == null || defaultValue.isEmpty() ? 0 : defaultValue.split(",").length;
        }

        String describe() {
            return key + (defaultValue == null ? "（无默认值）" : "，默认 " + defaultValue);
        }
    }

    // ==================== 方法 ====================

    private static Change methodAdded(String file, String cls, Member now) {
        List<String> bits = new ArrayList<>();
        List<String> bounds = new ArrayList<>();
        for (String c : conditions(now.body())) {
            bounds.addAll(boundaries(c));
        }
        if (!bounds.isEmpty()) {
            bits.add("边界：" + String.join("；", bounds.stream().distinct().limit(4).toList()));
        }
        Set<String> writes = calls(now.body(), WRITE_CALL);
        if (!writes.isEmpty()) {
            bits.add("会写库（" + String.join("、", writes) + "），测完查表");
        }
        String sched = annotation(now.annotations(), "@Scheduled", "@XxlJob");
        if (sched != null) {
            bits.add("是定时任务（" + sched + "），测试环境要手动触发");
        }
        String listen = annotation(now.annotations(), LISTENERS);
        if (listen != null) {
            bits.add("是消息消费方（" + listen + "）：MQ 上 topic / 消费组要先建好，和发消息的服务一起改时消费方先发版");
        }
        Change c = change(file, cls, now.name(), "METHOD_ADDED", "新增方法 " + now.name() + "()", null,
                firstLine(now.decl()),
                "先找到谁调它（接口 / 定时任务 / MQ），从入口测" + (bits.isEmpty() ? "" : "；" + String.join("；", bits)));
        c.setJobHandler(xxlJobName(sched));
        if (listen != null) {
            c.setRef(topicOf(listen));
        }
        return c;
    }

    private static final String[] LISTENERS = {"@KafkaListener", "@RabbitListener", "@JmsListener", "@RocketMQMessageListener"};
    private static final Pattern XXL_NAME = Pattern.compile("@XxlJob\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern TOPIC_ATTR = Pattern.compile(
            "\\b(?:topics|topic|queues|destination|value)\\s*=\\s*\\{?\\s*\"([^\"]+)\"|\\(\\s*\"([^\"]+)\"");

    static String xxlJobName(String annotation) {
        if (annotation == null) {
            return null;
        }
        Matcher m = XXL_NAME.matcher(annotation);
        return m.find() ? m.group(1) : null;
    }

    static String topicOf(String annotation) {
        if (annotation == null) {
            return null;
        }
        Matcher m = TOPIC_ATTR.matcher(annotation);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }

    private static final Pattern SEND_TOPIC = Pattern.compile(
            "\\.\\s*(?:send\\w*|convertAndSend|syncSend|asyncSend\\w*)\\s*\\(\\s*(?:\"([^\"]+)\"|([A-Z][A-Z0-9_]+)\\s*[,)])");

    /** 发消息时第一个参数写的 topic：字面量，或者本类里的字符串常量；别的类里的常量认不出 */
    private static Set<String> sentTopics(String body, Map<String, String> consts) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null) {
            return out;
        }
        Matcher m = SEND_TOPIC.matcher(body);
        while (m.find()) {
            String t = m.group(1) != null ? m.group(1) : consts.get(m.group(2));
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    private static void methodChanges(List<Change> out, String file, String cls, Member was, Member now,
                                      Map<String, String> consts) {
        String m = now.name();
        annotationChanges(out, file, cls, m, was.annotations(), now.annotations());
        if (norm(was.body()).equals(norm(now.body()))) {
            return;
        }
        int start = out.size();
        Change lines = lineDiff(file, cls, m, was.body(), now.body());
        out.add(lines);

        List<String> condWas = conditions(was.body());
        List<String> condNow = conditions(now.body());
        List<String> addedC = minus(condNow, condWas);
        List<String> removedC = minus(condWas, condNow);
        Set<String> coveredLiterals = new LinkedHashSet<>();
        for (String[] pair : pairSimilar(removedC, addedC)) {
            coveredLiterals.addAll(literals(pair[0]));
            coveredLiterals.addAll(literals(pair[1]));
            List<String> b = boundaries(pair[1]);
            out.add(change(file, cls, m, "CONDITION", "判断条件改了", pair[0], pair[1],
                    (b.isEmpty() ? "构造满足 / 不满足新条件的数据各测一次" : "边界：" + String.join("；", b))
                            + "；再用只满足旧条件的数据测一次，看结果变化是不是需求要的"));
        }
        for (String c : addedC) {
            coveredLiterals.addAll(literals(c));
            List<String> b = boundaries(c);
            out.add(change(file, cls, m, "CONDITION", "新增判断", null, c,
                    b.isEmpty() ? "构造满足 / 不满足这个条件的数据各测一次" : "边界：" + String.join("；", b)));
        }
        for (String c : removedC) {
            coveredLiterals.addAll(literals(c));
            List<String> b = boundaries(c);
            out.add(change(file, cls, m, "CONDITION", "去掉了判断", c, null,
                    "原来被这个条件拦住 / 分流的数据现在走哪条路？"
                            + (b.isEmpty() ? "用满足原条件的数据测一次" : "用原来的分界值测（" + String.join("；", b) + "）")
                            + "，并找开发确认是有意去掉"));
        }

        if (!arithmetic(was.body()).equals(arithmetic(now.body()))) {
            FormulaDelta d = formulaDelta(was.body(), now.body());
            d.before().forEach(s -> coveredLiterals.addAll(literals(s)));
            d.after().forEach(s -> coveredLiterals.addAll(literals(s)));
            d.added().forEach(s -> coveredLiterals.addAll(literals(s)));
            if (!d.before().isEmpty() || !d.after().isEmpty()) {
                out.add(change(file, cls, m, "FORMULA", "计算方式改了", join(d.before()), join(d.after()),
                        "挑 3 组典型输入（含 0、极大值、负数）按需求里的公式手算，和返回值对比；"
                                + "已有数据按新公式算出来的排序 / 展示会不会突然跳变"));
            }
            if (!d.added().isEmpty()) {
                boolean same = d.before().isEmpty() && d.after().isEmpty();
                out.add(change(file, cls, m, "CALC", same ? "多算了几项，原来的结果没变" : "新增了计算", null, join(d.added()),
                        (same ? "把局部变量代回去后，原来就有的结果和改之前一模一样（只是拆了中间变量 / 顺手多算了几项），"
                                + "已有数据的排序、展示不会因此跳变；" : "")
                                + "按需求核对新增这几项：单位（是否 ×100）、取整方式、各项加起来和总数对不对得上"));
            }
        }

        Set<String> litWas = literals(was.body());
        Set<String> litNow = literals(now.body());
        List<String> litAdded = new ArrayList<>(minusSet(litNow, litWas));
        List<String> litRemoved = new ArrayList<>(minusSet(litWas, litNow));
        litAdded.removeAll(coveredLiterals);
        litRemoved.removeAll(coveredLiterals);
        if ((!litAdded.isEmpty() || !litRemoved.isEmpty()) && litAdded.size() + litRemoved.size() <= 8) {
            out.add(change(file, cls, m, "LITERAL", "数值改了",
                    litRemoved.isEmpty() ? null : String.join("、", litRemoved),
                    litAdded.isEmpty() ? null : String.join("、", litAdded),
                    "阈值 / 系数 / 分页大小这类数字变了，按新值取边界测，并和需求核对数值"));
        }

        setDiff(out, file, cls, m, "EXCEPTION", throwsOf(was.body()), throwsOf(now.body()),
                "新增报错", "构造触发条件，确认返回的错误码和文案前端能正常提示",
                "不再报错", "原来会报错的输入现在返回什么？确认不是把错误吞掉了");
        setDiff(out, file, cls, m, "WRITE", calls(was.body(), WRITE_CALL), calls(now.body(), WRITE_CALL),
                "新增写库", "调用后查表确认落库的字段和值；重复调用 / 并发调用会不会写多",
                "不再写库", "原来写的这份数据现在谁写？依赖它的页面、统计要回归");
        setDiff(out, file, cls, m, "CACHE", calls(was.body(), CACHE_CALL), calls(now.body(), CACHE_CALL),
                "新增缓存操作", "改完数据立刻查，确认不会读到旧缓存；缓存过期前后各测一次",
                "去掉了缓存操作", "原来删 / 写的缓存现在没人管了，改数据后看是否读到旧值");
        int mqFrom = out.size();
        setDiff(out, file, cls, m, "MQ", calls(was.body(), MQ_CALL), calls(now.body(), MQ_CALL),
                "新增发消息", "确认消息发出且消费方生效；消费失败、重复消费时数据对不对",
                "不再发消息", "下游原来靠这条消息做的事（通知、统计、同步）还会发生吗");
        Set<String> newTopics = minusSet(sentTopics(now.body(), consts), sentTopics(was.body(), consts));
        for (int i = mqFrom; i < out.size(); i++) {
            if ("新增发消息".equals(out.get(i).getTitle()) && !newTopics.isEmpty()) {
                out.get(i).setRef(newTopics.iterator().next());
                out.get(i).setAfter(out.get(i).getAfter() + "\ntopic：" + String.join("、", newTopics));
            }
        }
        setDiff(out, file, cls, m, "REMOTE", calls(was.body(), REMOTE_CALL), calls(now.body(), REMOTE_CALL),
                "新增远程调用", "对方服务超时、报错、返回空时这里的表现；测试环境对方服务是否可用",
                "去掉了远程调用", "原来从对方拿的数据现在从哪来，页面上对应字段要核对");

        if (out.size() - start > 1) {
            lines.setHint("下面几条是从这些改动里认出来的具体测试点");
        }
    }

    private static void annotationChanges(List<Change> out, String file, String cls, String m,
                                          List<String> was, List<String> now) {
        String sWas = annotation(was, "@Scheduled", "@XxlJob");
        String sNow = annotation(now, "@Scheduled", "@XxlJob");
        if (!java.util.Objects.equals(sWas, sNow)) {
            String title = sWas == null ? "变成了定时任务" : sNow == null ? "不再是定时任务" : "定时规则改了";
            Change c = change(file, cls, m, "SCHEDULE", title, sWas, sNow,
                    sNow == null ? "调度中心里还挂着的话要停掉；原来定时生成的数据现在谁来生成"
                            : "测试环境要手动触发或等到执行时间；确认执行频率变化对数据量、重复处理的影响，"
                            + "XxlJob 需要在调度中心新建 / 修改任务");
            c.setJobHandler(xxlJobName(sNow != null ? sNow : sWas));
            out.add(c);
        }
        String lWas = annotation(was, LISTENERS);
        String lNow = annotation(now, LISTENERS);
        if (!java.util.Objects.equals(lWas, lNow)) {
            String title = lWas == null ? "开始消费消息" : lNow == null ? "不再消费消息" : "消费的 topic / 消费组改了";
            Change c = change(file, cls, m, "MQ", title, lWas, lNow,
                    lNow == null ? "这个 topic 还有服务在发吗？没人消费会一直堆积；原来靠这里处理的数据现在谁处理"
                            : "MQ 上 topic / 消费组要先建好（没开自动创建时）；和发消息的服务一起改的话消费方先发版，"
                            + "否则新消息没人处理；测试时发一条消息，看这里有没有消费、数据对不对、重复消费会不会写多");
            c.setRef(topicOf(lNow != null ? lNow : lWas));
            out.add(c);
        }
        boolean tWas = annotation(was, "@Transactional") != null;
        boolean tNow = annotation(now, "@Transactional") != null;
        if (tWas != tNow) {
            out.add(change(file, cls, m, "TRANSACTION", tNow ? "加了事务" : "去掉了事务", null, null,
                    tNow ? "构造中途异常，确认前面写的数据整体回滚"
                            : "中途失败时前面的写操作不再回滚：构造中途异常，看数据是不是只写了一半"));
        }
        String cWas = annotation(was, "@Cacheable", "@CacheEvict", "@CachePut");
        String cNow = annotation(now, "@Cacheable", "@CacheEvict", "@CachePut");
        if (!java.util.Objects.equals(cWas, cNow)) {
            out.add(change(file, cls, m, "CACHE", "缓存注解改了", cWas, cNow,
                    "缓存 key / 范围变了：新老缓存不互通，改完数据看能不能立刻读到新值"));
        }
        boolean aWas = annotation(was, "@Async") != null;
        boolean aNow = annotation(now, "@Async") != null;
        if (aWas != aNow) {
            out.add(change(file, cls, m, "ASYNC", aNow ? "改成了异步执行" : "改成了同步执行", null, null,
                    aNow ? "调用方拿到结果时这里可能还没执行完：接口返回后立刻查数据可能还是旧的；异常不会再抛给调用方"
                            : "同步后接口耗时会增加，这里报错会直接让接口失败"));
        }
    }

    // ==================== 公式：先把局部变量代回去再比 ====================

    /**
     * @param before / after 两边都有、但代回局部变量后不一样的计算（真改了算法）
     * @param added          新多出来的计算语句；原来的结果没变时只有这一类
     */
    record FormulaDelta(List<String> before, List<String> after, List<String> added) {
    }

    /**
     * 按行比的话，把 {@code a*x + b*y} 拆成两个中间变量再相加也是「公式改了」，测试会去担心排序跳变。
     * 这里把每个局部变量的赋值代回到用它的地方，比的是展开后的式子。
     */
    static FormulaDelta formulaDelta(String wasBody, String nowBody) {
        Calc was = calc(wasBody);
        Calc now = calc(nowBody);
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : was.inlined().entrySet()) {
            String v = e.getKey();
            List<String> n = now.inlined().get(v);
            if (n != null && !n.equals(e.getValue()) && (was.arith().contains(v) || now.arith().contains(v))) {
                before.add(v + " = " + String.join("；", was.raw().get(v)));
                Set<String> seen = new LinkedHashSet<>(List.of(v));
                List<String> deps = new ArrayList<>();
                now.raw().get(v).forEach(rhs -> newVarsUsed(rhs, was, now, seen, deps));
                after.addAll(deps);
                after.add(v + " = " + String.join("；", now.raw().get(v)));
            }
        }
        List<String> gone = minus(was.stmts(), now.stmts());
        List<String> added = minus(now.stmts(), was.stmts());
        for (String[] p : pairSimilar(gone, added)) {
            before.add(p[0]);
            after.add(p[1]);
        }
        before.addAll(gone);
        return new FormulaDelta(before, after, added);
    }

    /** 改了的结果用到的新中间变量（原来没有的），按定义顺序放在前面；不然只看到 score = a * b 看不出 a、b 怎么算 */
    private static void newVarsUsed(String rhs, Calc was, Calc now, Set<String> seen, List<String> out) {
        Matcher m = IDENT.matcher(rhs);
        while (m.find()) {
            String id = m.group();
            if (was.raw().containsKey(id) || !now.raw().containsKey(id) || !seen.add(id)) {
                continue;
            }
            now.raw().get(id).forEach(r -> newVarsUsed(r, was, now, seen, out));
            out.add(id + " = " + String.join("；", now.raw().get(id)));
        }
    }

    /**
     * @param inlined 变量 → 每次赋值代回局部变量后的式子（去空白）
     * @param raw     变量 → 每次赋值的原文
     * @param arith   赋值里带乘除 / Math 运算的变量
     * @param stmts   不是给局部变量赋值、但带运算的语句（return、setXxx(a * b)）
     */
    private record Calc(Map<String, List<String>> inlined, Map<String, List<String>> raw,
                        Set<String> arith, List<String> stmts) {
    }

    private static Calc calc(String body) {
        Map<String, String> env = new java.util.HashMap<>();
        Calc c = new Calc(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>(), new ArrayList<>());
        if (body == null) {
            return c;
        }
        for (String part : stripStrings(body).split(";")) {
            String s = stripControl(norm(part));
            if (s.isEmpty()) {
                continue;
            }
            int eq = topLevelEquals(s);
            String lhs = eq > 0 ? s.substring(0, eq).trim() : null;
            if (lhs != null && !lhs.isEmpty() && !lhs.contains("(") && !lhs.contains("[")) {
                char op = lhs.charAt(lhs.length() - 1);
                boolean compound = "+-*/%".indexOf(op) >= 0;
                String name = lastIdent(compound ? lhs.substring(0, lhs.length() - 1) : lhs);
                if (name != null) {
                    String rhs = s.substring(eq + 1).trim();
                    String expr = inline(rhs, env);
                    if (compound) {
                        expr = wrap(env.getOrDefault(name, name)) + op + wrap(expr);
                    }
                    env.put(name, expr);
                    c.inlined().computeIfAbsent(name, k -> new ArrayList<>()).add(expr.replaceAll("\\s+", ""));
                    c.raw().computeIfAbsent(name, k -> new ArrayList<>()).add(rhs);
                    if (ARITH.matcher(s).find() || ARITH.matcher(expr).find()) {
                        c.arith().add(name);
                    }
                    continue;
                }
            }
            if (ARITH.matcher(s).find()) {
                c.stmts().add(s.length() > 200 ? s.substring(0, 200) + "…" : s);
            }
        }
        return c;
    }

    private static final Pattern CONTROL_HEAD = Pattern.compile("^(?:if|while|for|switch|synchronized|catch)\\s*\\(");
    private static final Pattern BARE_HEAD = Pattern.compile("^(?:else|try|do|finally)\\b");

    /** 去掉语句前面的 {@code } else if (...) {} 之类，只留下真正的那条语句 */
    private static String stripControl(String s) {
        String t = s;
        while (true) {
            String before = t;
            t = t.replaceFirst("^[\\s{}]+", "");
            Matcher bm = BARE_HEAD.matcher(t);
            if (bm.find()) {
                t = t.substring(bm.end());
            }
            Matcher cm = CONTROL_HEAD.matcher(t);
            if (cm.find()) {
                int close = JavaApiSourceParser.skipParens(t, cm.end() - 1);
                if (close < 0) {
                    return "";
                }
                t = t.substring(close);
            }
            t = t.trim();
            if (t.equals(before)) {
                return t;
            }
        }
    }

    private static String inline(String expr, Map<String, String> env) {
        if (env.isEmpty()) {
            return expr;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = IDENT.matcher(expr);
        int last = 0;
        while (m.find()) {
            String v = env.get(m.group());
            int at = m.start();
            char prev = at > 0 ? expr.charAt(at - 1) : ' ';
            if (v == null || prev == '.' || Character.isJavaIdentifierPart(prev) || followedByParen(expr, m.end())) {
                continue;
            }
            sb.append(expr, last, at).append(wrap(v));
            last = m.end();
        }
        return sb.append(expr.substring(last)).toString();
    }

    private static boolean followedByParen(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '(';
            }
        }
        return false;
    }

    /** 代进去的式子顶层有加减 / 三目 / 比较时加括号，否则 a*b 代进 x*c 不用加 */
    private static String wrap(String e) {
        int depth = 0;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (depth == 0 && i > 0 && "+-?:<>=&|".indexOf(c) >= 0) {
                return "(" + e + ")";
            }
        }
        return e;
    }

    private static void setDiff(List<Change> out, String file, String cls, String m, String kind,
                                Set<String> was, Set<String> now,
                                String addTitle, String addHint, String delTitle, String delHint) {
        Set<String> added = minusSet(now, was);
        Set<String> removed = minusSet(was, now);
        if (!added.isEmpty()) {
            out.add(change(file, cls, m, kind, addTitle, null, String.join("\n", added), addHint));
        }
        if (!removed.isEmpty()) {
            out.add(change(file, cls, m, kind, delTitle, String.join("\n", removed), null, delHint));
        }
    }

    /** 方法体前后各自多出来的行；任何改了的方法都至少有这一条，认不出具体类别也不会消失 */
    private static Change lineDiff(String file, String cls, String m, String was, String now) {
        List<String> a = codeLines(was);
        List<String> b = codeLines(now);
        List<String> removed = minus(a, b);
        List<String> added = minus(b, a);
        return change(file, cls, m, "METHOD_CHANGED",
                m + "() 改了（+" + added.size() + " / -" + removed.size() + " 行）",
                head(removed), head(added),
                "规则没认出具体是哪类改动（不代表没问题）：要读完整方法才说得清新旧差在哪");
    }

    // ==================== 方法体里的各类信号 ====================

    private static final Pattern COND_START = Pattern.compile("\\b(?:if|while)\\s*\\(");

    static List<String> conditions(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        Matcher m = COND_START.matcher(body);
        while (m.find()) {
            int open = m.end() - 1;
            int close = JavaApiSourceParser.skipParens(body, open);
            if (close > open) {
                String c = norm(body.substring(open + 1, close - 1));
                if (!c.isEmpty()) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static final Pattern CMP_NUM = Pattern.compile(
            "([\\w.()\\[\\]]+?)\\s*(<=|>=|==|!=|<|>)\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern NULL_CHECK = Pattern.compile("([\\w.()]+)\\s*[!=]=\\s*null");
    private static final Pattern EMPTY_CHECK = Pattern.compile(
            "(?:(\\w+)\\.(?:isEmpty|isBlank)\\(\\)|(?:StringUtils|CollectionUtils|ObjectUtils|Strings)\\.(?:isEmpty|isBlank|isNotEmpty|isNotBlank)\\(([^)]+)\\))");
    private static final Pattern EQUALS_LIT = Pattern.compile(
            "(?:\"([^\"]*)\"\\.equals\\(([\\w.()]+)\\)|([\\w.()]+)\\.equals\\(\"([^\"]*)\"\\))");

    /** 从一个判断条件推出边界值：a &lt; 7 → 取 6、7；x == null → 传空 */
    static List<String> boundaries(String cond) {
        List<String> out = new ArrayList<>();
        Matcher m = CMP_NUM.matcher(cond);
        while (m.find()) {
            String v = m.group(1);
            String op = m.group(2);
            String n = m.group(3);
            out.add(v + " 取 " + edgeValues(op, n));
        }
        Matcher nm = NULL_CHECK.matcher(cond);
        while (nm.find()) {
            out.add(nm.group(1) + " 传空（null）和有值各一次");
        }
        Matcher em = EMPTY_CHECK.matcher(cond);
        while (em.find()) {
            String v = em.group(1) != null ? em.group(1) : em.group(2);
            out.add(v.trim() + " 传空串 / 空列表和有值各一次");
        }
        Matcher eq = EQUALS_LIT.matcher(cond);
        while (eq.find()) {
            String lit = eq.group(1) != null ? eq.group(1) : eq.group(4);
            String v = eq.group(2) != null ? eq.group(2) : eq.group(3);
            out.add(v + " 取 \"" + lit + "\" 和其它值各一次");
        }
        return out.stream().distinct().toList();
    }

    private static String edgeValues(String op, String n) {
        boolean decimal = n.contains(".");
        if (decimal) {
            return "略小于 " + n + "、等于 " + n + "、略大于 " + n;
        }
        long v = Long.parseLong(n);
        return switch (op) {
            case "<", ">=" -> (v - 1) + " 和 " + v;
            case "<=", ">" -> v + " 和 " + (v + 1);
            default -> v + " 和非 " + v;
        };
    }

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)?[LlFfDd]?(?![\\w.])");

    static Set<String> literals(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(stripStrings(text));
        while (m.find()) {
            String v = m.group().replaceAll("[LlFfDd]$", "");
            if (!v.equals("0") && !v.equals("1")) {
                out.add(v);
            }
        }
        return out;
    }

    private static final Pattern ARITH = Pattern.compile(
            "Math\\.\\w+\\(|\\.(?:multiply|divide|subtract|setScale|pow)\\(|\\w\\s*[*/]\\s*[\\w(]");

    /** 带乘除 / Math / BigDecimal 运算的语句：计算公式基本都长这样 */
    static List<String> arithmetic(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String stmt : stripStrings(body).split(";")) {
            String s = norm(stmt.replaceAll("^[\\s{}]+", ""));
            if (!s.isEmpty() && ARITH.matcher(s).find()) {
                out.add(s.length() > 200 ? s.substring(0, 200) + "…" : s);
            }
        }
        return out;
    }

    private static final Pattern THROW = Pattern.compile("throw\\s+new\\s+(\\w+)\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern FAIL_RESULT = Pattern.compile(
            "\\b(?:Result|ResponseResult|R|AjaxResult|CommonResult|ApiResult)\\s*\\.\\s*(?:fail|error|failed)\\w*\\s*\\(([^;]*)\\)");

    static Set<String> throwsOf(String body) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null) {
            return out;
        }
        Matcher m = THROW.matcher(body);
        while (m.find()) {
            out.add(m.group(1) + "(" + norm(m.group(2)) + ")");
        }
        Matcher f = FAIL_RESULT.matcher(body);
        while (f.find()) {
            out.add("返回失败(" + norm(f.group(1)) + ")");
        }
        return out;
    }

    private static final Pattern WRITE_CALL = Pattern.compile(
            "\\b(\\w*(?:Mapper|Dao|DAO|Repository|Repo|Service))\\s*\\.\\s*"
                    + "((?:insert|update|delete|save|remove|batchInsert|batchUpdate|upsert|replace)\\w*)\\s*\\(");
    private static final Pattern CACHE_CALL = Pattern.compile(
            "\\b(\\w*(?:[Rr]edis|[Cc]ache|[Jj]edis)\\w*)\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern MQ_CALL = Pattern.compile(
            "\\b(\\w*(?:[Mm]q|MQ|[Kk]afka|[Rr]ocket|[Rr]abbit|[Pp]roducer)\\w*)\\s*\\.\\s*"
                    + "(send\\w*|convertAndSend|syncSend|asyncSend\\w*)\\s*\\(");
    private static final Pattern REMOTE_CALL = Pattern.compile(
            "\\b(\\w*(?:Client|Feign|FeignService|RemoteService|Api))\\s*\\.\\s*(\\w+)\\s*\\(");

    static Set<String> calls(String body, Pattern p) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null) {
            return out;
        }
        Matcher m = p.matcher(stripStrings(body));
        while (m.find()) {
            out.add(m.group(1) + "." + m.group(2));
        }
        return out;
    }

    // ==================== 成员切分 ====================

    /**
     * @param kind METHOD / FIELD / TYPE / INIT
     * @param key  方法是「名字(参数类型)」，字段是「field:名字」——重载方法不能互相覆盖
     */
    record Member(String key, String kind, String name, List<String> annotations, String decl,
                  String body, String init, boolean isConstant) {
    }

    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");

    /** 顶层类的成员。内部类整体算一个成员，不往里拆 */
    static Map<String, Member> members(String src) {
        Map<String, Member> out = new LinkedHashMap<>();
        String s = JavaApiSourceParser.stripCommentsKeepStrings(src.replace("\r\n", "\n"));
        Matcher tm = TYPE_DECL.matcher(s);
        if (!tm.find()) {
            return out;
        }
        int open = indexOfCode(s, '{', tm.end());
        if (open < 0) {
            return out;
        }
        int depth = 1;
        int paren = 0;
        int start = open + 1;
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
                continue;
            }
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
                // 字段初始化里的 lambda / 匿名类：} 后面紧跟 ; , ) 说明成员还没完
                if (depth == 1 && paren == 0 && !continuesAfter(s, i + 1)) {
                    addMember(out, s.substring(start, i + 1));
                    start = i + 1;
                }
            } else if (c == ';' && depth == 1 && paren == 0) {
                addMember(out, s.substring(start, i + 1));
                start = i + 1;
            }
        }
        return out;
    }

    private static boolean continuesAfter(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == ';' || c == ',' || c == ')' || c == '.';
            }
        }
        return false;
    }

    private static final Pattern ANNO_NAME = Pattern.compile("@[\\w.]+");

    private static void addMember(Map<String, Member> out, String raw) {
        String text = raw.trim();
        if (text.isEmpty() || text.equals(";")) {
            return;
        }
        List<String> annos = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= text.length() || text.charAt(i) != '@' || text.startsWith("@interface", i)) {
                break;
            }
            Matcher an = ANNO_NAME.matcher(text);
            if (!an.find(i) || an.start() != i) {
                break;
            }
            int end = an.end();
            int j = end;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j < text.length() && text.charAt(j) == '(') {
                int close = JavaApiSourceParser.skipParens(text, j);
                end = close < 0 ? text.length() : close;
            }
            annos.add(norm(text.substring(i, end)));
            i = end;
        }
        String decl = text.substring(Math.min(i, text.length())).trim();
        if (decl.isEmpty()) {
            return;
        }
        int brace = indexOfCode(decl, '{', 0);
        String head = brace < 0 ? decl : decl.substring(0, brace);
        Matcher td = TYPE_DECL.matcher(head);
        if (td.find()) {
            out.putIfAbsent("type:" + td.group(2), new Member("type:" + td.group(2), "TYPE", td.group(2),
                    annos, head, decl, null, false));
            return;
        }
        if (head.trim().equals("static") || head.isBlank()) {
            out.putIfAbsent("init:" + out.size(), new Member("init:" + out.size(), "INIT", "初始化块",
                    annos, head, decl, null, false));
            return;
        }
        int paren = decl.indexOf('(');
        int eq = topLevelEquals(decl);
        if (paren >= 0 && (eq < 0 || paren < eq) && (brace < 0 || paren < brace)) {
            String name = identBefore(decl, paren);
            if (name == null) {
                return;
            }
            int close = JavaApiSourceParser.skipParens(decl, paren);
            String params = close < 0 ? "" : decl.substring(paren + 1, close - 1);
            String body = "";
            if (close > 0) {
                int b = indexOfCode(decl, '{', close);
                int semi = decl.indexOf(';', close);
                if (b >= 0 && (semi < 0 || b < semi)) {
                    body = decl.substring(b);
                }
            }
            String key = name + "(" + paramTypes(params) + ")";
            out.putIfAbsent(key, new Member(key, "METHOD", name, annos, norm(decl.substring(0, close < 0 ? decl.length() : close)),
                    body, null, false));
            return;
        }
        int end = eq >= 0 ? eq : decl.lastIndexOf(';');
        if (end < 0) {
            end = decl.length();
        }
        String name = lastIdent(decl.substring(0, end));
        if (name == null) {
            return;
        }
        String init = eq >= 0 ? decl.substring(eq + 1).replaceAll(";\\s*$", "").trim() : null;
        boolean constant = head.matches("(?s).*\\bstatic\\b.*") && head.matches("(?s).*\\bfinal\\b.*") && init != null;
        out.putIfAbsent("field:" + name, new Member("field:" + name, "FIELD", name, annos, norm(decl), null, init, constant));
    }

    /**
     * 签名改了（加了个参数）的方法按「同名且两边都唯一」配对，算作修改而不是「删一个加一个」——
     * 后者会让测试以为是全新方法，丢掉「原来怎么做、现在怎么做」的对比。
     */
    private static Map<String, Member> matchSignatureChanges(Map<String, Member> before, Map<String, Member> after) {
        Map<String, Member> out = new LinkedHashMap<>();
        for (Member now : after.values()) {
            if (!"METHOD".equals(now.kind()) || before.containsKey(now.key())) {
                continue;
            }
            List<Member> sameName = before.values().stream()
                    .filter(b -> "METHOD".equals(b.kind()) && b.name().equals(now.name()) && !after.containsKey(b.key()))
                    .toList();
            long headSame = after.values().stream()
                    .filter(a -> "METHOD".equals(a.kind()) && a.name().equals(now.name()) && !before.containsKey(a.key()))
                    .count();
            if (sameName.size() == 1 && headSame == 1) {
                out.put(now.key(), sameName.get(0));
            }
        }
        return out;
    }

    // ==================== 小工具 ====================

    private static Change change(String file, String cls, String member, String kind, String title,
                                 String before, String after, String hint) {
        Change c = new Change();
        c.setFile(file);
        c.setClassName(cls);
        c.setMember(member);
        c.setKind(kind);
        c.setTitle(title);
        c.setBefore(before);
        c.setAfter(after);
        c.setHint(hint);
        return c;
    }

    private static Change ref(Change c, String ref) {
        c.setRef(ref);
        return c;
    }

    private static String annotation(List<String> annos, String... names) {
        for (String a : annos) {
            for (String n : names) {
                if (a.equals(n) || a.startsWith(n + "(") || a.startsWith(n + " ")) {
                    return a;
                }
            }
        }
        return null;
    }

    private static String paramTypes(String params) {
        if (params.isBlank()) {
            return "";
        }
        List<String> types = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : (params + ",").toCharArray()) {
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                String p = cur.toString().replaceAll("@[\\w.]+(\\([^)]*\\))?", " ")
                        .replaceAll("\\bfinal\\b", " ").trim().replaceAll("\\s+", " ");
                int sp = p.lastIndexOf(' ');
                types.add((sp > 0 ? p.substring(0, sp) : p).replace(" ", ""));
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        return String.join(",", types);
    }

    private static int topLevelEquals(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
            } else if (c == '(' || c == '<' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == '}') {
                depth--;
            } else if (c == '=' && depth == 0 && i + 1 < s.length() && s.charAt(i + 1) != '='
                    && (i == 0 || "!<>=".indexOf(s.charAt(i - 1)) < 0)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCode(String s, char target, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static int skipLiteral(String s, int quote) {
        char q = s.charAt(quote);
        if (q == '"' && s.startsWith("\"\"\"", quote)) {
            int end = s.indexOf("\"\"\"", quote + 3);
            return end < 0 ? s.length() - 1 : end + 2;
        }
        for (int i = quote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == q || c == '\n') {
                return i;
            }
        }
        return s.length() - 1;
    }

    private static String identBefore(String s, int idx) {
        int e = idx;
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        int b = e;
        while (b > 0 && Character.isJavaIdentifierPart(s.charAt(b - 1))) {
            b--;
        }
        return b < e ? s.substring(b, e) : null;
    }

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static String lastIdent(String s) {
        Matcher m = IDENT.matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static String stripStrings(String s) {
        return s.replaceAll("\"(?:[^\"\\\\\\n]|\\\\.)*\"", "\"\"");
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static List<String> codeLines(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (String line : body.split("\n")) {
            String t = norm(line);
            if (!t.isEmpty() && !t.equals("{") && !t.equals("}")) {
                out.add(t);
            }
        }
        return out;
    }

    /** a 里有、b 里没有的（按出现次数扣减，同一行出现两次删一次也算） */
    private static List<String> minus(List<String> a, List<String> b) {
        List<String> rest = new ArrayList<>(b);
        List<String> out = new ArrayList<>();
        for (String x : a) {
            if (!rest.remove(x)) {
                out.add(x);
            }
        }
        return out;
    }

    private static Set<String> minusSet(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    /** 去掉的和新增的判断里，用到的变量大半相同的配成一对，报成「从 A 改成 B」 */
    private static List<String[]> pairSimilar(List<String> removed, List<String> added) {
        List<String[]> out = new ArrayList<>();
        for (java.util.Iterator<String> it = removed.iterator(); it.hasNext(); ) {
            String r = it.next();
            Set<String> rt = idents(r);
            String best = null;
            double score = 0;
            for (String a : added) {
                Set<String> at = idents(a);
                Set<String> inter = new LinkedHashSet<>(rt);
                inter.retainAll(at);
                Set<String> union = new LinkedHashSet<>(rt);
                union.addAll(at);
                double j = union.isEmpty() ? 0 : (double) inter.size() / union.size();
                if (j > score) {
                    score = j;
                    best = a;
                }
            }
            if (best != null && score >= 0.5) {
                out.add(new String[]{r, best});
                added.remove(best);
                it.remove();
            }
        }
        return out;
    }

    private static Set<String> idents(String s) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(stripStrings(s));
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String head(List<String> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        List<String> h = lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
        return String.join("\n", h) + (lines.size() > MAX_LINES ? "\n…… 还有 " + (lines.size() - MAX_LINES) + " 行" : "");
    }

    private static String join(List<String> xs) {
        return xs.isEmpty() ? null : String.join("\n", xs.size() > 4 ? xs.subList(0, 4) : xs);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        String t = norm(s);
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String simpleName(String file) {
        String f = file == null ? "" : file.replace('\\', '/');
        String name = f.substring(f.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }
}
