package com.mobile.standalone.service.apitest;

import com.mobile.standalone.service.apitest.EndpointInsight.Code;
import com.mobile.standalone.service.apitest.EndpointInsight.Repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按源码读每个入参：不传、传错类型、越界、传个代码不认识的值，接口分别会怎样。
 * <p>
 * 只认几类写法：框架层（@RequestParam 必填、@Valid + 校验注解、基本类型不能为 null）、
 * {@code if (x == null) throw / return}、校验工具方法（会顺着调用展开一两层）、
 * {@code x == null ? … } 兜默认值、没判空就拿来比较/调方法（空指针），
 * 再加上全局异常处理把各类异常翻成什么业务码。认不出的留空，不猜。
 */
public final class ParamInsight {

    private ParamInsight() {
    }

    /** 报错：返回 code（或 HTTP 状态） */
    public static final String REJECT = "REJECT";
    /** 照样返回成功码，但数据是空的（null / 空列表 / 空对象） */
    public static final String SILENT = "SILENT";
    /** 跳过一段逻辑，照常返回 */
    public static final String SKIP = "SKIP";
    /** 用默认值照常处理 */
    public static final String DEFAULT = "DEFAULT";
    /** 可选参数：判了非空才用，不传就不走那段 */
    public static final String OPTIONAL = "OPTIONAL";
    /** 没判空直接用，空指针之类的异常落到兜底处理 */
    public static final String CRASH = "CRASH";
    /** 没有全局异常处理接住，Spring 直接回 HTTP 状态码（code 里是状态码） */
    public static final String HTTP = "HTTP";

    /**
     * @param kind    见上面的常量
     * @param code    业务码（HTTP 时是状态码）；看不出具体码时为 null
     * @param returns SILENT 时返回的是什么：null / emptyList / emptyObject
     * @param detail  一句人话：代码在这里做了什么
     * @param suspect 疑似问题（缺参静默成功、没判空报系统繁忙…）
     */
    public record Outcome(String kind, String code, String codeName, String message, String returns,
                          String detail, String where, boolean suspect) {
    }

    /** @param value 会触发这条规则的值 */
    public record Bound(String value, String rule, Outcome outcome) {
    }

    /**
     * @param in       query / body
     * @param type     number / string / boolean / date / enum / array
     * @param required 框架层必填（@RequestParam 默认 required、@Valid + @NotNull）
     * @param values   代码里认的取值（{@code type == 1}、{@code case 2:}）
     * @param unsupported 传个代码不认识的值会怎样
     * @param warns    写了但不生效的校验之类
     */
    public record Param(String name, String in, String javaType, String type, boolean required, String comment,
                        String where, Outcome missing, Outcome badType, List<Bound> bounds,
                        List<String> values, Outcome unsupported, List<String> warns) {
    }

    // ---------- 全局异常处理 ----------

    static final List<String> MISSING_PARAM = List.of("MissingServletRequestParameterException",
            "MissingRequestValueException", "ServletRequestBindingException", "ServletException", "Exception",
            "Throwable");
    static final List<String> TYPE_MISMATCH = List.of("MethodArgumentTypeMismatchException",
            "TypeMismatchException", "BeansException", "NestedRuntimeException", "RuntimeException", "Exception",
            "Throwable");
    static final List<String> BIND = List.of("BindException", "Exception", "Throwable");
    static final List<String> BODY_INVALID = List.of("MethodArgumentNotValidException", "BindException",
            "Exception", "Throwable");
    static final List<String> NOT_READABLE = List.of("HttpMessageNotReadableException",
            "HttpMessageConversionException", "NestedRuntimeException", "RuntimeException", "Exception", "Throwable");
    static final List<String> CONSTRAINT = List.of("ConstraintViolationException", "ValidationException",
            "RuntimeException", "Exception", "Throwable");
    static final List<String> NPE = List.of("NullPointerException", "RuntimeException", "Exception", "Throwable");
    static final List<String> ILLEGAL_STATE = List.of("IllegalStateException", "RuntimeException", "Exception",
            "Throwable");
    static final List<String> ILLEGAL_ARG = List.of("IllegalArgumentException", "RuntimeException", "Exception",
            "Throwable");
    private static final Set<String> CATCH_ALL = Set.of("Exception", "Throwable", "RuntimeException");

    /** 异常类 → 全局处理里返回的码；passthrough＝原样返回异常里带的码 */
    static final class Advice {
        final Map<String, Code> codes = new HashMap<>();
        final Map<String, String> handlerWhere = new HashMap<>();
        final Set<String> passthrough = new HashSet<>();

        record Hit(String exception, Code code, boolean passthrough, String where) {
            boolean fallback() {
                return CATCH_ALL.contains(exception);
            }
        }

        Hit resolve(List<String> chain) {
            for (String ex : chain) {
                if (passthrough.contains(ex)) {
                    return new Hit(ex, null, true, handlerWhere.get(ex));
                }
                Code c = codes.get(ex);
                if (c != null) {
                    return new Hit(ex, c, false, handlerWhere.get(ex));
                }
            }
            return null;
        }
    }

