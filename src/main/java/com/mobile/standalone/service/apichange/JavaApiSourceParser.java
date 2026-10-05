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

    /**
     * Lombok 构造器注入：类上有 {@code @RequiredArgsConstructor}，字段写成
     * {@code private final XxxService xxx;}，<b>没有任何注入注解</b>。
     * <p>
     * 只认注解式注入的后果，是这类 Controller 的 {@code usedServices} 恒为空 →
     * 依赖解析返回空 Deps → <b>读写表、Redis key、并发风险、IMPL 档全部归零</b>，
     * 而报告上看着是「这个接口很干净」。这是最坏的一种失败：不报错，只是悄悄少了半份分析。
     * <p>
     * 实测被测后端 app-engine：75 个 Controller 里 7 个用的是这种写法。
     */
    private static final Pattern LOMBOK_CTOR_ANNO = Pattern.compile(
            "@(?:RequiredArgsConstructor|AllArgsConstructor)\\b");

    /**
     * {@code private final 类型 名字;}——<b>不带等号</b>。
     * <p>
     * 带初始值的 {@code private final String PREFIX = "x";} 不是注入，是常量。
     * 不排掉的话每个 Controller 都会多出一堆假的「依赖」，
     * 而依赖清单一旦有噪音，后面基于它的表/并发分析就全跟着脏。
     */
    private static final Pattern FINAL_FIELD = Pattern.compile(
            "private\\s+final\\s+([A-Z][\\w$]*(?:\\s*<[^;=]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*;");

    /** 入参上的注解：@RequestParam / @PathVariable / @RequestBody */
    private static final Pattern PARAM_ANNO = Pattern.compile(
            "@(RequestParam|PathVariable|RequestBody|RequestHeader)\\b");

    private static final Pattern PARAM_REQUIRED_FALSE = Pattern.compile("required\\s*=\\s*false");
    private static final Pattern PARAM_NAME_IN_ANNO = Pattern.compile(
            "(?:^\\s*|\\b(?:value|name)\\s*=\\s*)\"([^\"]+)\"");

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
            // 不能按固定字符数截：一长串 @ApiImplicitParams 能把签名推到上千字符之后。
            // 只要签名在下一个映射注解之前，就是这个接口的
            Matcher next = METHOD_MAPPING.matcher(after);
            int nextMapping = next.find() ? next.start() : Integer.MAX_VALUE;
            if (sm.find() && sm.start() < nextMapping) {
                returnType = sm.group(1).replaceAll("\\s+", "");
                methodName = sm.group(2);
                int argEnd = skipParens(after, sm.end() - 1);
                String argText = argEnd < 0 ? "" : after.substring(sm.end(), argEnd - 1);
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

    /**
     * 控制器里注入的 Service：字段名 -> 类型名。
     * <p>
     * 两种注入方式都认：注解式（{@code @Resource}/{@code @Autowired}）和
     * Lombok 构造器式（类上 {@code @RequiredArgsConstructor} + {@code private final}）。
     */
    static Map<String, String> injectedFields(String source) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        Matcher m = INJECTED_FIELD.matcher(source);
        while (m.find()) {
            out.put(m.group(2), m.group(1));
        }
        // 只有类上确实有 Lombok 构造器注解时才把 private final 当注入。
        // 不加这个前提的话，任何类里的 final 字段都会被当成依赖
        if (LOMBOK_CTOR_ANNO.matcher(source).find()) {
            Matcher lm = FINAL_FIELD.matcher(source);
            while (lm.find()) {
                String type = lm.group(1).trim();
                String name = lm.group(2);
                if (looksLikeDependency(type)) {
                    // 注解式优先：同一个字段两种写法都命中时，以注解那次为准
                    out.putIfAbsent(name, type);
                }
            }
        }
        return out;
    }

    /**
     * 这个 final 字段像不像一个被注入的依赖。
     * <p>
     * 排掉常量和值对象类型。放进来的噪音会一路脏到读写表和并发风险分析，
     * 而那两处的错误都不会报错，只会让报告看起来更「全」。
     */
    private static boolean looksLikeDependency(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String bare = type.replaceAll("<.*>", "").trim();
        if (NOT_DEPENDENCY_TYPES.contains(bare)) {
            return false;
        }
        // 全大写（EnumSet 之类的常量类型）和单字母泛型参数都不是依赖
        return bare.length() > 1 && Character.isUpperCase(bare.charAt(0));
    }

    /** 常见的非依赖类型。列出来比用「名字以 Service 结尾」宽松——Mapper/Client/Manager 也是依赖 */
    private static final Set<String> NOT_DEPENDENCY_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "BigDecimal", "BigInteger", "Object", "Class", "Pattern",
            "List", "Map", "Set", "Collection", "Optional", "Duration", "LocalDate",
            "LocalDateTime", "Date", "UUID", "Logger");

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
            String annoArgs = "";
            int end = am.end();
            int open = skipSpaces(seg, end);
            if (open < seg.length() && seg.charAt(open) == '(') {
                int close = skipParens(seg, open);
                if (close < 0) {
                    continue;
                }
                annoArgs = seg.substring(open + 1, close - 1);
                end = close;
            }
            String source = switch (anno) {
                case "RequestParam" -> "QUERY";
                case "PathVariable" -> "PATH";
                case "RequestBody" -> "BODY";
                default -> "HEADER";
            };
            // 注解后面剩下的就是「类型 名字」，可能还夹着 @Valid、@ApiParam("…") 这类注解
            String rest = stripAnnotations(seg.substring(end)).replaceAll("\\bfinal\\s+", "").trim();
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
            if (c == '"' || c == '\'') {
                int e = skipLiteral(s, i);
                cur.append(s, i, e + 1);
                i = e;
                continue;
            }
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

    /** 去掉所有注解（含括号里的参数），括号按配对算，字符串里的括号、逗号不算 */
    static String stripAnnotations(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '@' && i + 1 < s.length() && Character.isJavaIdentifierStart(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < s.length() && (Character.isJavaIdentifierPart(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                int k = skipSpaces(s, j);
                if (k < s.length() && s.charAt(k) == '(') {
                    int close = skipParens(s, k);
                    j = close < 0 ? s.length() : close;
                }
                out.append(' ');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** open 处是 (，返回配对的 ) 之后的下标；配不上返回 -1 */
    static int skipParens(String s, int open) {
        if (s == null || open < 0 || open >= s.length() || s.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i);
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** quote 处是引号，返回配对引号的下标（跳过转义） */
    private static int skipLiteral(String s, int quote) {
        char q = s.charAt(quote);
        for (int i = quote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == q) {
                return i;
            }
        }
        return s.length() - 1;
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
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
    /**
     * 是不是测试代码。
     * <p>
     * 单模块仓库的路径从仓库根算起是 {@code src/test/...}，前面没有斜杠——
     * 只认 {@code /src/test/} 的话，这类仓库的单测会被当成业务实现报出来
     * （路径里带 /service/ 的直接归成 SERVICE）。
     */
    public static boolean isTestPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replace('\\', '/');
        return p.startsWith("src/test/") || p.contains("/src/test/");
    }

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
            "(?:/\\*\\*(?<doc>(?:[^*]|\\*(?!/))*)\\*/\\s*|//(?<line>[^\\n;]*)\\n\\s*)?"  // 可选 javadoc / 单行注释
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
        boolean[] commented = commentMask(source);
        Matcher m = FIELD.matcher(source);
        while (m.find()) {
            // 「//    private String avatarUrl;」这种注释掉的字段不会出现在返回里
            if (commented[m.start("type")]) {
                continue;
            }
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
            out.add(new DtoField(name, type, commentOf(m.group("doc"), m.group("line"), annos),
                    jsonNameOf(annos, name), annos.contains("@JsonIgnore")));
        }
        return out;
    }

    private static final Pattern JSON_PROPERTY = Pattern.compile(
            "@JsonProperty\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern DOC_ANNOTATION = Pattern.compile(
            "@(?:ApiModelProperty\\s*\\(\\s*(?:value\\s*=\\s*)?|Schema\\s*\\([^)]*?description\\s*=\\s*)\"([^\"]+)\"");

    /** javadoc 优先，其次 @ApiModelProperty / @Schema，最后上一行的 // 注释 */
    private static String commentOf(String doc, String line, String annotations) {
        String c = cleanDoc(doc);
        if (c != null) {
            return c;
        }
        Matcher m = DOC_ANNOTATION.matcher(annotations);
        if (m.find() && !m.group(1).isBlank()) {
            return m.group(1).trim();
        }
        return line == null || line.isBlank() ? null : line.trim();
    }

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

    /**
     * 带动词的表引用，用来分清这张表是<b>读</b>还是<b>写</b>。
     * <p>
     * <b>{@code delete\s+from} 这个分支不能少。</b>少了的话，{@code DELETE FROM t} 里的
     * {@code from} 会被当成查询命中，<b>一条删除语句被判成只读</b>——
     * 而这个判据正是用来决定「能不能拿到生产上跑」的，判错的代价是在生产上删数据。
     * <p>
     * 顺带纠正一个容易想当然的地方：<b>分支的先后顺序在这里不影响结果</b>。
     * 正则引擎是按<b>位置</b>从左往右扫的，在 {@code DELETE} 那个位置上 {@code from} 根本匹配不上，
     * 只有 {@code delete\s+from} 能匹配，两个分支不会在同一位置竞争。
     * （这条是实测出来的——原先这里写的是「顺序不能动，左优先」，听着有道理，但不成立。）
     */
    private static final Pattern SQL_TABLE_REF = Pattern.compile(
            "(?i)\\b(delete\\s+from|replace\\s+into|insert\\s+into|into|update|from|join)"
                    + "\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?");

    /** 这些动词是写 */
    private static final Set<String> WRITE_VERBS = Set.of(
            "delete from", "replace into", "insert into", "into", "update");

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

    /** 读的表和写的表分开 */
    public record TableUse(Set<String> read, Set<String> write) {

        public boolean writesAnything() {
            return write != null && !write.isEmpty();
        }

        /** 一条 SQL 都没扫到。这和「只读」不是一回事——见 WriteEffect */
        public boolean sawNothing() {
            return (read == null || read.isEmpty()) && (write == null || write.isEmpty());
        }
    }

    /**
     * 从 SQL 文本里分别抽出读的表和写的表。
     * <p>
     * 同一张表可以同时出现在两边（{@code UPDATE t SET x=(SELECT ... FROM t)}），
     * 这是事实，不做去重——判「会不会写」时只看 write 那边。
     */
    public static TableUse parseTableUse(String content) {
        Set<String> read = new LinkedHashSet<>();
        Set<String> write = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return new TableUse(read, write);
        }
        Matcher m = SQL_TABLE_REF.matcher(content);
        while (m.find()) {
            String verb = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String lower = m.group(2).toLowerCase(Locale.ROOT);
            if (NOT_TABLE.contains(lower) || lower.length() < 3) {
                continue;
            }
            // ON DUPLICATE KEY UPDATE col = ... —— 这里的 update 后面跟的是列不是表。
            // 不排掉的话，每条 upsert 都会凭空多出一张「写表」
            if ("update".equals(verb) && precededByDuplicateKey(content, m.start())) {
                continue;
            }
            (WRITE_VERBS.contains(verb) ? write : read).add(lower);
        }
        return new TableUse(read, write);
    }

    /** 往前看一小段，判断这个 update 是不是 ON DUPLICATE KEY UPDATE 的一部分 */
    private static boolean precededByDuplicateKey(String content, int at) {
        int from = Math.max(0, at - 40);
        String before = content.substring(from, at).toLowerCase(Locale.ROOT);
        return before.contains("duplicate key") || before.endsWith("key ");
    }

    /** 注释里的 @GetMapping 之类会被当成真接口，先剥掉 */
    static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//[^\\n]*", " ");
    }

    /** 注释换成空格、换行保留；和 {@link #stripComments} 不同，字符串里的 "http://" 不会被当成注释切掉 */
    static String stripCommentsKeepStrings(String source) {
        boolean[] mask = commentMask(source);
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            sb.append(mask[i] && c != '\n' ? ' ' : c);
        }
        return sb.toString();
    }

    /** 每个字符是否落在注释里；字符串里的 // 不算 */
    static boolean[] commentMask(String s) {
        boolean[] mask = new boolean[s.length()];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i) + 1;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                end = end < 0 ? s.length() : end;
                java.util.Arrays.fill(mask, i, end, true);
                i = end;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                java.util.Arrays.fill(mask, i, end, true);
                i = end;
            } else {
                i++;
            }
        }
        return mask;
    }
}
