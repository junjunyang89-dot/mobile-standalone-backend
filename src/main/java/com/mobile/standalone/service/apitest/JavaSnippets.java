package com.mobile.standalone.service.apitest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Java 源码里抠出给模型看的片段：某个方法的完整实现、某个类去掉方法体后的字段部分。
 * <p>
 * 整个文件扔给模型既贵又分散注意力；一个 Controller 几十个接口，
 * 模型会把别的方法的校验安到这个接口头上。
 */
public final class JavaSnippets {

    private JavaSnippets() {
    }

    private static final Pattern EXTENDS = Pattern.compile("\\bclass\\s+\\w+(?:<[^{]*?>)?\\s+extends\\s+(\\w+)");
    private static final Pattern ACCESSOR = Pattern.compile(
            "^\\s*public\\s+[\\w<>\\[\\], ?]+\\s+(get|set|is)[A-Z]\\w*\\s*\\([^)]*\\)\\s*\\{…}\\s*$");
    private static final Pattern TYPE_TOKEN = Pattern.compile("\\b([A-Z]\\w+)\\b");
    private static final Set<String> JDK_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Short", "Byte", "Character",
            "Object", "List", "Map", "Set", "Collection", "Optional", "BigDecimal", "BigInteger",
            "Date", "LocalDate", "LocalDateTime", "Instant", "HttpServletRequest", "HttpServletResponse",
            "MultipartFile", "RequestParam", "RequestBody", "PathVariable", "RequestHeader", "Validated",
            "Valid", "ModelAttribute", "JSONObject", "JSONArray", "Void", "ResponseResult");

    /**
     * 方法声明 + 注解 + 方法体。同名重载取第一个带方法体的。
     *
     * @return 找不到返回 null
     */
    public static String method(String source, String name) {
        if (source == null || name == null || name.isBlank()) {
            return null;
        }
        String clean = blankLiterals(source);
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(clean);
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
            // 声明前面得是类型名，不能是 . 或 =（那是调用）
            String before = clean.substring(Math.max(0, m.start() - 80), m.start()).stripTrailing();
            if (before.isEmpty() || before.endsWith(".") || before.endsWith("=") || before.endsWith("(")
                    || before.endsWith(",") || before.endsWith("return") || before.endsWith("new")) {
                continue;
            }
            int end = matching(clean, i, '{', '}');
            if (end < 0) {
                continue;
            }
            int start = memberStart(clean, m.start());
            return source.substring(start, end + 1).strip();
        }
        return null;
    }

    /** 类的字段部分：去掉 import、方法体和 getter/setter，留注释（字段含义常写在注释里） */
    public static String fieldsOnly(String source, int maxChars) {
        if (source == null) {
            return "";
        }
        String clean = blankLiterals(source);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                depth++;
                if (depth == 2) {
                    out.append("{…}");
                    continue;
                }
            } else if (c == '}') {
                depth--;
                if (depth == 1) {
                    continue;
                }
            }
            if (depth <= 1) {
                out.append(source.charAt(i));
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : out.toString().split("\n")) {
            String t = line.strip();
            if (t.startsWith("import ") || t.startsWith("package ") || ACCESSOR.matcher(line).matches()) {
                continue;
            }
            if (t.isEmpty() && !lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                continue;
            }
            lines.add(line.stripTrailing());
        }
        String s = String.join("\n", lines).strip();
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "\n// …（截断）";
    }

    public static String superclass(String source) {
        if (source == null) {
            return null;
        }
        Matcher m = EXTENDS.matcher(blankLiterals(source));
        return m.find() ? m.group(1) : null;
    }

    /** 一段代码里出现的非 JDK 类型名，用来顺藤摸出入参类 */
    public static Set<String> typeNames(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = TYPE_TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group(1);
            if (!JDK_TYPES.contains(t) && !t.equals(t.toUpperCase())) {
                out.add(t);
            }
        }
        return out;
    }

    /** 方法体里 {@code xxx.method(} 形式的调用：接收者 → 方法名 */
    public static List<String[]> calls(String methodSource) {
        List<String[]> out = new ArrayList<>();
        if (methodSource == null) {
            return out;
        }
        Matcher m = Pattern.compile("\\b([a-z]\\w*)\\.([a-z]\\w*)\\s*\\(").matcher(blankLiterals(methodSource));
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (seen.add(m.group(1) + "." + m.group(2))) {
                out.add(new String[]{m.group(1), m.group(2)});
            }
        }
        return out;
    }

    /** 字段 {@code private XxxService fooService;} 里 foo 对应的类型 */
    public static String fieldType(String source, String fieldName) {
        if (source == null || fieldName == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b([A-Z]\\w*)(?:<[^;=]*>)?\\s+" + Pattern.quote(fieldName) + "\\s*[;=]")
                .matcher(blankLiterals(source));
        return m.find() ? m.group(1) : null;
    }

    /** 往回找到上一个成员的结尾，这样方法上的注解和注释一起带上 */
    private static int memberStart(String clean, int from) {
        int i = from - 1;
        while (i >= 0) {
            char c = clean.charAt(i);
            if (c == ';' || c == '}' || c == '{') {
                return i + 1;
            }
            i--;
        }
        return 0;
    }

    private static int matching(String s, int open, char o, char c) {
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

    /**
     * 字符串、字符字面量和注释里的内容换成空格，长度不变。
     * 路径 "/{id}" 里的大括号、注释里的半截代码都会把括号配对搞乱。
     */
    static String blankLiterals(String s) {
        char[] out = s.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                    if (i < n) out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n && out[i] != q) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }
}
