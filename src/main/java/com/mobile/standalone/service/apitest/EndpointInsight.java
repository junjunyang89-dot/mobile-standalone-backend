package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobile.standalone.service.apichange.DtoField;
import com.mobile.standalone.service.apichange.JavaApiSourceParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口体检：顺着 Controller 方法读源码，回答抓包回答不了的几件事——
 * 字段是什么意思、代码里会返回哪些业务码、要不要登录、有没有一看就可疑的写法。
 * <p>
 * 全是正则级别的读法，只看接口方法和它直接调到的 service 方法。认不出就不报：
 * 报一个不存在的问题，人会拿着去找开发对质，比漏报代价大。
 */
public final class EndpointInsight {

    private EndpointInsight() {
    }

    /**
     * @param path   {@code $.result.list[].name}，{@code []} 表示数组元素
     * @param type   number / string / boolean / array / object / date / any
     * @param status OK / NULL / MISSING / TYPE / EXTRA / SKIP；没有抓包样本时为空
     */
    public record Field(String path, String name, String type, String javaType, String comment,
                        String status, String sample) {
        Field with(String status, String sample) {
            return new Field(path, name, type, javaType, comment, status, sample);
        }
    }

    /** @param where {@code MemberServiceImpl.java:392} */
    public record Code(String name, String code, String message, String where) {
    }

    /** @param level HIGH / MID / LOW */
    public record Risk(String level, String key, String title, String detail, String where) {
    }

    /**
     * @param required  true 挂了登录注解；false 没挂（或被注释掉）；null 仓库不靠注解鉴权，看不出来
     * @param commented 登录注解被注释掉了
     * @param lenient   注解写了 strict=false / required=false，token 无效也放行
     * @param failCode  不带 token 时登录切面/拦截器抛的业务码
     */
    public record Login(Boolean required, String annotation, boolean commented, boolean lenient,
                        String where, Code failCode) {
    }

    /**
     * @param ranges 读过的方法：相对路径:起始行-结束行，拿去 git blame 看多久没人动过
     * @param params 每个入参不传 / 传错 / 越界时代码怎么处理，见 {@link ParamInsight}
     */
    public record Report(String file, int line, String handler, String returnType, List<String> services,
                         List<String> ranges,
                         List<Field> fields, Login login, Code success, List<Code> successCodes,
                         List<Code> errors, List<Code> globalErrors, Boolean httpAlways200, List<Risk> risks,
                         List<ParamInsight.Param> params) {

        public Report withRisks(List<Risk> risks) {
            return new Report(file, line, handler, returnType, services, ranges, fields, login, success,
                    successCodes, errors, globalErrors, httpAlways200, risks, params);
        }
    }

    /** 一个仓库的类名索引 + 读过的文件缓存 */
    public static final class Repo {
        private final Path root;
        private final Map<String, List<Path>> classes;
        private final Map<Path, String> texts = new HashMap<>();

        public Repo(Path root, Map<String, List<Path>> classes) {
            this.root = root.toAbsolutePath().normalize();
            this.classes = classes;
        }

        public Path root() {
            return root;
        }