    private static final Pattern EXCEPTION_HANDLER = Pattern.compile(
            "@ExceptionHandler\\b\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern CLASS_LITERAL = Pattern.compile("\\b([A-Z]\\w*)\\s*\\.\\s*class\\b");
    private static final Pattern EX_PARAM = Pattern.compile("\\b([A-Z]\\w*(?:Exception|Throwable|Error))\\s+(\\w+)\\s*[,)]");
    private static final Pattern ENUM_REF = Pattern.compile("\\b([A-Z]\\w*)\\.([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern BARE_CONST = Pattern.compile("(?<![\\w.])([A-Z][A-Z0-9_]{2,})\\b");
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+[\\w.]*?\\.?([A-Z]\\w*)\\.(\\*|\\w+)\\s*;");
    private static final Pattern NUM_LITERAL = Pattern.compile("^\\s*(-?\\d+)[lL]?\\s*$");
    private static final Pattern STR_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    static Advice advice(Repo repo, Map<String, Map<String, Code>> enums) {
        Advice out = new Advice();
        for (Path p : EndpointInsight.adviceFiles(repo)) {
            String text = repo.read(p);
            String clean = JavaSnippets.blankLiterals(text);
            Map<String, String> statics = staticImports(text);
            String file = p.getFileName().toString();
            Matcher m = EXCEPTION_HANDLER.matcher(clean);
            while (m.find()) {
                int open = clean.indexOf('{', m.end());
                int close = open < 0 ? -1 : matching(clean, open, '{', '}');
                if (close < 0) {
                    continue;
                }
                String sig = clean.substring(m.end(), open);
                Set<String> classes = new LinkedHashSet<>();
                if (m.group(1) != null) {
                    Matcher c = CLASS_LITERAL.matcher(m.group(1));
                    while (c.find()) {
                        classes.add(c.group(1));
                    }
                }
                Matcher ep = EX_PARAM.matcher(sig);
                String var = null;
                if (ep.find()) {
                    var = ep.group(2);
                    if (classes.isEmpty()) {
                        classes.add(ep.group(1));
                    }
                }
                String body = clean.substring(open, close);
                String where = file + ":" + EndpointInsight.lineOf(text, m.start());
                Code code = firstCode(repo, enums, body, statics, where);
                boolean pass = code == null && var != null && Pattern.compile("\\b" + Pattern.quote(var)
                        + "\\s*\\.\\s*get(?:ResponseResult|Result|Code|ErrorCode|Response|Status|Resp)\\s*\\(")
                        .matcher(body).find();
                if (code == null && !pass) {
                    Matcher n = Pattern.compile("\\(\\s*(\\d{3,})\\s*,").matcher(body);
                    if (n.find()) {
                        code = new Code(null, n.group(1), null, where);
                    }
                }
                for (String ex : classes) {
                    if (pass) {
                        out.passthrough.add(ex);
                    } else if (code != null) {
                        out.codes.putIfAbsent(ex, code);
                    } else {
                        continue;
                    }
                    out.handlerWhere.putIfAbsent(ex, where);
                }
            }
        }
        return out;
    }

    /** 一段代码里第一个能解析成「非成功」返回码的枚举常量（含静态导入的裸常量） */
    private static Code firstCode(Repo repo, Map<String, Map<String, Code>> enums, String clean,
                                  Map<String, String> statics, String where) {
        int best = Integer.MAX_VALUE;
        Code found = null;
        Matcher m = ENUM_REF.matcher(clean);
        while (m.find()) {
            Code def = EndpointInsight.constant(repo, enums, m.group(1), m.group(2));
            if (def != null && !EndpointInsight.isSuccessName(def.name())) {
                best = m.start();
                found = new Code(def.name(), def.code(), def.message(), where);
                break;
            }
        }
        Matcher b = BARE_CONST.matcher(clean);
        while (b.find() && b.start() < best) {
            String en = statics.get(b.group(1));
            Code def = en == null ? null : EndpointInsight.constant(repo, enums, en, b.group(1));
            if (def != null && !EndpointInsight.isSuccessName(def.name())) {
                return new Code(def.name(), def.code(), def.message(), where);
            }
        }
        return found;
    }

    /** 静态导入：常量名 → 所在类；{@code import static X.*} 记成 "*"+类名 */
    static Map<String, String> staticImports(String src) {
        Map<String, String> out = new HashMap<>();
        Matcher m = STATIC_IMPORT.matcher(src);
        List<String> wild = new ArrayList<>();
        while (m.find()) {
            if (m.group(2).equals("*")) {
                wild.add(m.group(1));
            } else {
                out.put(m.group(2), m.group(1));
            }
        }
        for (String w : wild) {
            out.put("*" + w, w);
        }
        return new StaticMap(out, wild);
    }

    /** 带通配静态导入兜底的 map：get 不到具体常量时退到 {@code import static X.*} 的第一个类 */
    private static final class StaticMap extends HashMap<String, String> {
        private final List<String> wild;

        StaticMap(Map<String, String> m, List<String> wild) {
            super(m);
            this.wild = wild;
        }

        @Override
        public String get(Object key) {
            String v = super.get(key);
            return v != null || wild.isEmpty() ? v : wild.get(0);
        }
    }

    // ---------- 入口 ----------

    private static final Set<String> NUM = Set.of("int", "long", "short", "byte", "double", "float", "Integer",
            "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger", "Number");
    private static final Set<String> STR = Set.of("String", "char", "Character", "CharSequence");
    private static final Set<String> BOOL = Set.of("boolean", "Boolean");
    private static final Set<String> DATE = Set.of("Date", "LocalDateTime", "LocalDate", "LocalTime", "Timestamp",
            "Instant", "ZonedDateTime", "OffsetDateTime");
    private static final Set<String> ARR = Set.of("List", "ArrayList", "Set", "HashSet", "Collection");
    private static final Set<String> PRIMITIVE = Set.of("int", "long", "short", "byte", "double", "float",
            "boolean", "char");
    /** 框架注入的参数，不是客户端传的 */
    private static final Set<String> INJECTED = Set.of("HttpServletRequest", "HttpServletResponse", "HttpSession",
            "Principal", "Authentication", "BindingResult", "Errors", "Model", "ModelMap", "Locale", "TimeZone",
            "ZoneId", "WebRequest", "NativeWebRequest", "ServletRequest", "ServletResponse", "MultipartFile",
            "MultipartHttpServletRequest", "ServerWebExchange", "ServerHttpRequest", "ServerHttpResponse",
            "UriComponentsBuilder", "RedirectAttributes", "SessionStatus", "InputStream", "OutputStream", "Reader",
            "Writer", "Pageable", "Sort", "HttpHeaders", "HttpEntity", "RequestEntity");
    private static final Set<String> KNOWN_ANNOS = Set.of("RequestParam", "RequestBody", "PathVariable",
            "RequestHeader", "ModelAttribute", "Valid", "Validated", "NotNull", "NotBlank", "NotEmpty", "Min",
            "Max", "Range", "Size", "Length", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "DecimalMin", "DecimalMax", "Pattern", "Email", "ApiParam", "Parameter", "DateTimeFormat",
            "Nullable", "NonNull", "CookieValue", "RequestAttribute", "SessionAttribute");
    private static final Set<String> NOT_NULL_ANNOS = Set.of("NotNull", "NotBlank", "NotEmpty", "NonNull");
    private static final int MAX_UNITS = 16;
    private static final int MAX_DEPTH = 3;

    /** 一个入参（或入参对象的一个字段）和代码里指代它的写法 */
    private static final class Spec {
        String key;
        String in;
        String javaType;
        String type;
        boolean primitive;
        boolean frameworkRequired;
        String defaultValue;
        /** null / BODY / BIND / CONSTRAINT：哪种校验会生效 */
        String validKind;
        Map<String, String> annos = new LinkedHashMap<>();
        Set<String> aliases = new LinkedHashSet<>();
        String comment;
        String where;
        boolean dtoField;
    }

    /**
     * @param ctrlSrc Controller 整个文件
     * @param method  接口方法源码（{@link JavaSnippets#method} 的结果）
     */
    static List<Param> analyze(Repo repo, String controllerFile, String ctrlSrc, String method, String handler,
                               Map<String, Map<String, Code>> enums) {
        Advice advice = advice(repo, enums);
        int at = ctrlSrc.indexOf(method);
        int startLine = EndpointInsight.lineOf(ctrlSrc, at);
        String clean = JavaSnippets.blankLiterals(method);
        Matcher sig = Pattern.compile("\\b" + Pattern.quote(handler) + "\\s*\\(").matcher(clean);
        if (!sig.find()) {
            return List.of();
        }
        int open = clean.indexOf('(', sig.start());
        int close = matching(clean, open, '(', ')');
        if (close < 0) {
            return List.of();
        }
        String head = annotationsBefore(ctrlSrc, at, at + sig.start()) + method.substring(0, sig.start());
        boolean classValidated = classHead(ctrlSrc).contains("@Validated");
        String file = fileName(controllerFile);

        List<Spec> specs = new ArrayList<>();
        Set<String> dtoVars = new LinkedHashSet<>();
        for (int[] r : splitTop(clean, open + 1, close)) {
            String part = method.substring(r[0], r[1]);
            Decl d = decl(part);
            if (d == null || INJECTED.contains(d.simpleType)) {
                continue;
            }
            if (d.annos.keySet().stream().anyMatch(a -> !KNOWN_ANNOS.contains(a))
                    || d.annos.containsKey("PathVariable") || d.annos.containsKey("RequestHeader")
                    || d.annos.containsKey("CookieValue") || d.annos.containsKey("RequestAttribute")
                    || d.annos.containsKey("SessionAttribute")) {
                continue;
            }
            String where = file + ":" + (startLine + EndpointInsight.lineOf(method, r[0]) - 1);
            boolean valid = d.annos.containsKey("Valid") || d.annos.containsKey("Validated");
            String simple = typeOf(repo, d.simpleType, d.type);
            if (d.annos.containsKey("RequestBody")) {
                if (!"object".equals(simple)) {
                    continue;
                }
                dtoVars.add(d.name);
                specs.addAll(dtoSpecs(repo, d.simpleType, d.name, "body", valid ? "BODY" : null, 0));
                continue;
            }
            if ("object".equals(simple)) {
                dtoVars.add(d.name);
                specs.addAll(dtoSpecs(repo, d.simpleType, d.name, "query", valid ? "BIND" : null, 0));
                continue;
            }
            Spec s = new Spec();
            s.in = "query";
            s.key = d.name;
            s.javaType = d.type;
            s.type = simple;
            s.primitive = PRIMITIVE.contains(d.simpleType);
            s.annos.putAll(d.annos);
            s.aliases.add(d.name);
            s.where = where;
            String rp = d.annos.get("RequestParam");
            if (d.annos.containsKey("RequestParam")) {
                String n = annoAttr(rp, "value", "name");
                if (n != null && !n.isBlank()) {
                    s.key = n;
                }
                s.defaultValue = annoAttr(rp, "defaultValue");
                String req = annoRaw(rp, "required");
                s.frameworkRequired = !"false".equals(req) && s.defaultValue == null;
            }
            if (classValidated) {
                s.validKind = "CONSTRAINT";
            }
            s.comment = paramComment(head, d, s.key);
            specs.add(s);
        }
        if (specs.isEmpty()) {
            return List.of();
        }

        Walker w = new Walker(repo, enums, advice, specs, dtoVars);
        Unit root = new Unit(controllerFile, ctrlSrc, method, startLine, handler, 0, true);
        List<Ev> events = w.walk(root);

        List<Param> out = new ArrayList<>();
        for (Spec s : specs) {
            out.add(param(s, events, advice));
        }
        return out;
    }

    // ---------- 声明解析 ----------

    private record Decl(String name, String type, String simpleType, Map<String, String> annos) {
    }

    private static final Pattern ANNO = Pattern.compile("^\\s*@([\\w.]+)\\s*(\\(((?:[^()\"]|\"(?:[^\"\\\\]|\\\\.)*\")*)\\))?");

    /** 形参 {@code @RequestParam(value = "x") Integer x} */
    private static Decl decl(String part) {
        String s = part.strip();
        Map<String, String> annos = new LinkedHashMap<>();
        while (true) {
            Matcher m = ANNO.matcher(s);
            if (!m.find()) {
                break;
            }
            String n = m.group(1);
            annos.put(n.substring(n.lastIndexOf('.') + 1), m.group(3) == null ? "" : m.group(3));
            s = s.substring(m.end()).strip();
        }
        s = s.replaceFirst("^final\\s+", "").strip();
        Matcher nm = Pattern.compile("(\\w+)\\s*$").matcher(s);
        if (!nm.find()) {
            return null;
        }
        String type = s.substring(0, nm.start()).strip();
        if (type.isEmpty() || type.endsWith("...")) {
            return null;
        }
        return new Decl(nm.group(1), type, simpleName(type), annos);
    }

    static String simpleName(String type) {
        String t = type.replaceAll("<.*", "").replace("[]", "").strip();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    private static String typeOf(Repo repo, String simple, String full) {
        if (NUM.contains(simple)) {
            return "number";
        }
        if (STR.contains(simple)) {
            return "string";
        }
        if (BOOL.contains(simple)) {
            return "boolean";
        }
        if (DATE.contains(simple)) {
            return "date";
        }
        if (ARR.contains(simple) || full.endsWith("[]")) {
            return "array";
        }
        Path p = repo.find(simple);
        if (p != null) {
            String src = repo.read(p);
            if (Pattern.compile("\\benum\\s+" + Pattern.quote(simple) + "\\b").matcher(src).find()) {
                return "enum";
            }
            if (Pattern.compile("\\bclass\\s+" + Pattern.quote(simple) + "\\b").matcher(src).find()) {
                return "object";
            }
        }
        return "other";
    }

    private static final Pattern FIELD_DECL = Pattern.compile(
            "(?s)^(.*?)\\b(?:private|protected|public)\\s+(?!static\\b)(?:final\\s+)?(?:transient\\s+)?"
                    + "([\\w.]+(?:\\s*<.*>)?(?:\\[\\])?)\\s+(\\w+)\\s*(?:=\\s*(.*))?$");
    private static final Pattern JAVADOC = Pattern.compile("(?s)/\\*\\*(.*?)\\*/");

    private static List<Spec> dtoSpecs(Repo repo, String type, String var, String in, String validKind, int depth) {
        List<Spec> out = new ArrayList<>();
        Path p = repo.find(type);
        if (p == null || depth > 3) {
            return out;
        }
        String src = repo.read(p);
        String sup = JavaSnippets.superclass(src);
        if (sup != null && !sup.equals(type)) {
            out.addAll(dtoSpecs(repo, sup, var, in, validKind, depth + 1));
        }
        String body = EndpointInsight.classBody(src, type);
        int bodyAt = src.indexOf('{', Math.max(0, src.indexOf("class " + type)));
        int pos = 0;
        for (String seg : body.split(";", -1)) {
            int segAt = pos;
            pos += seg.length() + 1;
            int cut = seg.lastIndexOf('}');
            String s = (cut >= 0 ? seg.substring(cut + 1) : seg).stripTrailing();
            Matcher m = FIELD_DECL.matcher(s);
            if (!m.matches()) {
                continue;
            }
            String ftype = m.group(2).strip();
            String fname = m.group(3);
            String simple = simpleName(ftype);
            String kind = typeOf(repo, simple, ftype);
            if ("object".equals(kind) || "other".equals(kind)) {
                continue;
            }
            Spec f = new Spec();
            f.key = fname;
            f.in = in;
            f.javaType = ftype;
            f.type = kind;
            f.primitive = PRIMITIVE.contains(simple);
            f.validKind = validKind;
            f.dtoField = true;
            String pre = m.group(1);
            Matcher am = Pattern.compile("@(\\w+)\\s*(?:\\(((?:[^()\"]|\"[^\"]*\")*)\\))?").matcher(pre);
            while (am.find()) {
                f.annos.put(am.group(1), am.group(2) == null ? "" : am.group(2));
            }
            String cap = Character.toUpperCase(fname.charAt(0)) + fname.substring(1);
            f.aliases.add(var + ".get" + cap + "()");
            if (BOOL.contains(simple)) {
                f.aliases.add(var + ".is" + cap + "()");
            }
            f.aliases.add(var + "." + fname);
            String doc = annoAttr(f.annos.get("ApiModelProperty"), "value");
            if (doc == null) {
                doc = annoAttr(f.annos.get("Schema"), "description");
            }
            if (doc == null) {
                Matcher jd = JAVADOC.matcher(pre);
                String last = null;
                while (jd.find()) {
                    last = jd.group(1);
                }
                if (last != null) {
                    doc = last.replaceAll("(?m)^\\s*\\*\\s?", " ").replaceAll("\\s+", " ").strip();
                }
            }
            f.comment = doc == null || doc.isBlank() ? null : doc;
            int fieldIdx = segAt + m.start(3);
            f.where = p.getFileName() + ":" + EndpointInsight.lineOf(src, Math.max(0, bodyAt) + fieldIdx);
            out.removeIf(o -> o.key.equals(fname));
            out.add(f);
        }
        return out;
    }

    private static String paramComment(String head, Decl d, String key) {
        String c = annoAttr(d.annos.get("ApiParam"), "value");
        if (c == null) {
            c = annoAttr(d.annos.get("Parameter"), "description");
        }
        if (c == null) {
            Matcher m = Pattern.compile("@ApiImplicitParam\\s*\\(((?:[^()\"]|\"[^\"]*\")*)\\)").matcher(head);
            while (m.find()) {
                if (key.equals(annoAttr(m.group(1), "name"))) {
                    c = annoAttr(m.group(1), "value");
                    break;
                }
            }
        }
        return c == null || c.isBlank() ? null : c;
    }

    /** 注解里的字符串属性；单个裸字符串算 value */
    static String annoAttr(String args, String... names) {
        if (args == null) {
            return null;
        }
        for (String n : names) {
            Matcher m = Pattern.compile("\\b" + n + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(args);
            if (m.find()) {
                return m.group(1);
            }
        }
        if (List.of(names).contains("value")) {
            Matcher m = Pattern.compile("^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*(?:,|$)").matcher(args);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String annoRaw(String args, String name) {
        if (args == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*([^,)]+)").matcher(args);
        return m.find() ? m.group(1).strip() : null;
    }

    /** 数字型注解参数：@Min(1)、@Max(value = 200)、@Range(min = 1, max = 9) */
    private static Long annoNum(String args, String name) {
        if (args == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"?(-?\\d+)").matcher(args);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        if (name.equals("value")) {
            Matcher v = Pattern.compile("^\\s*\"?(-?\\d+)\"?\\s*(?:,|$)").matcher(args);
            if (v.find()) {
                return Long.parseLong(v.group(1));
            }
        }
        return null;
    }

    /**
     * 方法源码前面被截掉的注解：{@code @ApiImplicitParams({ … })} 里的大括号会让
     * {@link JavaSnippets#method} 以为上一个成员到这儿结束
     */
    private static String annotationsBefore(String src, int at, int sigAt) {
        if (at <= 0) {
            return "";
        }
        String clean = JavaSnippets.blankLiterals(src);
        int depth = 0;
        int i = sigAt - 1;
        for (; i >= 0; i--) {
            char c = clean.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
            } else if (depth == 0 && (c == ';' || c == '}' || c == '{')) {
                break;
            }
        }
        return i + 1 < at ? src.substring(i + 1, at) : "";
    }

    private static String classHead(String src) {
        Matcher m = Pattern.compile("\\bclass\\s+\\w+").matcher(JavaSnippets.blankLiterals(src));
        return m.find() ? src.substring(0, m.start()) : "";
    }

    // ---------- 读代码 ----------

    /** 一段要读的方法体；callee 的形参已经换成调用处的实参写法 */
    private record Unit(String file, String fileSrc, String text, int startLine, String method, int depth,
                        boolean returnPath) {
        String where(int offset) {
            return fileName(file) + ":" + (startLine + EndpointInsight.lineOf(text, offset) - 1);
        }
    }

    /**
     * @param type NULL（不传时的结局）/ GUARD（判了非空才用）/ DEFAULT / DEREF（没判空就用）/
     *             BOUND（比较后报错/返回空）/ VALUE（认的取值）/ OTHER（取值都不认时的结局）
     */
    private record Ev(String key, String type, Outcome outcome, String op, Long k, boolean failWhenTrue,
                      String rule, String where) {
    }

    private static final Pattern IF = Pattern.compile("\\bif\\s*\\(");
    private static final Pattern SWITCH = Pattern.compile("\\bswitch\\s*\\(");
    private static final Pattern CALL = Pattern.compile("(?<![\\w.])(?:(\\w+)\\s*\\.\\s*)?([a-z]\\w*)\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "synchronized", "throw", "try", "else", "do", "super", "this", "assert");
    private static final Pattern EMPTY_RET = Pattern.compile(
            "^\\s*(null|new\\s+[A-Z]\\w*(?:<[^>;]*>)?\\s*\\(\\s*\\)|Collections\\.empty\\w*\\(\\s*\\)"
                    + "|Lists\\.newArrayList\\(\\s*\\)|List\\.of\\(\\s*\\)|Optional\\.empty\\(\\s*\\)"
                    + "|new\\s+[A-Z]\\w*\\[0])\\s*$");
    private static final Pattern THROW = Pattern.compile("\\bthrow\\s+new\\s+([A-Z]\\w*)\\s*\\(");
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b([^;]*);");
    private static final Pattern HELPER = Pattern.compile(
            "(?i).*(Validator|Validate|Validation|Check(?:er|s)?|Assert\\w*|Utils?|Helper|Preconditions?|Verif\\w*)");

    private static final class Walker {
        final Repo repo;
        final Map<String, Map<String, Code>> enums;
        final Advice advice;
        final List<Spec> specs;
        final Set<String> dtoVars;
        int units;
        final Set<String> visited = new HashSet<>();

        Walker(Repo repo, Map<String, Map<String, Code>> enums, Advice advice, List<Spec> specs, Set<String> dtoVars) {
            this.repo = repo;
            this.enums = enums;
            this.advice = advice;
            this.specs = specs;
            this.dtoVars = dtoVars;
        }

        private record Site(int at, List<Ev> evs) {
        }

        List<Ev> walk(Unit u) {
            units++;
            currentSources.add(0, u.fileSrc());
            try {
                return walkUnit(u);
            } finally {
                currentSources.remove(0);
            }
        }

        private List<Ev> walkUnit(Unit u) {
            String text = u.text();
            String clean = JavaSnippets.blankLiterals(text);
            int bodyAt = bodyStart(clean, u.method());
            if (bodyAt < 0) {
                return List.of();
            }
            Map<String, String> statics = staticImports(u.fileSrc());
            Map<String, String> alias = new LinkedHashMap<>();
            for (Spec s : specs) {
                Set<String> exprs = new LinkedHashSet<>(s.aliases);
                localAliases(clean, bodyAt, exprs);
                alias.put(s.key, aliasRegex(exprs));
            }
            List<Site> sites = new ArrayList<>();

            Matcher im = IF.matcher(clean);
            while (im.find()) {
                if (im.start() < bodyAt) {
                    continue;
                }
                List<Ev> evs = ifSite(u, text, clean, im, alias, statics);
                if (!evs.isEmpty()) {
                    sites.add(new Site(im.start(), evs));
                }
            }
            Matcher sm = SWITCH.matcher(clean);
            while (sm.find()) {
                if (sm.start() >= bodyAt) {
                    List<Ev> evs = switchSite(u, text, clean, sm, alias, statics);
                    if (!evs.isEmpty()) {
                        sites.add(new Site(sm.start(), evs));
                    }
                }
            }
            for (Spec s : specs) {
                for (Pattern d : defaultPatterns(alias.get(s.key))) {
                    Matcher m = d.matcher(clean);
                    if (m.find(bodyAt)) {
                        sites.add(new Site(m.start(), List.of(new Ev(s.key, "DEFAULT",
                                outcome(DEFAULT, null, "代码里兜了默认值：" + snippet(text, m.start(), m.end()),
                                        u.where(m.start()), false), null, null, false, null, u.where(m.start())))));
                        break;
                    }
                }
                Matcher g = Pattern.compile(guardRegex(alias.get(s.key))).matcher(clean);
                g.region(bodyAt, clean.length());
                while (g.find()) {
                    if (insideIfCond(clean, g.start())) {
                        sites.add(new Site(g.start(), List.of(new Ev(s.key, "GUARD", null, null, null, false, null,
                                u.where(g.start())))));
                        break;
                    }
                }
                if (!s.primitive && !"array".equals(s.type)) {
                    int use = derefAt(clean, bodyAt, alias.get(s.key), s.type);
                    if (use >= 0 && !guardedBefore(clean, bodyAt, use, alias.get(s.key))) {
                        int end = use;
                        while (end < text.length() && end - use < 60 && text.charAt(end) != ';'
                                && text.charAt(end) != '\n' && text.charAt(end) != '{') {
                            end++;
                        }
                        sites.add(new Site(use, List.of(new Ev(s.key, "DEREF", null, null, null, false,
                                squash(text.substring(use, end)), u.where(use)))));
                    }
                }
            }
            if (u.depth() < MAX_DEPTH) {
                Matcher cm = CALL.matcher(clean);
                while (cm.find() && units < MAX_UNITS) {
                    if (cm.start() < bodyAt || KEYWORDS.contains(cm.group(2))
                            || (cm.group(1) != null && KEYWORDS.contains(cm.group(1)) && !"this".equals(cm.group(1)))) {
                        continue;
                    }
                    List<Ev> evs = callSite(u, text, clean, cm, alias, statics);
                    if (!evs.isEmpty()) {
                        sites.add(new Site(cm.start(), evs));
                    }
                }
            }
            sites.sort((x, y) -> Integer.compare(x.at(), y.at()));
            List<Ev> out = new ArrayList<>();
            sites.forEach(s -> out.addAll(s.evs()));
            return out;
        }

        // ----- if -----

        private List<Ev> ifSite(Unit u, String text, String clean, Matcher im, Map<String, String> alias,
                                Map<String, String> statics) {
            int open = clean.indexOf('(', im.start());
            int close = matching(clean, open, '(', ')');
            if (close < 0) {
                return List.of();
            }
            String cond = clean.substring(open + 1, close);
            String condText = text.substring(open + 1, close);
            int[] br = branch(clean, close + 1);
            Outcome fail = br == null ? null : firstExit(u, text, clean, br[0], br[1], statics);
            String where = u.where(im.start());
            List<Ev> out = new ArrayList<>();
            for (Spec s : specs) {
                String a = alias.get(s.key);
                if (!Pattern.compile(a).matcher(cond).find()) {
                    continue;
                }
                List<String[]> dis = disjuncts(cond, condText);
                boolean nullHit = false;
                for (String[] d : dis) {
                    nullHit |= missingFails(d[0], a);
                }
                if (nullHit && fail != null) {
                    Outcome o = withDetail(fail, "if (" + squash(condText) + ") → " + fail.detail());
                    out.add(new Ev(s.key, "NULL", new Outcome(o.kind(), o.code(), o.codeName(), o.message(),
                            o.returns(), o.detail(), where, o.suspect()), null, null, false, null, where));
                } else if (nullHit && br != null && assigns(clean, br, a)) {
                    out.add(new Ev(s.key, "DEFAULT", outcome(DEFAULT, null, "不传时在 if 里赋了默认值", where, false),
                            null, null, false, null, where));
                }
                boolean failing = fail != null && !fail.kind().equals(SKIP);
                if (failing) {
                    for (String[] d : dis) {
                        bounds(s, a, d[0], d[1], fail, where, out);
                    }
                } else {
                    List<Long> vals = equalities(cond, a);
                    if (!vals.isEmpty() && br != null) {
                        for (Long v : vals) {
                            out.add(new Ev(s.key, "VALUE", null, "==", v, false, null, where));
                        }
                        Outcome other = chainEnd(u, text, clean, br[1], statics);
                        if (other != null) {
                            out.add(new Ev(s.key, "OTHER", other, null, null, false, null, other.where()));
                        }
                    }
                }
            }
            return out;
        }

        /** 不等式一串（{@code type != 1 && type != 2} 抛错）也算「认的取值」 */
        private void bounds(Spec s, String a, String d, String dText, Outcome fail, String where, List<Ev> out) {
            String inner = d.strip();
            boolean negated = false;
            if (inner.startsWith("!")) {
                String rest = inner.substring(1).strip();
                if (rest.startsWith("(") && matching(rest, 0, '(', ')') == rest.length() - 1) {
                    inner = rest.substring(1, rest.length() - 1);
                    negated = true;
                }
            }
            List<String> parts = negated ? splitOp(inner, "&&") : inner.contains("&&") ? splitNe(inner) : List.of(inner);
            if (parts == null) {
                return;
            }
            for (String c : parts) {
                Matcher m = Pattern.compile(a + "\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+|[A-Z][A-Za-z0-9_.]*)").matcher(c);
                Matcher r = Pattern.compile("(-?\\d+|[A-Z][A-Za-z0-9_.]*)\\s*(>=|<=|==|!=|>|<)\\s*" + a).matcher(c);
                String op;
                String lit;
                if (m.find()) {
                    op = m.group(1);
                    lit = m.group(2);
                } else if (r.find()) {
                    op = flip(r.group(2));
                    lit = r.group(1);
                } else {
                    continue;
                }
                Long k = number(lit, s);
                if (k == null) {
                    continue;
                }
                if (op.equals("!=") && !negated) {
                    out.add(new Ev(s.key, "VALUE", null, "==", k, false, null, where));
                    out.add(new Ev(s.key, "OTHER", fail, null, null, false, null, where));
                    continue;
                }
                if (op.equals("==") && negated) {
                    out.add(new Ev(s.key, "VALUE", null, "==", k, false, null, where));
                    out.add(new Ev(s.key, "OTHER", fail, null, null, false, null, where));
                    continue;
                }
                String rule = squash(c.strip());
                out.add(new Ev(s.key, "BOUND", fail, op, k, !negated, negated ? "要求 " + rule : rule, where));
            }
        }

        private Long number(String lit, Spec s) {
            Matcher n = NUM_LITERAL.matcher(lit);
            if (n.matches()) {
                return Long.parseLong(n.group(1));
            }
            return constValue(lit);
        }

        /** 常量值：同文件 {@code NAME = 200;} 或 {@code Cls.NAME} */
        private Long constValue(String lit) {
            String cls = null;
            String name = lit;
            int dot = lit.lastIndexOf('.');
            if (dot > 0) {
                cls = lit.substring(0, dot);
                cls = cls.substring(cls.lastIndexOf('.') + 1);
                name = lit.substring(dot + 1);
            }
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(-?\\d+)[lL]?\\s*;");
            List<String> srcs = new ArrayList<>();
            if (cls != null) {
                srcs.add(repo.read(repo.find(cls)));
            } else {
                srcs.addAll(currentSources);
            }
            for (String src : srcs) {
                Matcher m = p.matcher(src);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
            return null;
        }

        final List<String> currentSources = new ArrayList<>();

        // ----- switch -----

        private List<Ev> switchSite(Unit u, String text, String clean, Matcher sm, Map<String, String> alias,
                                    Map<String, String> statics) {
            int open = clean.indexOf('(', sm.start());
            int close = matching(clean, open, '(', ')');
            int bo = close < 0 ? -1 : clean.indexOf('{', close);
            int be = bo < 0 ? -1 : matching(clean, bo, '{', '}');
            if (be < 0) {
                return List.of();
            }
            String subject = clean.substring(open + 1, close);
            List<Ev> out = new ArrayList<>();
            for (Spec s : specs) {
                if (!Pattern.compile("^\\s*" + alias.get(s.key) + "\\s*$").matcher(subject).find()) {
                    continue;
                }
                String where = u.where(sm.start());
                Matcher c = Pattern.compile("\\bcase\\s+(-?\\d+)\\s*[:\\-]").matcher(clean.substring(bo, be));
                while (c.find()) {
                    out.add(new Ev(s.key, "VALUE", null, "==", Long.parseLong(c.group(1)), false, null, where));
                }
                Matcher d = Pattern.compile("\\bdefault\\s*(?::|->)").matcher(clean);
                d.region(bo, be);
                if (d.find()) {
                    int stEnd = be;
                    Outcome o = firstExit(u, text, clean, d.end(), stEnd, statics);
                    if (o != null && !o.kind().equals(SKIP)) {
                        out.add(new Ev(s.key, "OTHER", o, null, null, false, null, o.where()));
                    }
                }
                if (!s.primitive) {
                    out.add(new Ev(s.key, "DEREF", null, null, null, false, "switch (" + squash(subject) + ")", where));
                }
            }
            return out;
        }

        // ----- 调用展开 -----

        private List<Ev> callSite(Unit u, String text, String clean, Matcher cm, Map<String, String> alias,
                                  Map<String, String> statics) {
            String recv = cm.group(1);
            String name = cm.group(2);
            int open = clean.indexOf('(', cm.end() - 1);
            int close = matching(clean, open, '(', ')');
            if (close < 0) {
                return List.of();
            }
            if (recv != null && (dtoVars.contains(recv) || Character.isLowerCase(recv.charAt(0))
                    && specs.stream().anyMatch(s -> s.aliases.contains(recv)))) {
                return List.of();
            }
            List<int[]> argRanges = splitTop(clean, open + 1, close);
            if (name.matches("set[A-Z]\\w*") && argRanges.size() == 1
                    || name.matches("put|putAll|add|addAll|append|contains|equals|format|valueOf|println|info|warn"
                    + "|debug|error|trace")) {
                return List.of();
            }
            List<String> args = new ArrayList<>();
            boolean relevant = false;
            for (int[] r : argRanges) {
                String a = text.substring(r[0], r[1]).strip();
                args.add(a);
                String ac = clean.substring(r[0], r[1]);
                if (dtoVars.stream().anyMatch(v -> Pattern.compile("(?<![\\w.])" + Pattern.quote(v) + "\\b").matcher(ac).find())
                        || alias.values().stream().anyMatch(x -> Pattern.compile(x).matcher(ac).find())) {
                    relevant = true;
                }
            }
            if (!relevant) {
                return List.of();
            }
            String where = u.where(cm.start());
            List<Ev> builtin = builtinValidator(recv, name, args, argRanges, clean, alias, where);
            if (builtin != null) {
                return builtin;
            }
            Path target = resolveTarget(u, recv, name, statics);
            if (target == null) {
                return List.of();
            }
            String tsrc = repo.read(target);
            int[] decl = methodDecl(tsrc, name, args);
            if (decl == null) {
                return List.of();
            }
            String key = repo.rel(target) + "#" + name + "#" + String.join(",", args);
            if (!visited.add(key)) {
                return List.of();
            }
            String body = tsrc.substring(decl[0], decl[2] + 1);
            String bodyClean = JavaSnippets.blankLiterals(body);
            int so = decl[1] - decl[0];
            int sc = matching(bodyClean, so, '(', ')');
            List<String> names = new ArrayList<>();
            for (int[] r : splitTop(bodyClean, so + 1, sc)) {
                Decl d = decl(body.substring(r[0], r[1]));
                names.add(d == null ? null : d.name());
            }
            if (names.size() != args.size()) {
                return List.of();
            }
            String inlined = substitute(body, bodyClean, sc, names, args);
            boolean rp = u.returnPath() && onReturnPath(clean, cm.start(), close);
            Unit callee = new Unit(repo.rel(target), tsrc, inlined,
                    EndpointInsight.lineOf(tsrc, decl[0]), name, u.depth() + 1, rp);
            List<Ev> evs = walk(callee);
            String cls = target.getFileName().toString().replace(".java", "");
            if (!HELPER.matcher(cls).matches()) {
                return evs;
            }
            // 校验工具里的那行对谁都一样，人要看的是哪个接口在哪一行调了它
            String via = cls + "." + name + "()";
            List<Ev> out = new ArrayList<>();
            for (Ev e : evs) {
                Outcome o = e.outcome() == null ? null : new Outcome(e.outcome().kind(), e.outcome().code(),
                        e.outcome().codeName(), e.outcome().message(), e.outcome().returns(),
                        via + " 里 " + e.outcome().detail() + "（" + e.outcome().where() + "）", where,
                        e.outcome().suspect());
                out.add(new Ev(e.key(), e.type(), o, e.op(), e.k(), e.failWhenTrue(), e.rule(), where));
            }
            return out;
        }

        /** Objects.requireNonNull / Spring Assert / Guava Preconditions */
        private List<Ev> builtinValidator(String recv, String name, List<String> args, List<int[]> ranges,
                                          String clean, Map<String, String> alias, String where) {
            if (recv == null || args.isEmpty()) {
                return null;
            }
            List<String> chain;
            boolean nullCheck;
            if (recv.equals("Objects") && name.equals("requireNonNull")
                    || recv.equals("Preconditions") && name.equals("checkNotNull")) {
                chain = NPE;
                nullCheck = true;
            } else if ((recv.equals("Assert") || recv.equals("Validate"))
                    && name.matches("notNull|hasText|hasLength|notEmpty|notBlank")) {
                chain = ILLEGAL_ARG;
                nullCheck = true;
            } else if ((recv.equals("Assert") || recv.equals("Preconditions") || recv.equals("Validate"))
                    && name.matches("isTrue|checkArgument|state|checkState")) {
                chain = name.matches("state|checkState") ? ILLEGAL_STATE : ILLEGAL_ARG;
                nullCheck = false;
            } else {
                return null;
            }
            String first = clean.substring(ranges.get(0)[0], ranges.get(0)[1]);
            Matcher msg = STR_LITERAL.matcher(args.size() > 1 ? args.get(1) : "");
            String message = msg.find() ? msg.group(1) : null;
            Outcome fail = fromChain(advice, chain, 400, recv + "." + name + " 不通过抛异常", where, false, message);
            List<Ev> out = new ArrayList<>();
            for (Spec s : specs) {
                String a = alias.get(s.key);
                if (nullCheck && Pattern.compile("^\\s*" + a + "\\s*$").matcher(first).find()) {
                    out.add(new Ev(s.key, "NULL", fail, null, null, false, null, where));
                } else if (!nullCheck && Pattern.compile(a).matcher(first).find()) {
                    String neg = "!(" + first + ")";
                    for (String[] d : disjuncts(neg, neg)) {
                        if (missingFails(d[0], a)) {
                            out.add(new Ev(s.key, "NULL", fail, null, null, false, null, where));
                        }
                        bounds(s, a, d[0], d[1], fail, where, out);
                    }
                }
            }
            return out;
        }

        private Path resolveTarget(Unit u, String recv, String name, Map<String, String> statics) {
            if (recv == null || recv.equals("this")) {
                if (methodExists(u.fileSrc(), name)) {
                    return repo.find(fileName(u.file()).replace(".java", ""));
                }
                String cls = statics.get(name);
                return cls == null ? null : classFile(cls);
            }
            if (Character.isUpperCase(recv.charAt(0))) {
                return classFile(recv);
            }
            String type = JavaSnippets.fieldType(u.fileSrc(), recv);
            if (type == null) {
                return null;
            }
            Path impl = repo.find(type + "Impl");
            return impl != null ? impl : classFile(type);
        }

        private Path classFile(String cls) {
            Path p = repo.find(cls);
            if (p != null && Pattern.compile("\\b(?:class|interface)\\s+" + Pattern.quote(cls) + "\\b")
                    .matcher(repo.read(p)).find()) {
                return p;
            }
            return null;
        }

        // ----- 分支结局 -----

        /** 分支里第一个顶层出口：throw / return；没有返回 null */
        private Outcome firstExit(Unit u, String text, String clean, int from, int to,
                                  Map<String, String> statics) {
            int depth = 0;
            for (int i = from; i < to; i++) {
                char c = clean.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
                if (depth > 0) {
                    continue;
                }
                Matcher t = THROW.matcher(clean);
                t.region(i, to);
                if (t.lookingAt()) {
                    return throwOutcome(u, text, clean, t, statics);
                }
                Matcher r = RETURN.matcher(clean);
                r.region(i, to);
                if (r.lookingAt()) {
                    return returnOutcome(u, text, r, statics);
                }
            }
            return null;
        }

        private Outcome throwOutcome(Unit u, String text, String clean, Matcher t, Map<String, String> statics) {
            String ex = t.group(1);
            int open = clean.indexOf('(', t.end() - 1);
            int close = matching(clean, open, '(', ')');
            String where = u.where(t.start());
            if (close < 0) {
                return null;
            }
            String argsClean = clean.substring(open + 1, close);
            String argsText = text.substring(open + 1, close);
            Matcher sl = STR_LITERAL.matcher(argsText);
            String msg = sl.find() ? sl.group(1) : null;
            List<int[]> rs = splitTop(clean, open + 1, close);
            if (!rs.isEmpty()) {
                String first = clean.substring(rs.get(0)[0], rs.get(0)[1]);
                Code c = firstCode(repo, enums, first, statics, where);
                if (c == null) {
                    Matcher n = NUM_LITERAL.matcher(first);
                    if (n.matches()) {
                        c = new Code(null, n.group(1), msg, where);
                    }
                }
                if (c != null) {
                    Advice.Hit h = advice.resolve(List.of(ex));
                    if (h != null && !h.passthrough() && h.code() != null) {
                        c = h.code();
                    }
                    String m = msg != null ? msg : c.message();
                    return new Outcome(REJECT, c.code(), c.name(), m, null,
                            "抛 " + ex + "(" + label(c) + ")", where, false);
                }
            }
            Advice.Hit h = advice.resolve(exChain(ex));
            if (h != null && h.passthrough()) {
                return new Outcome(REJECT, null, null, msg, null,
                        "抛 " + ex + (msg == null ? "" : "(\"" + msg + "\")") + "，码由异常自己带", where, false);
            }
            Outcome o = fromChain(advice, exChain(ex), 500, "抛 " + ex, where, false, msg);
            return o;
        }

        private Outcome returnOutcome(Unit u, String text, Matcher r, Map<String, String> statics) {
            String expr = text.substring(r.start(1), r.end(1)).strip();
            String exprClean = JavaSnippets.blankLiterals(expr);
            String where = u.where(r.start());
            Matcher e = EMPTY_RET.matcher(exprClean);
            if (e.matches()) {
                String what = e.group(1).equals("null") || e.group(1).startsWith("Optional") ? "null"
                        : e.group(1).matches("(?s).*(List|Set|empty|\\[0]).*") ? "emptyList" : "emptyObject";
                if (!u.returnPath()) {
                    return new Outcome(SKIP, null, null, null, null,
                            u.method() + "() 提前 return " + e.group(1) + "，主流程照常返回", where, false);
                }
                return new Outcome(SILENT, null, null, null, what,
                        "直接 return " + e.group(1) + "，不报错", where, true);
            }
            Code c = firstCode(repo, enums, exprClean, statics, where);
            if (c != null) {
                return new Outcome(REJECT, c.code(), c.name(), c.message(), null, "返回 " + label(c), where, false);
            }
            if (expr.isEmpty()) {
                return new Outcome(SKIP, null, null, null, null, u.method() + "() 提前 return，主流程照常返回", where, false);
            }
            return new Outcome(SKIP, null, null, null, null, "提前 return " + squash(expr), where, false);
        }

        /** if/else if 链结束后：else 分支或紧跟的 return */
        private Outcome chainEnd(Unit u, String text, String clean, int branchEnd, Map<String, String> statics) {
            int i = afterBranch(clean, branchEnd);
            while (true) {
                int j = skipWs(clean, i);
                if (!clean.startsWith("else", j)) {
                    break;
                }
                int k = skipWs(clean, j + 4);
                if (clean.startsWith("if", k)) {
                    int open = clean.indexOf('(', k);
                    int close = matching(clean, open, '(', ')');
                    int[] br = close < 0 ? null : branch(clean, close + 1);
                    if (br == null) {
                        return null;
                    }
                    i = afterBranch(clean, br[1]);
                    continue;
                }
                int[] br = branch(clean, k);
                if (br == null) {
                    return null;
                }
                Outcome o = firstExit(u, text, clean, br[0], br[1], statics);
                return o == null || o.kind().equals(SKIP) ? null : o;
            }
            int j = skipWs(clean, i);
            int end = j;
            while (end < clean.length() && clean.charAt(end) != ';' && clean.charAt(end) != '}') {
                end++;
            }
            Outcome o = firstExit(u, text, clean, j, Math.min(clean.length(), end + 1), statics);
            return o == null || o.kind().equals(SKIP) ? null : o;
        }
    }

    // ---------- 结论 ----------

    private static Param param(Spec s, List<Ev> events, Advice advice) {
        List<String> warns = new ArrayList<>();
        List<Ev> mine = events.stream().filter(e -> e.key().equals(s.key)).toList();
        List<Bound> bounds = new ArrayList<>();
        Set<String> boundValues = new HashSet<>();

        List<String> chain = "BODY".equals(s.validKind) ? BODY_INVALID : "BIND".equals(s.validKind) ? BIND
                : CONSTRAINT;
        String notNull = s.annos.keySet().stream().filter(NOT_NULL_ANNOS::contains).findFirst().orElse(null);
        boolean annoBounds = s.annos.keySet().stream().anyMatch(a -> a.matches(
                "Min|Max|Range|Positive|PositiveOrZero|Negative|NegativeOrZero|DecimalMin|DecimalMax"));
        if ((notNull != null || annoBounds) && s.validKind == null) {
            warns.add("写了 @" + (notNull != null ? notNull : s.annos.keySet().stream().filter(a -> a.matches(
                    "Min|Max|Range|Positive\\w*|Negative\\w*|Decimal\\w+")).findFirst().orElse("校验注解"))
                    + "，但" + (s.dtoField ? "入参对象没加 @Valid" : "Controller 类没加 @Validated") + "，不生效");
        }
        if (s.validKind != null && annoBounds) {
            for (String[] b : annoBounds(s)) {
                if (boundValues.add(b[0])) {
                    bounds.add(new Bound(b[0], b[1], fromChain(advice, chain, 400, b[1] + " 校验不通过", s.where, false,
                            annoAttr(s.annos.get(b[2]), "message"))));
                }
            }
        }
        for (Ev e : mine) {
            if (!"BOUND".equals(e.type()) || e.outcome() == null) {
                continue;
            }
            Long v = violating(e.op(), e.k(), e.failWhenTrue());
            if (v != null && boundValues.add(String.valueOf(v))) {
                bounds.add(new Bound(String.valueOf(v), e.rule(), e.outcome()));
            }
        }

        Outcome missing = null;
        if (s.defaultValue != null) {
            missing = outcome(DEFAULT, null, "@RequestParam 默认值 " + s.defaultValue, s.where, false);
        } else if (s.frameworkRequired) {
            missing = fromChain(advice, MISSING_PARAM, 400, "@RequestParam 必填，Spring 直接抛 "
                    + "MissingServletRequestParameterException", s.where, false, null);
        } else if (s.validKind != null && notNull != null) {
            missing = fromChain(advice, chain, 400, "@" + notNull + " 校验不通过", s.where, false,
                    annoAttr(s.annos.get(notNull), "message"));
        } else if (s.primitive && !s.dtoField) {
            missing = fromChain(advice, ILLEGAL_STATE, 500, "基本类型 " + s.javaType
                    + " 参数不传，Spring 转不成 null 抛 IllegalStateException", s.where, true, null);
        } else if (s.primitive) {
            long zero = 0;
            for (Ev e : mine) {
                if ("BOUND".equals(e.type()) && e.outcome() != null && violates(e.op(), e.k(), e.failWhenTrue(), zero)) {
                    missing = withDetail(e.outcome(), "基本类型字段不传按 0 处理，命中 " + e.rule() + " → "
                            + e.outcome().detail());
                    break;
                }
            }
            if (missing == null) {
                missing = outcome(DEFAULT, null, "基本类型字段，不传按 " + ("boolean".equals(s.type) ? "false" : "0")
                        + " 处理", s.where, false);
            }
        } else {
            for (Ev e : mine) {
                switch (e.type()) {
                    case "NULL" -> missing = e.outcome();
                    case "GUARD" -> missing = outcome(OPTIONAL, null, "判了非空才用（" + e.where() + "），不传就跳过那段",
                            e.where(), false);
                    case "DEFAULT" -> missing = e.outcome();
                    case "DEREF" -> missing = fromChain(advice, NPE, 500, "没判空就用（" + e.rule() + "），不传会空指针",
                            e.where(), true, null);
                    default -> {
                    }
                }
                if (missing != null) {
                    break;
                }
            }
        }

        Outcome badType = null;
        if ("query".equals(s.in) && List.of("number", "boolean", "date", "enum").contains(s.type)) {
            badType = s.dtoField
                    ? fromChain(advice, BIND, 400, "传 abc 绑定到 " + s.javaType + " 失败，抛 BindException", s.where,
                    false, null)
                    : fromChain(advice, TYPE_MISMATCH, 400, "传 abc 转不成 " + s.javaType
                    + "，抛 MethodArgumentTypeMismatchException", s.where, false, null);
        } else if ("body".equals(s.in) && List.of("number", "boolean", "date", "enum").contains(s.type)) {
            badType = fromChain(advice, NOT_READABLE, 400, "JSON 里传 \"abc\" 反序列化成 " + s.javaType
                    + " 失败，抛 HttpMessageNotReadableException", s.where, false, null);
        }

        List<String> values = new ArrayList<>();
        Outcome unsupported = null;
        for (Ev e : mine) {
            if ("VALUE".equals(e.type()) && !values.contains(String.valueOf(e.k()))) {
                values.add(String.valueOf(e.k()));
            } else if ("OTHER".equals(e.type()) && unsupported == null && e.outcome() != null) {
                unsupported = e.outcome();
            }
        }
        if (values.isEmpty()) {
            unsupported = null;
        }
        boolean required = s.frameworkRequired || (s.validKind != null && notNull != null);
        return new Param(s.key, s.in, s.javaType, s.type, required, s.comment, s.where, missing, badType,
                bounds, values, unsupported, warns);
    }

    /** {value, 规则, 注解名} */
    private static List<String[]> annoBounds(Spec s) {
        List<String[]> out = new ArrayList<>();
        Long min = annoNum(s.annos.get("Min"), "value");
        if (min != null) {
            out.add(new String[]{String.valueOf(min - 1), "@Min(" + min + ")", "Min"});
        }
        Long max = annoNum(s.annos.get("Max"), "value");
        if (max != null) {
            out.add(new String[]{String.valueOf(max + 1), "@Max(" + max + ")", "Max"});
        }
        if (s.annos.containsKey("Range")) {
            Long lo = annoNum(s.annos.get("Range"), "min");
            Long hi = annoNum(s.annos.get("Range"), "max");
            if (lo != null) {
                out.add(new String[]{String.valueOf(lo - 1), "@Range(min=" + lo + ")", "Range"});
            }
            if (hi != null) {
                out.add(new String[]{String.valueOf(hi + 1), "@Range(max=" + hi + ")", "Range"});
            }
        }
        if (s.annos.containsKey("Positive")) {
            out.add(new String[]{"0", "@Positive", "Positive"});
        }
        if (s.annos.containsKey("PositiveOrZero")) {
            out.add(new String[]{"-1", "@PositiveOrZero", "PositiveOrZero"});
        }
        Long dmin = annoNum(s.annos.get("DecimalMin"), "value");
        if (dmin != null) {
            out.add(new String[]{String.valueOf(dmin - 1), "@DecimalMin(" + dmin + ")", "DecimalMin"});
        }
        Long dmax = annoNum(s.annos.get("DecimalMax"), "value");
        if (dmax != null) {
            out.add(new String[]{String.valueOf(dmax + 1), "@DecimalMax(" + dmax + ")", "DecimalMax"});
        }
        return out;
    }

    /** 让条件「失败」的那个值：failWhenTrue 时让比较成立，否则让它不成立 */
    static Long violating(String op, long k, boolean failWhenTrue) {
        if (failWhenTrue) {
            return switch (op) {
                case ">" -> k + 1;
                case ">=", "<=", "==" -> k;
                case "<" -> k - 1;
                case "!=" -> k + 1;
                default -> null;
            };
        }
        return switch (op) {
            case ">" -> k;
            case ">=" -> k - 1;
            case "<" -> k;
            case "<=" -> k + 1;
            case "==" -> k + 1;
            case "!=" -> k;
            default -> null;
        };
    }

    static boolean violates(String op, long k, boolean failWhenTrue, long v) {
        boolean holds = switch (op) {
            case ">" -> v > k;
            case ">=" -> v >= k;
            case "<" -> v < k;
            case "<=" -> v <= k;
            case "==" -> v == k;
            case "!=" -> v != k;
            default -> false;
        };
        return failWhenTrue == holds;
    }

    private static Outcome fromChain(Advice advice, List<String> chain, int httpDefault, String what, String where,
                                     boolean crash, String message) {
        Advice.Hit h = advice.resolve(chain);
        if (h == null) {
            return new Outcome(HTTP, String.valueOf(httpDefault), "HTTP", message, null,
                    what + "，没有全局异常处理接住，Spring 回 HTTP " + httpDefault, where, crash);
        }
        if (h.passthrough()) {
            return new Outcome(crash ? CRASH : REJECT, null, null, message, null,
                    what + "，" + h.exception() + " 处理（" + h.where() + "）原样返回异常里的码", where, crash);
        }
        Code c = h.code();
        String detail = what + "，" + (h.fallback() ? "落到兜底的 @ExceptionHandler(" + h.exception() + ")"
                : "@ExceptionHandler(" + h.exception() + ")") + "（" + h.where() + "）返回 " + label(c);
        return new Outcome(crash ? CRASH : REJECT, c.code(), c.name(), message != null ? message : c.message(), null,
                detail, where, crash || h.fallback());
    }

    private static List<String> exChain(String ex) {
        List<String> out = new ArrayList<>();
        out.add(ex);
        switch (ex) {
            case "NullPointerException", "IllegalArgumentException", "IllegalStateException",
                 "UnsupportedOperationException", "ArithmeticException", "IndexOutOfBoundsException" ->
                    out.add("RuntimeException");
            default -> {
                if (!ex.equals("RuntimeException") && !ex.equals("Exception")) {
                    out.add("RuntimeException");
                }
            }
        }
        out.add("Exception");
        out.add("Throwable");
        return out;
    }

    private static Outcome outcome(String kind, Code c, String detail, String where, boolean suspect) {
        return new Outcome(kind, c == null ? null : c.code(), c == null ? null : c.name(),
                c == null ? null : c.message(), null, detail, where, suspect);
    }

    private static Outcome withDetail(Outcome o, String detail) {
        return new Outcome(o.kind(), o.code(), o.codeName(), o.message(), o.returns(), detail, o.where(), o.suspect());
    }

    static String label(Code c) {
        if (c == null) {
            return "";
        }
        if (c.name() == null) {
            return c.code();
        }
        return c.name() + (c.code() == null ? "" : " " + c.code());
    }

    // ---------- 文本工具 ----------

    private static final Pattern GETTER_EXPR = Pattern.compile("^(\\w+)\\.(\\w+)\\(\\)$");

    static String aliasRegex(Collection<String> exprs) {
        List<String> parts = new ArrayList<>();
        for (String e : exprs) {
            Matcher g = GETTER_EXPR.matcher(e);
            if (g.matches()) {
                parts.add("(?<![\\w.])" + g.group(1) + "\\s*\\.\\s*" + g.group(2) + "\\s*\\(\\s*\\)");
            } else if (e.matches("\\w+\\.\\w+")) {
                parts.add("(?<![\\w.])" + e.replace(".", "\\s*\\.\\s*") + "\\b(?!\\s*\\()");
            } else {
                parts.add("(?<![\\w.])" + Pattern.quote(e) + "\\b(?!\\s*\\()");
            }
        }
        return "(?:" + String.join("|", parts) + ")";
    }

    /** {@code Integer t = request.getType();} → t 也指这个参数 */
    private static void localAliases(String clean, int from, Set<String> exprs) {
        String a = aliasRegex(exprs);
        Matcher m = Pattern.compile("\\b(?:final\\s+)?(?:[A-Z]\\w*(?:<[^;=()]*>)?|int|long|short|double|float|boolean|var)"
                + "\\s+(\\w+)\\s*=\\s*" + a + "\\s*;").matcher(clean);
        List<String> add = new ArrayList<>();
        while (m.find()) {
            if (m.start() >= from) {
                add.add(m.group(1));
            }
        }
        exprs.addAll(add);
    }

    /**
     * 「失败条件」里的一段（顶层 || 拆出来的），参数不传时它成不成立。
     * <p>
     * {@code !(…)} 包着的是「通过条件」：里面 && 连着的某一项是判非空，不传才会失败；
     * {@code !(isEmpty(x) || 审核通过)} 这种，不传反而通过。
     */
    static boolean missingFails(String d, String a) {
        String s = d.strip();
        if (s.startsWith("!")) {
            String rest = s.substring(1).strip();
            if (rest.startsWith("(") && matching(rest, 0, '(', ')') == rest.length() - 1) {
                String inner = rest.substring(1, rest.length() - 1);
                Pattern present = Pattern.compile("^\\s*\\(*\\s*(?:" + a + "\\s*!=\\s*null|null\\s*!=\\s*" + a
                        + "|(?:Objects\\s*\\.\\s*)?nonNull\\s*\\(\\s*" + a + "\\s*\\)"
                        + "|(?:\\w+\\s*\\.\\s*)?(?:isNot(?:Empty|Blank)|hasText|hasLength)\\s*\\(\\s*" + a + "\\s*\\))"
                        + "\\s*\\)*\\s*$");
                for (String c : splitOp(inner, "&&")) {
                    if (!c.contains("||") && present.matcher(c).find()) {
                        return true;
                    }
                }
                return false;
            }
        }
        return !s.contains("&&") && Pattern.compile(nullishRegex(a)).matcher(s).find();
    }

    static String nullishRegex(String a) {
        return "(?:" + a + "\\s*==\\s*null|null\\s*==\\s*" + a
                + "|\\b(?:Objects\\s*\\.\\s*)?isNull\\s*\\(\\s*" + a + "\\s*\\)"
                + "|\\b(?:\\w+\\s*\\.\\s*)?is(?:Empty|Blank)\\s*\\(\\s*" + a + "\\s*\\)"
                + "|!\\s*\\(*\\s*(?:Objects\\s*\\.\\s*)?nonNull\\s*\\(\\s*" + a + "\\s*\\)"
                + "|!\\s*\\(*\\s*" + a + "\\s*!=\\s*null"
                + "|!\\s*\\(*\\s*(?:\\w+\\s*\\.\\s*)?(?:isNot(?:Empty|Blank)|hasText|hasLength|notNull)\\s*\\(\\s*" + a + "\\s*\\))";
    }

    private static String guardRegex(String a) {
        return "(?:(?<!!\\s{0,3}\\(\\s{0,3})" + a + "\\s*!=\\s*null|null\\s*!=\\s*" + a
                + "|(?<!!\\s{0,3})\\b(?:Objects\\s*\\.\\s*)?nonNull\\s*\\(\\s*" + a + "\\s*\\)"
                + "|(?<!!\\s{0,3})\\b(?:\\w+\\s*\\.\\s*)?(?:isNot(?:Empty|Blank)|hasText|hasLength)\\s*\\(\\s*" + a + "\\s*\\)"
                + "|Optional\\s*\\.\\s*ofNullable\\s*\\(\\s*" + a + "\\s*\\)\\s*\\.\\s*ifPresent)";
    }

    private static List<Pattern> defaultPatterns(String a) {
        return List.of(
                Pattern.compile(a + "\\s*[=!]=\\s*null\\s*\\?"),
                Pattern.compile("null\\s*[=!]=\\s*" + a + "\\s*\\?"),
                Pattern.compile("\\b(?:Objects\\s*\\.\\s*)?(?:isNull|nonNull)\\s*\\(\\s*" + a + "\\s*\\)\\s*\\?"),
                Pattern.compile("Optional\\s*\\.\\s*ofNullable\\s*\\(\\s*" + a + "\\s*\\)\\s*\\.\\s*orElse"),
                Pattern.compile("\\b(?:requireNonNullElse|defaultIfNull|defaultIfBlank|defaultIfEmpty|defaultString"
                        + "|firstNonNull)\\s*\\(\\s*" + a + "\\b"));
    }

    /** 没判空就用：拆箱比较、算术、调方法 */
    private static int derefAt(String clean, int from, String a, String type) {
        List<Pattern> ps = new ArrayList<>(List.of(
                Pattern.compile(a + "\\s*(?:[<>]=?)\\s*[-\\w(]"),
                Pattern.compile("[\\w)]\\s+(?:[<>]=?)\\s*" + a),
                Pattern.compile(a + "\\s*\\.\\s*(?!getClass\\b)[a-z]\\w*\\s*\\("),
                Pattern.compile("\\bfor\\s*\\([^:;]*:\\s*" + a + "\\s*\\)")));
        if ("number".equals(type) || "boolean".equals(type)) {
            ps.add(Pattern.compile(a + "\\s*(?:==|!=)\\s*-?\\d"));
            ps.add(Pattern.compile("(?<![\\w.])-?\\d+\\s*(?:==|!=)\\s*" + a));
            ps.add(Pattern.compile(a + "\\s*[-*/%]\\s*[\\w(]"));
            ps.add(Pattern.compile(a + "\\s*\\+\\s*\\d"));
            ps.add(Pattern.compile("!\\s*" + a + "\\b"));
        }
        int best = -1;
        for (Pattern p : ps) {
            Matcher m = p.matcher(clean);
            if (m.find(from) && (best < 0 || m.start() < best)) {
                best = m.start();
            }
        }
        return best;
    }

    private static boolean guardedBefore(String clean, int from, int use, String a) {
        Pattern g = Pattern.compile("(?:" + a + "\\s*[=!]=\\s*null|null\\s*[=!]=\\s*" + a
                + "|(?:isNull|nonNull|isEmpty|isBlank|isNotEmpty|isNotBlank|hasText|hasLength|ofNullable"
                + "|requireNonNull\\w*|notNull|checkNotNull)\\s*\\(\\s*" + a + ")");
        Matcher m = g.matcher(clean);
        m.region(from, use);
        return m.find();
    }

    /** 只认 if 条件里的判非空；传给校验方法的 nonNull(x) 是「必须有」，不是「有才用」 */
    private static boolean insideIfCond(String clean, int at) {
        Matcher m = IF.matcher(clean);
        while (m.find() && m.start() < at) {
            int open = clean.indexOf('(', m.start());
            int close = matching(clean, open, '(', ')');
            if (open < at && at < close) {
                return true;
            }
        }
        return false;
    }

    private static boolean assigns(String clean, int[] br, String a) {
        return Pattern.compile(a + "\\s*=[^=]").matcher(clean.substring(br[0], br[1])).find();
    }

    /** 条件按顶层 || 拆开，每段剥掉外层括号；{原文去字面量, 原文} */
    private static List<String[]> disjuncts(String cond, String condText) {
        List<String[]> out = new ArrayList<>();
        for (int[] r : splitOpRanges(cond, "||")) {
            String c = cond.substring(r[0], r[1]);
            String t = condText.substring(r[0], r[1]);
            while (true) {
                String cs = c.strip();
                if (cs.startsWith("(") && matching(cs, 0, '(', ')') == cs.length() - 1) {
                    int lead = c.indexOf('(');
                    int tail = c.lastIndexOf(')');
                    c = c.substring(lead + 1, tail);
                    t = t.substring(lead + 1, tail);
                } else {
                    break;
                }
            }
            out.add(new String[]{c, t});
        }
        return out;
    }

    private static List<String> splitOp(String s, String op) {
        List<String> out = new ArrayList<>();
        for (int[] r : splitOpRanges(s, op)) {
            out.add(s.substring(r[0], r[1]));
        }
        return out;
    }

    /** {@code a != 1 && a != 2}：只有全是 != 才拆，别的 && 组合看不准 */
    private static List<String> splitNe(String s) {
        List<String> parts = splitOp(s, "&&");
        for (String p : parts) {
            if (!p.contains("!=") || p.contains("null")) {
                return null;
            }
        }
        return parts;
    }

    private static List<int[]> splitOpRanges(String s, String op) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && s.startsWith(op, i)) {
                out.add(new int[]{start, i});
                start = i + op.length();
                i += op.length() - 1;
            }
        }
        out.add(new int[]{start, s.length()});
        return out;
    }

    private static List<Long> equalities(String cond, String a) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?:" + a + "\\s*==\\s*(-?\\d+)\\b|(?<![\\w.])(-?\\d+)\\s*==\\s*" + a
                + "|Objects\\s*\\.\\s*equals\\s*\\(\\s*" + a + "\\s*,\\s*(-?\\d+)\\s*\\)"
                + "|" + a + "\\s*\\.\\s*equals\\s*\\(\\s*(-?\\d+)\\s*\\))").matcher(cond);
        while (m.find()) {
            for (int g = 1; g <= 4; g++) {
                if (m.group(g) != null) {
                    out.add(Long.parseLong(m.group(g)));
                }
            }
        }
        return out;
    }

    private static String flip(String op) {
        return switch (op) {
            case ">" -> "<";
            case "<" -> ">";
            case ">=" -> "<=";
            case "<=" -> ">=";
            default -> op;
        };
    }

    /** if/else 后面的分支：{@code [开始, 结束)}，块或单条语句 */
    private static int[] branch(String clean, int from) {
        int i = skipWs(clean, from);
        if (i >= clean.length()) {
            return null;
        }
        if (clean.charAt(i) == '{') {
            int end = matching(clean, i, '{', '}');
            return end < 0 ? null : new int[]{i + 1, end};
        }
        int depth = 0;
        for (int j = i; j < clean.length(); j++) {
            char c = clean.charAt(j);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return new int[]{i, j + 1};
            }
        }
        return null;
    }

    /** {@link #branch} 的结束位置：块是右大括号本身，单条语句是分号之后 */
    private static int afterBranch(String clean, int end) {
        return end < clean.length() && clean.charAt(end) == '}' ? end + 1 : end;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int bodyStart(String clean, String method) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(clean);
        if (!m.find()) {
            return -1;
        }
        int open = clean.indexOf('(', m.start());
        int close = matching(clean, open, '(', ')');
        return close < 0 ? -1 : clean.indexOf('{', close);
    }

    /** 顶层逗号切参数：{@code [开始, 结束)} */
    static List<int[]> splitTop(String clean, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = clean.charAt(i);
            if (c == '(' || c == '{' || c == '[' || c == '<') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']' || c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                out.add(new int[]{start, i});
                start = i + 1;
            }
        }
        if (!clean.substring(start, to).isBlank()) {
            out.add(new int[]{start, to});
        }
        return out;
    }

    private static boolean methodExists(String src, String name) {
        return JavaSnippets.method(src, name) != null;
    }

    /**
     * 按名字和实参个数找方法声明，重载时按字面量类型挑：{@code [声明起点, 形参左括号, 方法体右括号]}
     */
    private static int[] methodDecl(String src, String name, List<String> args) {
        String clean = JavaSnippets.blankLiterals(src);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(clean);
        int[] best = null;
        int bestScore = Integer.MIN_VALUE;
        while (m.find()) {
            int open = clean.indexOf('(', m.start());
            int close = matching(clean, open, '(', ')');
            if (close < 0) {
                continue;
            }
            int i = close + 1;
            while (i < clean.length() && clean.charAt(i) != '{' && clean.charAt(i) != ';') {
                i++;
            }
            if (i >= clean.length() || clean.charAt(i) != '{') {
                continue;
            }
            String before = clean.substring(Math.max(0, m.start() - 80), m.start()).stripTrailing();
            if (before.isEmpty() || before.endsWith(".") || before.endsWith("=") || before.endsWith("(")
                    || before.endsWith(",") || before.endsWith("return") || before.endsWith("new")) {
                continue;
            }
            List<int[]> ps = splitTop(clean, open + 1, close);
            if (ps.size() != args.size()) {
                continue;
            }
            int score = 0;
            for (int k = 0; k < ps.size(); k++) {
                Decl d = decl(src.substring(ps.get(k)[0], ps.get(k)[1]));
                if (d == null) {
                    continue;
                }
                String a = args.get(k).strip();
                boolean str = a.startsWith("\"");
                boolean num = a.matches("-?\\d+[lL]?");
                boolean en = a.matches("[A-Z]\\w*\\.[A-Z][A-Z0-9_]+") || a.matches("[A-Z][A-Z0-9_]+");
                boolean pStr = STR.contains(d.simpleType());
                boolean pNum = NUM.contains(d.simpleType());
                if (str) {
                    score += pStr ? 2 : -2;
                } else if (num) {
                    score += pNum ? 2 : -2;
                } else if (en) {
                    score += !pStr && !pNum && !PRIMITIVE.contains(d.simpleType()) ? 2 : -1;
                }
            }
            if (score > bestScore) {
                int end = matching(clean, i, '{', '}');
                if (end < 0) {
                    continue;
                }
                int start = i;
                int j = m.start() - 1;
                while (j >= 0 && clean.charAt(j) != ';' && clean.charAt(j) != '}' && clean.charAt(j) != '{') {
                    j--;
                }
                start = j + 1;
                best = new int[]{start, open, end};
                bestScore = score;
            }
        }
        return best;
    }

    /** 方法体里的形参换成实参写法；不加换行，行号不变 */
    private static String substitute(String body, String bodyClean, int sigClose, List<String> names, List<String> args) {
        Map<String, String> rep = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null) {
                continue;
            }
            String a = args.get(i).replaceAll("\\s+", " ").strip();
            boolean simple = a.matches("[\\w.]+(?:\\(\\s*\\))?") || a.startsWith("\"") && a.endsWith("\"");
            rep.put(n, simple ? a : "(" + a + ")");
        }
        if (rep.isEmpty()) {
            return body;
        }
        StringBuilder sb = new StringBuilder(body.substring(0, sigClose));
        Matcher m = Pattern.compile("(?<![\\w.])([a-zA-Z_]\\w*)\\b").matcher(bodyClean);
        int last = sigClose;
        m.region(sigClose, bodyClean.length());
        while (m.find()) {
            String r = rep.get(m.group(1));
            if (r == null) {
                continue;
            }
            int after = skipWs(bodyClean, m.end());
            if (after < bodyClean.length() && bodyClean.charAt(after) == '(') {
                continue;
            }
            sb.append(body, last, m.start()).append(r);
            last = m.end();
        }
        sb.append(body.substring(last));
        return sb.toString();
    }

    /** 调用结果是不是接口返回的东西：在 return / setResult 里，或者赋给的变量后来被 return / setResult */
    private static boolean onReturnPath(String clean, int callAt, int callEnd) {
        int s = callAt - 1;
        while (s >= 0 && clean.charAt(s) != ';' && clean.charAt(s) != '{' && clean.charAt(s) != '}') {
            s--;
        }
        String stmt = clean.substring(s + 1, callAt).strip();
        if (stmt.startsWith("return") || Pattern.compile("\\bset(?:Result|Data|Body|Obj|Content)\\s*\\(\\s*$")
                .matcher(stmt).find() || Pattern.compile("\\b(?:success|ok|of|wrap)\\s*\\(\\s*$").matcher(stmt).find()) {
            return true;
        }
        Matcher a = Pattern.compile("(\\w+)\\s*=\\s*$").matcher(stmt);
        if (a.find()) {
            String v = a.group(1);
            String rest = clean.substring(callEnd);
            return Pattern.compile("\\breturn\\s+" + v + "\\s*;|\\bset(?:Result|Data|Body|Obj|Content)\\s*\\(\\s*" + v
                    + "\\s*\\)|\\b(?:success|ok|of|wrap)\\s*\\(\\s*" + v + "\\s*\\)").matcher(rest).find();
        }
        return false;
    }

    static int matching(String s, int open, char o, char c) {
        if (open < 0 || open >= s.length() || s.charAt(open) != o) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String snippet(String text, int from, int to) {
        int s = from;
        while (s > 0 && text.charAt(s - 1) != ';' && text.charAt(s - 1) != '{' && text.charAt(s - 1) != '\n') {
            s--;
        }
        int e = to;
        while (e < text.length() && text.charAt(e) != ';' && text.charAt(e) != '\n') {
            e++;
        }
        String r = squash(text.substring(s, e));
        return r.length() > 80 ? r.substring(0, 80) + "…" : r;
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
