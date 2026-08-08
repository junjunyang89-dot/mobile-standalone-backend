package com.mobile.standalone.service.apichange;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 展开一个返回体/请求体的<b>完整字段清单</b>（含嵌套），用于抓包时逐字段对照。
 * <p>
 * 跟字段 diff 的区别：diff 只回答「这次改了什么」，这里回答「<b>这个接口到底有哪些字段、
 * 每个是什么意思</b>」。测试拿着抓包结果对字段时，需要的是后者——尤其接口有几十个字段、
 * 名字还都是英文缩写的时候。
 * <p>
 * 只展开<b>待测那一侧</b>的代码：测的是新版本，基线侧的字段清单对测试没有意义。
 */
public final class FieldTreeBuilder {

    /** 再深就是把整棵对象图铺开了，报告会没法看；三层够覆盖绝大多数返回体 */
    private static final int MAX_DEPTH = 3;
    private static final int MAX_FIELDS = 200;

    private FieldTreeBuilder() {
    }

    @Data
    public static class FieldNode {
        /** 带层级的路径，如 heroLine[].heroId；抓包对字段时靠它定位 */
        private String path;
        /** 抓包看到的名字（@JsonProperty 改过就是改后的） */
        private String name;
        private String type;
        /** javadoc 里的中文名 */
        private String comment;
        private int depth;
        /** 本次变更：ADDED / REMOVED / TYPE_CHANGED；没变则为空 */
        private String changeType;
    }

    /**
     * @param rootType 返回体类型简单名，如 FollowUserInfo
     * @param locate   简单类名 -> 源码；找不到返回 null
     * @param changed  本次有变更的字段名（不带路径），用来在完整清单里标出来
     */
    public static List<FieldNode> build(String rootType,
                                        Function<String, String> locate,
                                        Map<String, String> changed) {
        List<FieldNode> out = new ArrayList<>();
        if (rootType == null || rootType.isBlank()) {
            return out;
        }
        expand(rootType, "", 0, locate, changed, new LinkedHashSet<>(), out);
        return out;
    }

    private static void expand(String type, String prefix, int depth,
                               Function<String, String> locate,
                               Map<String, String> changed,
                               Set<String> visiting,
                               List<FieldNode> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FIELDS || type == null) {
            return;
        }
        String simple = simpleOf(type);
        if (simple == null || JavaApiSourceParser.isBuiltin(simple)) {
            return;
        }
        // 对象图里互相引用很常见（用户里有帖子、帖子里有用户），没有环检测会转到栈溢出
        if (!visiting.add(simple)) {
            return;
        }
        String source = locate.apply(simple);
        if (source == null) {
            visiting.remove(simple);
            return;
        }
        for (DtoField f : JavaApiSourceParser.parseFields(source)) {
            if (out.size() >= MAX_FIELDS) {
                break;
            }
            // @JsonIgnore 的字段不会出现在 JSON 里，列出来只会让人对着抓包找一个找不到的东西
            if (f.ignored()) {
                continue;
            }
            boolean collection = isCollection(f.type());
            String path = prefix.isEmpty()
                    ? f.wireName()
                    : prefix + "." + f.wireName();

            FieldNode n = new FieldNode();
            n.setPath(path + (collection ? "[]" : ""));
            n.setName(f.wireName());
            n.setType(f.type());
            n.setComment(f.comment());
            n.setDepth(depth);
            n.setChangeType(depth == 0 ? changed.get(f.name()) : null);
            out.add(n);

            String inner = elementTypeOf(f.type());
            if (inner != null && !JavaApiSourceParser.isBuiltin(inner)) {
                expand(inner, path + (collection ? "[]" : ""), depth + 1, locate, changed, visiting, out);
            }
        }
        visiting.remove(simple);
    }

    private static boolean isCollection(String type) {
        String t = type == null ? "" : type;
        return t.startsWith("List<") || t.startsWith("Set<") || t.startsWith("Collection<")
                || t.endsWith("[]");
    }

    /** List&lt;XxxDTO&gt; -> XxxDTO；Map&lt;String, XxxDTO&gt; -> XxxDTO；普通类型原样返回 */
    static String elementTypeOf(String type) {
        if (type == null) {
            return null;
        }
        String t = type.replaceAll("\\s+", "");
        if (t.endsWith("[]")) {
            return t.substring(0, t.length() - 2);
        }
        int lt = t.indexOf('<');
        int gt = t.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            return t;
        }
        String inner = t.substring(lt + 1, gt);
        // Map<String, XxxVO> 取最后一个类型参数，前面那个基本是 key
        int depth = 0;
        int lastComma = -1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) lastComma = i;
        }
        String last = lastComma < 0 ? inner : inner.substring(lastComma + 1);
        return elementTypeOf(last);
    }

    private static String simpleOf(String type) {
        String t = elementTypeOf(type);
        if (t == null) {
            return null;
        }
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    /** 简单类名 -> 源码文件路径的索引，供 locate 用 */
    public static String normalizeKey(String simpleName) {
        return simpleName == null ? "" : simpleName.trim().toLowerCase(Locale.ROOT);
    }
}