        /** 类名 → 文件。按文件名认，内部类认不出 */
        public static Map<String, List<Path>> index(Path root) throws IOException {
            Path base = root.toAbsolutePath().normalize();
            Map<String, List<Path>> out = new HashMap<>();
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(base) && SourceEndpointScanner.SKIP_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String n = file.getFileName().toString();
                    if (n.endsWith(".java")) {
                        out.computeIfAbsent(n.substring(0, n.length() - 5), k -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            return out;
        }

        String read(Path p) {
            if (p == null) {
                return "";
            }
            return texts.computeIfAbsent(p, k -> {
                try {
                    return Files.readString(k, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
        }

        Path find(String simpleName) {
            List<Path> list = simpleName == null ? null : classes.get(simpleName);
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (Path p : list) {
                if (p.toString().replace('\\', '/').contains("/src/main/")) {
                    return p;
                }
            }
            return list.get(0);
        }

        String rel(Path p) {
            return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        }

        Set<String> names() {
            return classes.keySet();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FIELDS = 120;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_SERVICE_METHODS = 4;

    private static final Set<String> NUM = Set.of("int", "long", "short", "byte", "double", "float", "Integer",
            "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger", "Number", "AtomicInteger",
            "AtomicLong");
    private static final Set<String> STR = Set.of("String", "char", "Character", "CharSequence", "UUID");
    private static final Set<String> BOOL = Set.of("boolean", "Boolean");
    private static final Set<String> DATE = Set.of("Date", "LocalDateTime", "LocalDate", "LocalTime", "Timestamp",
            "Instant", "ZonedDateTime", "OffsetDateTime");
    private static final Set<String> ARR = Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "Collection", "Iterable", "JSONArray", "Flux");
    private static final Set<String> MAP = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap",
            "ConcurrentHashMap", "JSONObject");
    private static final Set<String> ANY = Set.of("Object", "JsonNode", "?", "Void", "void");
    /** 框架层的壳，JSON 里看不到它们 */
    private static final Set<String> FRAMEWORK = Set.of("ResponseEntity", "Mono", "CompletableFuture",
            "DeferredResult", "Callable", "WebAsyncTask", "Optional", "Future", "CompletionStage");

    private static final Pattern ENUM_REF = Pattern.compile("\\b([A-Z]\\w*)\\.([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern THROW_MSG = Pattern.compile(
            "throw\\s+new\\s+([A-Z]\\w*(?:Exception|Error))\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern THROW_ENUM = Pattern.compile(
            "throw\\s+new\\s+\\w+\\s*\\(\\s*([A-Z]\\w*)\\.([A-Z][A-Z0-9_]+)");
    private static final Pattern COMMENTED_AUTH = Pattern.compile(
            "(?m)^[ \\t]*//[ \\t]*(@(?!(?i:no|ignore|skip|without|anonymous|pass|permit|allow|public|author))"
                    + "\\w*(?:Auth|Login)\\w*)");
    private static final Pattern LENIENT = Pattern.compile("\\b(?:strict|required)\\s*=\\s*false\\b");
    private static final Pattern EMPTY_RETURN = Pattern.compile(
            "\\breturn\\s+(new\\s+[A-Z]\\w*(?:<[^>;]*>)?\\s*\\(\\s*\\)|null|Collections\\.empty\\w*\\(\\s*\\)"
                    + "|new\\s+(?:Array|Linked)List<[^>;]*>\\s*\\(\\s*\\)|Lists\\.newArrayList\\(\\s*\\)"
                    + "|List\\.of\\(\\s*\\)|Optional\\.empty\\(\\s*\\))\\s*;");
    private static final Pattern VAR_RETURN = Pattern.compile("\\breturn\\s+([a-z]\\w*)\\s*;");
    private static final Pattern IF_COND = Pattern.compile("\\bif\\s*\\((.+)\\)\\s*\\{?\\s*(?:return\\b.*)?$");
    /** 「没登录」分支的条件：tokenDetail == null、isEmpty(token)、!isLogin… */
    private static final Pattern LOGIN_WORDS = Pattern.compile(
            "(?i)\\b\\w*(?:token|login|session|passport)\\w*\\s*==\\s*null"
                    + "|(?:isEmpty|isBlank|isNull)\\s*\\(\\s*\\w*(?:token|session)\\w*\\s*\\)"
                    + "|!\\s*\\w*(?:login|logged)\\w*");
    private static final Pattern USER_GETTER = Pattern.compile("\\b(get\\w*(?:Token|User|Login|Session)\\w*)\\s*\\(");
    private static final Pattern DEPRECATED = Pattern.compile("@Deprecated\\b");
    private static final Pattern ADVICE = Pattern.compile("@(?:Rest)?ControllerAdvice\\b");
    private static final Pattern NON_200 = Pattern.compile(
            "@ResponseStatus\\b|HttpStatus\\.|ResponseEntity\\.status|\\.setStatus\\(");

    /** 一段方法源码和它在文件里的起始行，用来把命中位置换算成 文件:行 */
    private record Snip(String file, String name, String text, int startLine) {
        String where(int offset) {
            return fileName(file) + ":" + (startLine + lineOf(text, offset) - 1);
        }
    }

    /**
     * @param controllerFile 相对仓库根目录的路径
     * @param handler        Controller 方法名
     * @param scannedAuth    扫描器给的登录结论（仓库不靠注解鉴权时为 null）
     * @param respSample     抓包响应，用来对字段；可空
     * @param configuredSuccess 项目管理里配的成功码，用来提示和接口实际成功码不一致；可空
     * @return 方法都找不到时返回 null
     */
    public static Report analyze(Repo repo, String controllerFile, String handler, Boolean scannedAuth,
                                 String respSample, String configuredSuccess) {
        Path ctrlPath = repo.root().resolve(controllerFile);
        String src = repo.read(ctrlPath);
        String method = JavaSnippets.method(src, handler);
        if (method == null) {
            return null;
        }
        int at = src.indexOf(method);
        String cleanMethod = JavaSnippets.blankLiterals(method);
        Matcher sig = Pattern.compile("\\b" + Pattern.quote(handler) + "\\s*\\(").matcher(cleanMethod);
        int sigAt = sig.find() ? sig.start() : 0;
        String head = method.substring(0, sigAt);
        String returnType = returnTypeOf(cleanMethod.substring(0, sigAt));

        List<Snip> snips = new ArrayList<>();
        snips.add(new Snip(controllerFile, fileName(controllerFile).replace(".java", "") + "." + handler,
                method, lineOf(src, at)));
        snips.addAll(serviceMethods(repo, src, method));

        JsonNode sample = parse(respSample);
        List<Field> fields = compare(new Walker(repo).root(returnType, sample), sample);

        Map<String, Map<String, Code>> enums = new HashMap<>();
        Code success = null;
        String successEnum = null;
        Set<String> codeEnums = new HashSet<>();
        for (Snip s : snips) {
            String clean = JavaSnippets.blankLiterals(s.text());
            Matcher m = ENUM_REF.matcher(clean);
            while (m.find()) {
                Code def = constant(repo, enums, m.group(1), m.group(2));
                if (def != null && success == null && isSuccessName(def.name())) {
                    success = new Code(def.name(), def.code(), def.message(), s.where(m.start()));
                    successEnum = m.group(1);
                }
            }
            Matcher t = THROW_ENUM.matcher(clean);
            while (t.find()) {
                codeEnums.add(t.group(1));
            }
        }
        if (successEnum != null) {
            codeEnums.add(successEnum);
        }
        List<Code> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Snip s : snips) {
            String clean = JavaSnippets.blankLiterals(s.text());
            Matcher m = ENUM_REF.matcher(clean);
            while (m.find()) {
                if (!isCodeEnum(m.group(1), codeEnums)) {
                    continue;
                }
                Code def = constant(repo, enums, m.group(1), m.group(2));
                if (def != null && !isSuccessName(def.name()) && seen.add(def.name())) {
                    errors.add(new Code(def.name(), def.code(), def.message(), s.where(m.start())));
                }
            }
            Matcher t = THROW_MSG.matcher(s.text());
            while (t.find()) {
                if (clean.charAt(t.start()) != ' ' && seen.add(t.group(2))) {
                    errors.add(new Code(t.group(1), null, t.group(2), s.where(t.start())));
                }
            }
        }
        List<Code> successCodes = new ArrayList<>();
        if (successEnum != null) {
            for (Code c : enums.getOrDefault(successEnum, Map.of()).values()) {
                if (PLAIN_SUCCESS.matcher(c.name()).matches()) {
                    successCodes.add(c);
                }
            }
        }

        Login login = login(repo, enums, src, head, at, controllerFile, scannedAuth);

        List<Code> globalErrors = new ArrayList<>();
        Boolean always200 = null;
        for (Path adv : adviceFiles(repo)) {
            String text = repo.read(adv);
            String clean = JavaSnippets.blankLiterals(text);
            always200 = (always200 == null || always200) && !NON_200.matcher(clean).find();
            Snip s = new Snip(repo.rel(adv), adv.getFileName().toString(), text, 1);
            Matcher m = ENUM_REF.matcher(clean);
            while (m.find()) {
                Code def = isCodeEnum(m.group(1), codeEnums) ? constant(repo, enums, m.group(1), m.group(2)) : null;
                if (def != null && !isSuccessName(def.name())
                        && globalErrors.stream().noneMatch(g -> g.name().equals(def.name()))) {
                    globalErrors.add(new Code(def.name(), def.code(), def.message(), s.where(m.start())));
                }
            }
        }

        List<Risk> risks = risks(src, head, snips, login, success, successEnum, successCodes, fields,
                configuredSuccess, controllerFile, at);
        List<String> services = snips.stream().skip(1)
                .map(s -> s.name() + "（" + fileName(s.file()) + ":" + s.startLine() + "）").toList();
        List<String> ranges = snips.stream()
                .map(s -> s.file() + ":" + s.startLine() + "-" + (s.startLine() + lineOf(s.text(), s.text().length()) - 1))
                .toList();
        List<ParamInsight.Param> params;
        try {
            params = ParamInsight.analyze(repo, controllerFile, src, method, handler, enums);
        } catch (RuntimeException ex) {
            // 参数分析是正则级别的读法，读不懂某种写法不能连累整份体检
            params = List.of();
        }
        return new Report(controllerFile, lineOf(src, at + sigAt), handler, returnType, services, ranges, fields, login,
                success, successCodes, errors, globalErrors, always200, risks, params);
    }

    // ---------- 登录 ----------

    private static Login login(Repo repo, Map<String, Map<String, Code>> enums, String src, String head, int at,
                               String file, Boolean scannedAuth) {
        String headClean = SourceEndpointScanner.stripComments(head);
        Matcher am = SourceEndpointScanner.AUTH_ANNOTATION.matcher(headClean);
        boolean active = am.find();
        boolean classAuth = SourceEndpointScanner.classRequiresAuth(SourceEndpointScanner.stripComments(src));
        Matcher cm = COMMENTED_AUTH.matcher(head);
        boolean commented = !active && cm.find();
        String anno = active ? am.group() : commented ? cm.group(1) : null;
        String where = null;
        boolean lenient = false;
        if (active) {
            int i = head.indexOf(anno);
            where = fileName(file) + ":" + lineOf(src, at + Math.max(i, 0));
            Matcher args = Pattern.compile(Pattern.quote(anno) + "\\s*\\(([^)]*)\\)").matcher(head);
            lenient = args.find() && LENIENT.matcher(args.group(1)).find();
        } else if (commented) {
            where = fileName(file) + ":" + lineOf(src, at + cm.start(1));
        }
        if (anno == null && classAuth) {
            Matcher c = SourceEndpointScanner.AUTH_ANNOTATION.matcher(SourceEndpointScanner.stripComments(src));
            anno = c.find() ? c.group() : null;
        }
        Boolean required = active || classAuth ? Boolean.TRUE : commented ? Boolean.FALSE : scannedAuth;
        Code fail = anno == null ? null : loginFailCode(repo, enums, anno.substring(1));
        return new Login(required, anno, commented, lenient, where, fail);
    }

    /** 处理登录注解的切面/拦截器里抛的那个业务码 */
    private static Code loginFailCode(Repo repo, Map<String, Map<String, Code>> enums, String annotation) {
        List<Path> handlers = new ArrayList<>();
        for (String n : repo.names()) {
            if (!n.equals(annotation) && n.startsWith(annotation)) {
                handlers.add(repo.find(n));
            }
        }
        if (handlers.isEmpty()) {
            for (String n : repo.names()) {
                if (handlers.size() < 30 && n.matches(".*(Aspect|Interceptor|Filter)$")) {
                    Path p = repo.find(n);
                    if (repo.read(p).contains(annotation)) {
                        handlers.add(p);
                    }
                }
            }
        }
        Code first = null;
        for (Path p : handlers) {
            String text = repo.read(p);
            Matcher m = THROW_ENUM.matcher(JavaSnippets.blankLiterals(text));
            while (m.find()) {
                Code def = constant(repo, enums, m.group(1), m.group(2));
                if (def == null) {
                    continue;
                }
                Code c = new Code(def.name(), def.code(), def.message(),
                        p.getFileName() + ":" + lineOf(text, m.start()));
                if (def.name().matches("(?i).*(TOKEN|LOGIN|AUTH).*")) {
                    return c;
                }
                if (first == null) {
                    first = c;
                }
            }
        }
        return first;
    }

    static List<Path> adviceFiles(Repo repo) {
        List<Path> out = new ArrayList<>();
        for (String n : repo.names()) {
            if (out.size() < 5 && n.matches("(?i).*(ExceptionHandler|ExceptionAdvice|ErrorHandler|Advice|GlobalException\\w*)$")) {
                Path p = repo.find(n);
                if (ADVICE.matcher(repo.read(p)).find()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    // ---------- service ----------

    /** Controller 方法里直接调的 service 方法（只追一层） */
    private static List<Snip> serviceMethods(Repo repo, String ctrlSrc, String method) {
        List<Snip> out = new ArrayList<>();
        for (String[] call : JavaSnippets.calls(method)) {
            if (out.size() >= MAX_SERVICE_METHODS) {
                break;
            }
            String type = JavaSnippets.fieldType(ctrlSrc, call[0]);
            if (type == null) {
                continue;
            }
            Path impl = repo.find(type + "Impl");
            if (impl == null) {
                Path p = repo.find(type);
                if (p != null && Pattern.compile("\\bclass\\s+" + Pattern.quote(type) + "\\b")
                        .matcher(repo.read(p)).find()) {
                    impl = p;
                }
            }
            if (impl == null) {
                continue;
            }
            String isrc = repo.read(impl);
            String m = JavaSnippets.method(isrc, call[1]);
            if (m == null) {
                continue;
            }
            String cls = impl.getFileName().toString().replace(".java", "");
            out.add(new Snip(repo.rel(impl), cls + "." + call[1], m, lineOf(isrc, isrc.indexOf(m))));
        }
        return out;
    }

    // ---------- 业务码 ----------

    static boolean isSuccessName(String name) {
        return (name.contains("SUCCESS") || name.equals("OK")) && !name.contains("ERROR") && !name.contains("NOT");
    }

    /** 和 SUCCESS 平级的「通用成功码」：SUCCESS、NEW_SUCCESS、OK；ACTIVATE_CARD_SUCCESS 这种带业务文案的不算 */
    private static final Pattern PLAIN_SUCCESS = Pattern.compile("(?:[A-Z0-9]+_)?SUCCESS|OK");
    private static final Pattern CODE_ENUM_NAME = Pattern.compile("(?i).*(code|error|result|status|msg|message|resp).*");

    /** VideoCountsCategoryEnum.GF_COUNTS 这种业务枚举不是返回码 */
    private static boolean isCodeEnum(String enumName, Set<String> known) {
        return known.contains(enumName) || CODE_ENUM_NAME.matcher(enumName).matches();
    }

    /** 枚举常量 → 码值和文案。不是枚举（常量类之类）返回 null */
    static Code constant(Repo repo, Map<String, Map<String, Code>> enums, String enumName, String constName) {
        Map<String, Code> consts = enums.computeIfAbsent(enumName, n -> parseEnum(repo, n));
        return consts.get(constName);
    }

    private static Map<String, Code> parseEnum(Repo repo, String name) {
        Path p = repo.find(name);
        String src = repo.read(p);
        if (p == null || !Pattern.compile("\\benum\\s+" + Pattern.quote(name) + "\\b").matcher(src).find()) {
            return Map.of();
        }
        return parseEnumConstants(src, p.getFileName().toString());
    }

    private static final Pattern ENUM_CONST = Pattern.compile(
            "(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*\\(((?:[^()\"]|\"[^\"]*\")*)\\)\\s*[,;]");
    private static final Pattern NUM_ARG = Pattern.compile("^\\s*\"?(-?\\d+)\"?\\s*$");
    private static final Pattern STR_ARG = Pattern.compile("\"([^\"]*)\"");

    static Map<String, Code> parseEnumConstants(String src, String fileName) {
        Map<String, Code> out = new LinkedHashMap<>();
        Matcher m = ENUM_CONST.matcher(src);
        while (m.find()) {
            String code = null;
            for (String arg : m.group(2).split(",")) {
                Matcher n = NUM_ARG.matcher(arg);
                if (n.matches()) {
                    code = n.group(1);
                    break;
                }
            }
            Matcher s = STR_ARG.matcher(m.group(2));
            String msg = s.find() ? s.group(1) : null;
            out.putIfAbsent(m.group(1), new Code(m.group(1), code, msg, fileName + ":" + lineOf(src, m.start(1))));
        }
        return out;
    }

    // ---------- 风险 ----------

    private static List<Risk> risks(String src, String head, List<Snip> snips, Login login, Code success,
                                    String successEnum, List<Code> successCodes, List<Field> fields,
                                    String configuredSuccess, String file, int at) {
        List<Risk> out = new ArrayList<>();
        List<String[]> silent = silentReturns(snips);
        boolean tokenSilent = silent.stream().anyMatch(s -> LOGIN_WORDS.matcher(s[0]).find());
        String fname = fileName(file);

        if (login.commented()) {
            String d = "「// " + login.annotation() + "」被注释，接口不再校验登录，不带 token 也能调通";
            if (tokenSilent) {
                d += "；而且取不到登录信息时代码直接返回空数据、照样报成功——没登录的人看到的是「0 / 没有」，不是「请先登录」";
            }
            out.add(new Risk("HIGH", "LOGIN_COMMENTED", "登录注解被注释掉了", d, login.where()));
        } else if (login.lenient()) {
            out.add(new Risk("MID", "LOGIN_LENIENT", "登录校验是宽松的",
                    login.annotation() + " 写了 strict/required = false：token 无效也会放行到业务代码", login.where()));
        } else if (Boolean.FALSE.equals(login.required())) {
            for (Snip s : snips) {
                Matcher g = USER_GETTER.matcher(JavaSnippets.blankLiterals(s.text()));
                if (g.find()) {
                    out.add(new Risk("MID", "LOGIN_MISSING", "没挂登录注解，代码却在取登录信息",
                            "接口没有登录注解，" + s.name() + " 里调了 " + g.group(1)
                                    + "()；不带 token 时是报错还是返回空数据，要实际调一次看", s.where(g.start())));
                    break;
                }
            }
        }

        int n = 0;
        for (String[] s : silent) {
            if (n++ >= 4) {
                break;
            }
            boolean noLogin = LOGIN_WORDS.matcher(s[0]).find();
            if (noLogin && !Boolean.TRUE.equals(login.required())) {
                out.add(new Risk("HIGH", "SILENT_NO_LOGIN", "拿不到登录信息也返回成功",
                        "if (" + s[0] + ") 时直接 return " + s[1] + "，接口照样返回成功码；"
                                + "不带 token 调用拿到的是一份空数据，而不是登录失败", s[2]));
            } else if (!noLogin) {
                out.add(new Risk("MID", "SILENT_EMPTY", "某些条件下静默返回空数据",
                        "if (" + s[0] + ") 时直接 return " + s[1] + "，响应仍是成功；"
                                + "测试时拿到空数据，先排除是不是走了这个分支", s[2]));
            }
        }

        String classHead = classHead(src);
        if (DEPRECATED.matcher(SourceEndpointScanner.stripComments(head)).find()
                || DEPRECATED.matcher(SourceEndpointScanner.stripComments(classHead)).find()) {
            out.add(new Risk("MID", "DEPRECATED", "接口标了 @Deprecated",
                    "代码里标记为废弃。先确认客户端还在调，再决定要不要给它写用例；没人调就该下线",
                    fname + ":" + lineOf(src, at)));
        }

        Set<String> distinct = new LinkedHashSet<>();
        successCodes.forEach(c -> distinct.add(c.code()));
        if (success != null && success.code() != null) {
            if (configuredSuccess != null && !configuredSuccess.isBlank()
                    && !configuredSuccess.trim().equals(success.code())) {
                out.add(new Risk("MID", "SUCCESS_MISMATCH", "项目配的成功码和这个接口不一样",
                        "接口返回 " + successEnum + "." + success.name() + "=" + success.code()
                                + "，项目管理里配的成功码是 " + configuredSuccess.trim()
                                + "；按项目配置自动加的成功码断言会把成功误判成失败", success.where()));
            } else if (distinct.size() > 1) {
                StringBuilder d = new StringBuilder(successEnum).append(" 里有 ").append(distinct.size())
                        .append(" 个成功码：");
                successCodes.forEach(c -> d.append(c.name()).append('=').append(c.code()).append(' '));
                d.append("。这个接口用的是 ").append(success.name()).append('=').append(success.code())
                        .append("，断言按它写");
                out.add(new Risk("LOW", "MULTI_SUCCESS", "成功码不止一个", d.toString(), success.where()));
            }
        }

        List<Field> typeBad = fields.stream().filter(f -> "TYPE".equals(f.status())).toList();
        if (!typeBad.isEmpty()) {
            StringBuilder d = new StringBuilder();
            typeBad.stream().limit(3).forEach(f -> d.append(f.path()).append(" 代码是 ").append(f.type())
                    .append("、响应是 ").append(f.sample()).append("；"));
            out.add(new Risk("MID", "FIELD_TYPE", "字段类型和代码对不上",
                    d + (typeBad.size() > 3 ? "等 " + typeBad.size() + " 个" : ""), null));
        }
        long extra = fields.stream().filter(f -> "EXTRA".equals(f.status())).count();
        if (extra > 0) {
            out.add(new Risk("LOW", "FIELD_EXTRA", "响应里有代码里没有的字段",
                    extra + " 个字段在本地代码里找不到；本地代码可能不是测试环境部署的版本，体检结论要打折扣", null));
        }
        out.sort((a, b) -> levelRank(a.level()) - levelRank(b.level()));
        return out;
    }

    static int levelRank(String level) {
        return switch (level) {
            case "HIGH" -> 0;
            case "MID" -> 1;
            default -> 2;
        };
    }

    /**
     * 带条件的「直接返回空」：{@code if (x) return new Dto();}、{@code return null;}，
     * 以及 new 出来一个对象、一个字段都没设就返回的。
     *
     * @return [条件, 返回的表达式, 文件:行]
     */
    static List<String[]> silentReturns(List<Snip> snips) {
        List<String[]> out = new ArrayList<>();
        for (Snip s : snips) {
            String clean = JavaSnippets.blankLiterals(s.text());
            List<int[]> hits = new ArrayList<>();
            Matcher m = EMPTY_RETURN.matcher(clean);
            while (m.find()) {
                hits.add(new int[]{m.start(), m.start(1), m.end(1)});
            }
            Matcher v = VAR_RETURN.matcher(clean);
            while (v.find()) {
                String var = v.group(1);
                Matcher decl = Pattern.compile("\\b[A-Z]\\w*(?:<[^>;]*>)?\\s+" + Pattern.quote(var)
                        + "\\s*=\\s*new\\s+[A-Z]\\w*(?:<[^>;]*>)?\\s*\\(\\s*\\)\\s*;").matcher(clean);
                if (decl.find() && decl.end() < v.start()
                        && !clean.substring(decl.end(), v.start()).matches("(?s).*\\b" + Pattern.quote(var) + "\\s*\\..*")) {
                    hits.add(new int[]{v.start(), v.start(1), v.end(1)});
                }
            }
            hits.sort((a, b) -> a[0] - b[0]);
            for (int[] h : hits) {
                String cond = conditionBefore(clean, h[0]);
                if (cond != null) {
                    out.add(new String[]{cond, clean.substring(h[1], h[2]).replaceAll("\\s+", " "), s.where(h[0])});
                }
            }
        }
        return out;
    }

    /** return 所在行或往上 3 行内最近的 if 条件；没有条件的 return 是正常出口，不算 */
    private static String conditionBefore(String clean, int pos) {
        int lineEnd = clean.indexOf('\n', pos);
        String[] lines = clean.substring(0, lineEnd < 0 ? clean.length() : lineEnd).split("\n", -1);
        int last = lines.length - 1;
        for (int i = last; i >= Math.max(0, last - 3); i--) {
            String l = lines[i].strip();
            if (i < last && (l.endsWith(";") || l.endsWith("}"))) {
                break;
            }
            Matcher m = IF_COND.matcher(l);
            if (m.find()) {
                return balancedCond(m.group(1));
            }
        }
        return null;
    }

    /** {@code a == null) return x} 这种贪婪多吃的，按括号配对截回条件本身 */
    private static String balancedCond(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return s.substring(0, i).strip();
                }
                depth--;
            }
        }
        return s.strip();
    }

    private static String classHead(String src) {
        String clean = JavaSnippets.blankLiterals(src);
        Matcher m = Pattern.compile("\\b(?:class|interface)\\s+\\w+").matcher(clean);
        if (!m.find()) {
            return "";
        }
        int from = clean.lastIndexOf(';', m.start()) + 1;
        return src.substring(from, m.start());
    }

    // ---------- 字段 ----------

    private record TypeRef(String base, List<TypeRef> args) {
        static final TypeRef UNKNOWN = new TypeRef("?", List.of());

        static TypeRef parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            String s = raw.replaceAll("\\s+", "").replace("?extends", "").replace("?super", "");
            if (s.endsWith("[]")) {
                return new TypeRef("List", List.of(parse(s.substring(0, s.length() - 2))));
            }
            int lt = s.indexOf('<');
            if (lt < 0) {
                return new TypeRef(simple(s), List.of());
            }
            int gt = s.lastIndexOf('>');
            List<TypeRef> args = new ArrayList<>();
            if (gt > lt) {
                for (String a : splitTop(s.substring(lt + 1, gt))) {
                    args.add(parse(a));
                }
            }
            return new TypeRef(simple(s.substring(0, lt)), args);
        }

        private static String simple(String s) {
            return s.substring(s.lastIndexOf('.') + 1);
        }

        TypeRef bind(Map<String, TypeRef> b) {
            if (args.isEmpty() && b.containsKey(base)) {
                return b.get(base);
            }
            if (args.isEmpty()) {
                return this;
            }
            List<TypeRef> out = new ArrayList<>();
            args.forEach(a -> out.add(a.bind(b)));
            return new TypeRef(base, out);
        }

        @Override
        public String toString() {
            if (args.isEmpty()) {
                return base;
            }
            StringBuilder sb = new StringBuilder(base).append('<');
            for (int i = 0; i < args.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(args.get(i));
            }
            return sb.append('>').toString();
        }
    }

    private static List<String> splitTop(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    /** 顺着返回类型展开字段：ResponseResult&lt;VipNumSummaryDTO&gt; → $.code、$.result.videoNum… */
    private static final class Walker {
        private final Repo repo;
        private final List<Field> out = new ArrayList<>();

        Walker(Repo repo) {
            this.repo = repo;
        }

        List<Field> root(String returnType, JsonNode sample) {
            TypeRef t = TypeRef.parse(returnType);
            while (FRAMEWORK.contains(t.base()) && !t.args().isEmpty()) {
                t = t.args().get(0);
            }
            String kind = kind(t);
            if ("object".equals(kind) && isRepoClass(t.base())) {
                expand(t, "$", 0, new HashSet<>());
            } else if ("array".equals(kind) && !t.args().isEmpty() && isRepoClass(t.args().get(0).base())) {
                expand(t.args().get(0), "$[]", 0, new HashSet<>());
            } else if (!t.args().isEmpty() && !ARR.contains(t.base()) && !MAP.contains(t.base())) {
                // 返回体的壳在 jar 里（R<T>、Result<T>）：数据挂在哪个键上只能看抓包猜
                String key = dataKey(sample);
                add("$." + key, key, t.args().get(t.args().size() - 1), null, 0, new HashSet<>());
            }
            return out;
        }

        private void expand(TypeRef t, String path, int depth, Set<String> stack) {
            Path f = repo.find(t.base());
            String src = repo.read(f);
            List<String> params = typeParams(src, t.base());
            Map<String, TypeRef> b = new HashMap<>();
            for (int i = 0; i < params.size(); i++) {
                b.put(params.get(i), i < t.args().size() ? t.args().get(i) : TypeRef.UNKNOWN);
            }
            stack.add(t.base());
            TypeRef sup = superOf(src, t.base());
            if (sup != null && isRepoClass(sup.base()) && !stack.contains(sup.base())) {
                expand(sup.bind(b), path, depth, stack);
            }
            for (DtoField df : JavaApiSourceParser.parseFields(classBody(src, t.base()))) {
                if (df.ignored() || out.size() >= MAX_FIELDS) {
                    continue;
                }
                add(path + "." + df.wireName(), df.wireName(), TypeRef.parse(df.type()).bind(b), df.comment(),
                        depth, stack);
            }
            stack.remove(t.base());
        }

        private void add(String path, String name, TypeRef t, String comment, int depth, Set<String> stack) {
            String kind = kind(t);
            out.add(new Field(path, name, kind, t.toString(), comment, null, null));
            if (depth + 1 >= MAX_DEPTH || out.size() >= MAX_FIELDS) {
                return;
            }
            if ("object".equals(kind) && isRepoClass(t.base()) && !stack.contains(t.base())) {
                expand(t, path, depth + 1, stack);
            } else if ("array".equals(kind) && !t.args().isEmpty()) {
                TypeRef el = t.args().get(0);
                if ("object".equals(kind(el)) && isRepoClass(el.base()) && !stack.contains(el.base())) {
                    expand(el, path + "[]", depth + 1, stack);
                }
            }
        }

        private String kind(TypeRef t) {
            String b = t.base();
            if (NUM.contains(b)) {
                return "number";
            }
            if (STR.contains(b)) {
                return "string";
            }
            if (BOOL.contains(b)) {
                return "boolean";
            }
            if (DATE.contains(b)) {
                return "date";
            }
            if (ARR.contains(b)) {
                return "array";
            }
            if (MAP.contains(b)) {
                return "object";
            }
            if (ANY.contains(b) || b.length() == 1) {
                return "any";
            }
            Path f = repo.find(b);
            if (f != null && Pattern.compile("\\benum\\s+" + Pattern.quote(b) + "\\b").matcher(repo.read(f)).find()) {
                return "string";
            }
            return "object";
        }

        private boolean isRepoClass(String name) {
            Path f = repo.find(name);
            return f != null && Pattern.compile("\\bclass\\s+" + Pattern.quote(name) + "\\b")
                    .matcher(JavaSnippets.blankLiterals(repo.read(f))).find();
        }
    }

    private static List<String> typeParams(String src, String name) {
        Matcher m = Pattern.compile("\\b(?:class|interface)\\s+" + Pattern.quote(name) + "\\s*<([^{]*?)>\\s*(?:extends|implements|\\{)")
                .matcher(JavaSnippets.blankLiterals(src));
        if (!m.find()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : splitTop(m.group(1))) {
            String t = p.strip().split("\\s+")[0];
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static TypeRef superOf(String src, String name) {
        Matcher m = Pattern.compile("\\bclass\\s+" + Pattern.quote(name)
                        + "\\s*(?:<[^{]*?>)?\\s+extends\\s+([\\w.]+\\s*(?:<[^{]*?>)?)\\s*(?:implements|\\{)")
                .matcher(JavaSnippets.blankLiterals(src));
        return m.find() ? TypeRef.parse(m.group(1)) : null;
    }

    /** 只留类体第一层：内部类的字段不能算到外层头上 */
    static String classBody(String src, String name) {
        String clean = JavaSnippets.blankLiterals(src);
        Matcher m = Pattern.compile("\\bclass\\s+" + Pattern.quote(name) + "\\b").matcher(clean);
        if (!m.find()) {
            return src;
        }
        int open = clean.indexOf('{', m.end());
        if (open < 0) {
            return src;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            if (depth == 1 && c != '{') {
                sb.append(src.charAt(i));
            } else {
                sb.append(c == '\n' ? '\n' : ' ');
            }
        }
        return sb.toString();
    }

    private static String dataKey(JsonNode sample) {
        if (sample != null && sample.isObject()) {
            for (String k : List.of("data", "result", "body", "obj", "content")) {
                if (sample.has(k)) {
                    return k;
                }
            }
        }
        return "data";
    }

    /** 方法名前面那段里最后一个类型：{@code public ResponseResult<List<X>> } → ResponseResult&lt;List&lt;X&gt;&gt; */
    static String returnTypeOf(String beforeName) {
        String s = beforeName.stripTrailing();
        int end = s.length();
        int i = end - 1;
        if (i >= 0 && s.charAt(i) == ']') {
            i = s.lastIndexOf('[', i) - 1;
        }
        if (i >= 0 && s.charAt(i) == '>') {
            int depth = 0;
            for (; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == '>') {
                    depth++;
                } else if (c == '<' && --depth == 0) {
                    i--;
                    break;
                }
            }
        }
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        int nameEnd = i + 1;
        while (i >= 0 && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
            i--;
        }
        return nameEnd <= i + 1 ? null : s.substring(i + 1, end).replaceAll("\\s+", "");
    }

    // ---------- 字段对照 ----------

    private static final Pattern SEG = Pattern.compile("\\.([^.\\[]+)|\\[]");

    /** 代码字段对上抓包：每个字段标一个状态，抓包里多出来的补成 EXTRA */
    static List<Field> compare(List<Field> fields, JsonNode sample) {
        if (sample == null || fields.isEmpty()) {
            return fields;
        }
        List<Field> out = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Field f : fields) {
            known.add(f.path());
            JsonNode[] r = at(sample, f.path());
            if (r == null) {
                out.add(f.with("SKIP", null));
                continue;
            }
            JsonNode n = r[0];
            if (n == null || n.isMissingNode()) {
                out.add(f.with("MISSING", null));
            } else if (n.isNull()) {
                out.add(f.with("NULL", "null"));
            } else {
                String got = FieldTypeAsserts.typeOf(n);
                boolean ok = switch (f.type()) {
                    case "any" -> true;
                    case "date" -> "string".equals(got) || "number".equals(got);
                    default -> f.type().equals(got);
                };
                out.add(f.with(ok ? "OK" : "TYPE", ok ? preview(n) : got + "（" + preview(n) + "）"));
            }
        }
        Set<String> parents = new HashSet<>();
        for (String p : known) {
            int dot = p.lastIndexOf('.');
            if (dot > 0) {
                parents.add(p.substring(0, dot));
            }
        }
        extras(sample, "$", parents, known, out, 0);
        return out;
    }

    private static void extras(JsonNode node, String path, Set<String> parents, Set<String> known,
                               List<Field> out, int depth) {
        if (depth > MAX_DEPTH || node == null) {
            return;
        }
        if (node.isArray()) {
            if (!node.isEmpty()) {
                extras(node.get(0), path + "[]", parents, known, out, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        boolean described = parents.contains(path);
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String p = path + "." + e.getKey();
            if (known.contains(p)) {
                extras(e.getValue(), p, parents, known, out, depth + 1);
            } else if (described && out.size() < MAX_FIELDS * 2) {
                out.add(new Field(p, e.getKey(), FieldTypeAsserts.typeOf(e.getValue()), null, null, "EXTRA",
                        preview(e.getValue())));
            }
        }
    }

    /**
     * @return null＝上级就没取到（null / 不是对象 / 空数组），这个字段没法判；
     * 否则 [0] 是节点，取不到时是 null
     */
    private static JsonNode[] at(JsonNode root, String path) {
        JsonNode n = root;
        Matcher m = SEG.matcher(path.substring(1));
        List<String> segs = new ArrayList<>();
        while (m.find()) {
            segs.add(m.group(1) == null ? "[]" : m.group(1));
        }
        for (int i = 0; i < segs.size(); i++) {
            String s = segs.get(i);
            boolean last = i == segs.size() - 1;
            if ("[]".equals(s)) {
                if (n == null || !n.isArray() || n.isEmpty()) {
                    return null;
                }
                n = n.get(0);
                continue;
            }
            if (n == null || !n.isObject()) {
                return null;
            }
            JsonNode child = n.get(s);
            if (last) {
                return new JsonNode[]{child};
            }
            n = child;
        }
        return new JsonNode[]{n};
    }

    private static String preview(JsonNode n) {
        if (n.isArray()) {
            return "[" + n.size() + " 项]";
        }
        if (n.isObject()) {
            return "{…}";
        }
        String s = n.asText();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static int lineOf(String s, int idx) {
        int line = 1;
        for (int i = 0; i < Math.min(idx, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
