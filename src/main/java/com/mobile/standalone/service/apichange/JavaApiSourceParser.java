package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Java 源码文本里抽接口、返回体字段、表名。
 * <p>
 * <b>为什么用正则而不是完整 Java 解析器</b>：这里要的不是编译级正确，是
 * 「本周这个分支动了哪些接口、返回体多了什么字段」。这几个后端项目风格高度一致
 * （{@code @RestController} + {@code ResponseResult<T>} + Lombok {@code @Data} 的 DTO），
 * 正则足够，而且不用为了解析 20 多个仓库引入重型依赖、也不受各仓 JDK 版本差异影响。
 * <p>
 * 认不出的地方一律<b>少报不错报</b>：漏一个接口人还会自己抓包，报一个不存在的字段
 * 会让人白查半天，后者代价大得多。
 */
public final class JavaApiSourceParser {

    private JavaApiSourceParser() {
    }

    // ---------- 接口 ----------

    /** 类上的 @RequestMapping("/xxx")，作为方法路径的前缀 */
    private static final Pattern CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\\{\\s]*\"([^\"]*)\"");

    /** 方法上的 @GetMapping / @PostMapping / @RequestMapping(...)，路径可省略 */
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete|Request)Mapping\\s*(?:\\(([^)]*)\\))?");

    private static final Pattern MAPPING_PATH = Pattern.compile(
            "(?:value\\s*=\\s*|path\\s*=\\s*)?[\\{\\s]*\"([^\"]*)\"");

    /** @RequestMapping(method = RequestMethod.POST) 这种写法里的动词 */
    private static final Pattern MAPPING_METHOD = Pattern.compile("RequestMethod\\.([A-Z]+)");

    /** @ApiOperation("查询英雄胜率")，接口的中文说明 */
    private static final Pattern API_OPERATION = Pattern.compile(
            "@ApiOperation\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");

    /** 方法签名：返回类型 + 方法名，用来取 ResponseResult<T> 里的 T */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "public\\s+([A-Za-z_$][\\w$]*(?:\\s*<[^;{]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");

    /** 控制器里注入的 Service 字段：@Resource / @Autowired private XxxService xxx; */
    private static final Pattern INJECTED_FIELD = Pattern.compile(
            "@(?:Resource|Autowired)[^;]*?private\\s+(?:final\\s+)?"
                    + "([A-Za-z_$][\\w$]*)\\s+([a-zA-Z_$][\\w$]*)\\s*;");

    /** 入参上的注解：@RequestParam / @PathVariable / @RequestBody */
    private static final Pattern PARAM_ANNO = Pattern.compile(
            "@(RequestParam|PathVariable|RequestBody|RequestHeader)"
                    + "(?:\\s*\\(([^)]*)\\))?");

    private static final Pattern PARAM_REQUIRED_FALSE = Pattern.compile("required\\s*=\\s*false");
    private static final Pattern PARAM_NAME_IN_ANNO = Pattern.compile(
            "(?:value\\s*=\\s*|name\\s*=\\s*)?\"([^\"]+)\"");

    public static boolean looksLikeController(String source) {
        return source != null && (source.contains("@RestController") || source.contains("@Controller"));
    }

    /**
     * 抽出这个 Controller 里的所有接口。
     * 路径 = 类上的 @RequestMapping + 方法上的路径，都做了斜杠归一。
     */
    public static List<ApiEndpoint> parseEndpoints(String source) {
        List<ApiEndpoint> out = new ArrayList<>();
        if (source == null || source.isBlank() || !looksLikeController(source)) {
            return out;
        }
        String body = stripComments(source);
        String base = "";
        Matcher cm = CLASS_MAPPING.matcher(body);
        // 记的是起点不是终点：两个正则从同一个 @RequestMapping 起手，但 METHOD_MAPPING 会把
        // 整个 (...) 一起吃掉、终点更靠后。按终点判断会漏掉它，于是类注解被当成一个接口，
        // 路径还会拼成「/a/b/a/b」——这个 bug 是拿真实仓库跑出来才看见的。
        int classAnnoStart = -1;
        if (cm.find()) {
            base = cm.group(1);
            classAnnoStart = cm.start();
        }

        Map<String, String> injected = injectedFields(body);

        Matcher mm = METHOD_MAPPING.matcher(body);
        while (mm.find()) {
            // 类上那个 @RequestMapping 自己不是接口
            if (mm.start() == classAnnoStart) {
                continue;
            }
            String verb = mm.group(1);
            String args = mm.group(2) == null ? "" : mm.group(2);

            String path = "";
            Matcher pm = MAPPING_PATH.matcher(args);
            if (pm.find()) {
                path = pm.group(1);
            }
            String httpMethod = "Request".equals(verb) ? methodFromArgs(args) : verb.toUpperCase(Locale.ROOT);

            // 注解和方法签名之间可能还夹着别的注解，往后找最近的一个 public 方法
            String after = body.substring(mm.end());
            Matcher sm = METHOD_SIGNATURE.matcher(after);
            String returnType = null;
            String methodName = null;
            List<ApiParam> params = List.of();
            Set<String> services = Set.of();
            if (sm.find() && sm.start() < 800) {
                returnType = sm.group(1).replaceAll("\\s+", "");
                methodName = sm.group(2);
                String argText = balanced(after, sm.end() - 1, '(', ')');
                params = parseParams(argText);
                services = usedServices(methodBody(after, sm.end()), injected);
            }

            String summary = findSummary(body, mm.end(), after);

            out.add(new ApiEndpoint(
                    httpMethod, joinPath(base, path), methodName, returnType, summary,
                    unwrapPayloadType(returnType), params, services));
        }
        return out;
    }

    /**
     * 找这个接口的 @ApiOperation。
     * <p>
     * 只在<b>本接口注解到方法签名之间</b>这段里找，再不行才往上一小段找。
     * 早先是在 ±400 字符的窗口里搜，结果一个接口会抓到隔壁方法的说明——
     * 五个仓库里表现为「四个不同接口都叫同步绑定数据」，比没有说明更误导。
     */
    private static String findSummary(String body, int mappingEnd, String afterMapping) {
        // 这几个项目的写法是 @GetMapping 在上、@ApiOperation 紧跟其后，所以先往下找
        Matcher sig = METHOD_SIGNATURE.matcher(afterMapping);
        int limit = sig.find() ? sig.start() : Math.min(afterMapping.length(), 400);
        Matcher down = API_OPERATION.matcher(afterMapping.substring(0, limit));
        if (down.find()) {
            return down.group(1);
        }
        // 也有写在 @GetMapping 上面的，往上找最近的一个
        int from = Math.max(0, mappingEnd - 300);
        Matcher up = API_OPERATION.matcher(body.substring(from, mappingEnd));
        String last = null;
        while (up.find()) {
            last = up.group(1);
        }
        return last;
    }

    /** 控制器里注入的 Service：字段名 -> 类型名 */
    static Map<String, String> injectedFields(String source) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        Matcher m = INJECTED_FIELD.matcher(source);
        while (m.find()) {
            out.put(m.group(2), m.group(1));
        }
        return out;
    }

    /**
     * 方法体里用到了哪些注入的 Service。
     * <p>
     * 这是回答「接口签名没变，但实现改了吗」的关键：光看 Controller 和 DTO，
     * Service 里改了业务逻辑的接口在报告里一个字都不会出现，人会以为不用测。
     */
    static Set<String> usedServices(String methodBody, Map<String, String> injected) {
        Set<String> out = new LinkedHashSet<>();
        if (methodBody == null || injected.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : injected.entrySet()) {
            if (methodBody.matches("(?s).*\\b" + Pattern.quote(e.getKey()) + "\\s*\\..*")) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** 从签名的 ( 开始取到配对的 )，再取到方法体的 { ... } */
    static String balanced(String s, int openIdx, char open, char close) {
        if (s == null || openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != open) {
            return "";
        }
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(openIdx + 1, i);
                }
            }
        }
        return "";
    }

    static String methodBody(String after, int sigEnd) {
        int paren = after.indexOf('(', sigEnd - 1);
        if (paren < 0) {
            return "";
        }
        // 跳过参数列表再找方法体的 {
        int depth = 0;
        int i = paren;
        for (; i < after.length(); i++) {
            char c = after.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) break;
            }
        }
        int brace = after.indexOf('{', i);
        return brace < 0 ? "" : balanced(after, brace, '{', '}');
    }

    /**
     * 解析入参。按顶层逗号切，泛型和注解里的逗号不能算。
     * 没带注解的参数（如 HttpServletRequest）跳过，它们不是业务入参。
     */
    static List<ApiParam> parseParams(String argText) {
        List<ApiParam> out = new ArrayList<>();
        if (argText == null || argText.isBlank()) {
            return out;
        }
        for (String part : splitArgs(argText)) {
            String seg = part.trim();
            if (seg.isEmpty()) {
                continue;
            }
            Matcher am = PARAM_ANNO.matcher(seg);
            if (!am.find()) {
                continue;
            }
            String anno = am.group(1);
            String annoArgs = am.group(2) == null ? "" : am.group(2);
            String source = switch (anno) {
                case "RequestParam" -> "QUERY";
                case "PathVariable" -> "PATH";
                case "RequestBody" -> "BODY";
                default -> "HEADER";
            };
            // 注解后面剩下的就是「类型 名字」
            String rest = seg.substring(am.end()).trim();
            // 可能还有别的注解（@Valid @NotNull）
            rest = rest.replaceAll("@[A-Za-z_$][\\w$]*(?:\\s*\\([^)]*\\))?\\s*", "").trim();
            String[] tokens = rest.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            String name = tokens[tokens.length - 1].replaceAll("[^\\w$].*$", "");
            String type = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1))
                    .replaceAll("\\s+", "");
            Matcher nm = PARAM_NAME_IN_ANNO.matcher(annoArgs);
            if (nm.find()) {
                name = nm.group(1);
            }
            // @RequestParam 默认必填；写了 required=false 或给了 defaultValue 才是可选
            boolean required = !PARAM_REQUIRED_FALSE.matcher(annoArgs).find()
                    && !annoArgs.contains("defaultValue");
            out.add(new ApiParam(source, name, type, required));
        }
        return out;
    }

    private static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---------- DDL ----------

    private static final Pattern DDL_ALTER = Pattern.compile(
            "(?i)ALTER\\s+TABLE\\s+`?([a-zA-Z_][\\w]*)`?\\s+"
                    + "(ADD|MODIFY|CHANGE|DROP)\\s+(?:COLUMN\\s+)?`?([a-zA-Z_][\\w]*)`?([^,;\\n]*)");

    private static final Pattern DDL_CREATE = Pattern.compile(
            "(?i)CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?([a-zA-Z_][\\w]*)`?");

    /**
     * 从新增的 SQL 里抽出表结构变更明细。
     * 只报「涉及了某表」人还得自己翻 SQL；加了什么列、什么类型才能直接拿去造数据、对字段。
     */
    public static List<DdlChange> parseDdl(String sql) {
        List<DdlChange> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return out;
        }
        Matcher a = DDL_ALTER.matcher(sql);
        while (a.find()) {
            out.add(new DdlChange(a.group(1).toLowerCase(Locale.ROOT),
                    a.group(2).toUpperCase(Locale.ROOT), a.group(3),
                    a.group(4) == null ? "" : a.group(4).trim()));
        }
        Matcher c = DDL_CREATE.matcher(sql);
        while (c.find()) {
            out.add(new DdlChange(c.group(1).toLowerCase(Locale.ROOT), "CREATE", null, "新建表"));
        }
        return out;
    }

    // ---------- 其它可能影响测试环境的改动 ----------

    /**
     * 给一个变更文件归类。
     * <p>
     * 定时任务改了会直接改变测试环境里有什么数据；缓存 key 变了会出现新老数据混用——
     * 这两类都不是接口变更，但对测试同样要紧，不该被静默丢掉。
     */
    public static String classifyFile(String path, String source) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String src = source == null ? "" : source;
        // 先看它在工程里的角色（路径），再看文件内容。
        // 反过来的话，一个带 @Scheduled 方法的 ServiceImpl 会被整个判成「定时任务」，
        // 提示语就跑偏了——它主要还是业务实现。
        if (p.contains("/schedule/") || p.contains("/job/") || p.endsWith("task.java")) {
            return "SCHEDULE";
        }
        if (p.contains("/service/")) {
            return src.contains("@Scheduled") || src.contains("@XxlJob")
                    ? "SERVICE_SCHEDULED" : "SERVICE";
        }
        if (src.contains("@Scheduled") || src.contains("@XxlJob")) {
            return "SCHEDULE";
        }
        if (p.contains("redis") || p.contains("cache")
                || (p.contains("constant") && src.contains("KEY"))) {
            return "CACHE";
        }
        if (p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties")) {
            return "CONFIG";
        }
        if (p.contains("/mapper/") || p.contains("mapper.xml")) {
            return "MAPPER";
        }
        return "OTHER";
    }

    private static String methodFromArgs(String args) {
        Matcher m = MAPPING_METHOD.matcher(args);
        // 没写 method 的 @RequestMapping 其实是「全部动词」，标 ANY 比瞎猜一个 GET 诚实
        return m.find() ? m.group(1) : "ANY";
    }

    static String joinPath(String base, String path) {
        String b = trimSlash(base);
        String p = trimSlash(path);
        if (b.isEmpty() && p.isEmpty()) {
            return "/";
        }
        if (p.isEmpty()) {
            return "/" + b;
        }
        if (b.isEmpty()) {
            return "/" + p;
        }
        return "/" + b + "/" + p;
    }

    private static String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /**
     * 从 {@code ResponseResult<List<DeadlockHeroStatsDTO>>} 里剥出真正承载字段的类型名。
     * <p>
     * 这几个项目统一用 {@code ResponseResult<T>} 包一层，直接拿返回类型去找 DTO 永远找不到；
     * {@code List}/{@code Map} 等容器也要继续往里剥。
     */
    static String unwrapPayloadType(String returnType) {
        if (returnType == null || returnType.isBlank()) {
            return null;
        }
        String t = returnType.replaceAll("\\s+", "");
        // 一层层剥容器，直到剩下一个不带泛型的类型名
        while (true) {
            int lt = t.indexOf('<');
            if (lt < 0) {
                break;
            }
            int gt = t.lastIndexOf('>');
            if (gt <= lt) {
                break;
            }
            String inner = t.substring(lt + 1, gt);
            // Map<String, XxxVO> 取最后一个类型参数，前面那个基本是 key
            String last = splitTopLevel(inner);
            if (last == null || last.isBlank()) {
                break;
            }
            t = last;
        }
        if (t.isBlank() || isBuiltin(t)) {
            return null;
        }
        return t;
    }

    /** 按顶层逗号切，取最后一段（泛型里的逗号不能算） */
    private static String splitTopLevel(String s) {
        int depth = 0;
        int lastComma = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) lastComma = i;
        }
        return lastComma < 0 ? s : s.substring(lastComma + 1);
    }

    private static final Set<String> BUILTIN = Set.of(
            "void", "Void", "String", "Integer", "int", "Long", "long", "Boolean", "boolean",
            "Double", "double", "Float", "float", "BigDecimal", "Object", "JSONObject", "JSONArray",
            "Date", "LocalDate", "LocalDateTime", "Byte", "Short", "Character");

    static boolean isBuiltin(String type) {
        return type == null || BUILTIN.contains(type);
    }

    // ---------- 返回体字段 ----------

    /**
     * 字段上方的 javadoc 就是字段中文名——这几个项目都这么写，
     * 变更清单里能直接标出「新增 winRate（胜率）」，比只报一个英文名有用得多。
     */
    private static final Pattern FIELD = Pattern.compile(
            "(?:/\\*\\*(?<doc>(?:[^*]|\\*(?!/))*)\\*/\\s*)?"          // 可选 javadoc
                    + "(?:@[A-Za-z_$][\\w$]*(?:\\s*\\([^)]*\\))?\\s*)*"      // 可选注解
                    + "private\\s+(?:final\\s+)?"
                    + "(?<type>[A-Za-z_$][\\w$]*(?:\\s*<[^;]*?>)?(?:\\[\\])?)\\s+"
                    + "(?<name>[a-zA-Z_$][\\w$]*)\\s*(?:=[^;]*)?;");

    /** Spring 组件注解：带这些的是干活的类，不是返回体 */
    private static final Pattern SPRING_BEAN = Pattern.compile(
            "@(Component|Service|Controller|RestController|Repository|Configuration|Mapper)\\b");

    public static boolean looksLikeDto(String path, String source) {
        if (source == null || !source.contains("private ")) {
            return false;
        }
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        // 定时任务、服务、Mapper 这些是干活的类：它们的「字段」是注入的依赖，
        // 报进变更清单纯属噪音，还会占着「可能不兼容」最显眼的位置，
        // 把真正该看的破坏性变更挤下去——DeadlockStatsTask 就是这么混进来的。
        if (SPRING_BEAN.matcher(stripComments(source)).find()) {
            return false;
        }
        if (p.matches(".*(task|job|service|serviceimpl|mapper|controller|util|utils|config|"
                + "handler|listener|factory|builder|manager|helper)\\.java$")) {
            return false;
        }
        // 名字或包路径带 DTO/VO/Resp 等，或者用了 Lombok @Data 的纯数据类
        return p.matches(".*(dto|vo|resp|response|entity|model|bean|param|req|request)[a-z0-9]*\\.java$")
                || p.contains("/vo/") || p.contains("/dto/") || p.contains("/model/")
                || source.contains("@Data");
    }

    /** 抽出所有 private 字段：名字、类型、中文名（来自 javadoc） */
    public static List<DtoField> parseFields(String source) {
        List<DtoField> out = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(source);
        while (m.find()) {
            String name = m.group("name");
            if (name == null || !seen.add(name)) {
                continue;
            }
            String type = m.group("type").replaceAll("\\s+", "");
            // serialVersionUID 之类不是业务字段
            if ("serialVersionUID".equals(name)) {
                seen.remove(name);
                continue;
            }
            // 注入的依赖不是响应字段。即使整个类被当成了返回体，这些也不该报出来
            String anno = m.group(0);
            if (anno.contains("@Resource") || anno.contains("@Autowired")
                    || type.endsWith("Mapper") || type.endsWith("Service") || type.endsWith("Repository")) {
                seen.remove(name);
                continue;
            }
            // 抓包看到的是 @JsonProperty 改过的名字；@JsonIgnore 的字段压根不出现在 JSON 里。
            // 不认这两个注解，字段表就会跟抓包对不上——而对不上比没有更糟，人会怀疑整份报告。
            String annos = m.group(0);
            out.add(new DtoField(name, type, cleanDoc(m.group("doc")),
                    jsonNameOf(annos, name), annos.contains("@JsonIgnore")));
        }
        return out;
    }

    private static final Pattern JSON_PROPERTY = Pattern.compile(
            "@JsonProperty\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    private static String jsonNameOf(String annotations, String fallback) {
        Matcher m = JSON_PROPERTY.matcher(annotations);
        return m.find() ? m.group(1) : fallback;
    }

    /** javadoc 去掉每行的 * 和空白，多行合成一句 */
    static String cleanDoc(String doc) {
        if (doc == null || doc.isBlank()) {
            return null;
        }
        String s = doc.lines()
                .map(l -> l.replaceFirst("^\\s*\\*+", "").trim())
                .filter(l -> !l.isEmpty() && !l.startsWith("@"))
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        return s.isBlank() ? null : s;
    }

    // ---------- 表名 ----------

    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update)\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?");

    /** SQL 关键字后面跟的未必是表；这些一看就不是，滤掉免得清单里全是噪音 */
    private static final Set<String> NOT_TABLE = Set.of(
            "select", "set", "where", "values", "dual", "id", "on", "as", "and", "or", "if",
            "case", "when", "then", "else", "end", "duplicate", "key");

    /**
     * 从 MyBatis XML / SQL 文本里抽表名。
     * <p>
     * 这几个项目没有 {@code @TableName} / JPA {@code @Table}，表名全写在 mapper XML 的 SQL 里，
     * 所以只能从 SQL 语句里认。
     */
    public static Set<String> parseTables(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        Matcher m = SQL_TABLE.matcher(content);
        while (m.find()) {
            String t = m.group(1);
            String lower = t.toLowerCase(Locale.ROOT);
            if (NOT_TABLE.contains(lower) || lower.length() < 3) {
                continue;
            }
            out.add(lower);
        }
        return out;
    }

    /** 注释里的 @GetMapping 之类会被当成真接口，先剥掉 */
    static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//[^\\n]*", " ");
    }
}
