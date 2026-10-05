package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码这次开始读写的列：mapper XML 里多出来的 column、MyBatis-Plus / JPA 实体上多出来的字段。
 * <p>
 * 最常见的线上事故之一：开发在自己库里执行过加字段的 SQL，分支和单子里都没放；
 * 代码一发，读写这张表的语句全部报 Unknown column。diff 里只看得到「mapper 改了」，
 * 报告里写「SQL 有改动，结构没变」反而让人以为不用动表。
 * <p>
 * 新建的 mapper 文件不看：分不清是新表还是给老表补的 mapper，宁可少报。
 */
final class NewColumnScanner {

    private NewColumnScanner() {
    }

    /** @param file 从哪看出来的（mapper XML 或实体类） */
    record Hit(String table, String column, String file) {
    }

    private static final Pattern COLUMN_ATTR = Pattern.compile("\\bcolumn\\s*=\\s*\"`?(\\w+)`?\"");
    private static final Pattern BASE_COLUMNS = Pattern.compile(
            "<sql\\s+id\\s*=\\s*\"Base_Column_List\"\\s*>(.*?)</sql>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_COLUMNS = Pattern.compile(
            "insert\\s+into\\s+`?(\\w+)`?\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE = Pattern.compile("\\bupdate\\s+`?(\\w+)`?\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern PLAIN_COLUMN = Pattern.compile("(?:\\w+\\.)?`?(\\w+)`?");

    private static final Pattern TABLE_NAME = Pattern.compile(
            "@TableName\\s*\\(\\s*(?:value\\s*=\\s*)?\"`?(\\w+)`?\"|@Table\\s*\\(\\s*name\\s*=\\s*\"`?(\\w+)`?\"");
    private static final Pattern FIELD_COLUMN = Pattern.compile(
            "@(?:TableField|Column)\\s*\\(\\s*(?:(?:value|name)\\s*=\\s*)?\"`?(\\w+)`?\"");
    private static final Pattern NOT_COLUMN = Pattern.compile(
            "@TableField\\s*\\([^)]*exist\\s*=\\s*false|@Transient\\b");

    static List<Hit> scan(List<String> changed, Function<String, String> base, Function<String, String> head) {
        List<Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String file : changed) {
            String lower = file.toLowerCase(Locale.ROOT);
            String was = base.apply(file);
            String now = head.apply(file);
            if (was == null || now == null || JavaApiSourceParser.isTestPath(file)) {
                continue;
            }
            List<Hit> hits = lower.endsWith(".xml") && lower.contains("mapper") ? fromMapper(file, was, now)
                    : lower.endsWith(".java") ? fromEntity(file, was, now) : List.of();
            for (Hit h : hits) {
                if (seen.add(h.table() + "." + h.column())) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    static List<Hit> fromMapper(String file, String was, String now) {
        String table = mainTable(now);
        if (table == null) {
            return List.of();
        }
        Set<String> before = mapperColumns(was);
        List<Hit> out = new ArrayList<>();
        for (String c : mapperColumns(now)) {
            if (!before.contains(c)) {
                out.add(new Hit(table, c, file));
            }
        }
        return out;
    }

    /** resultMap 的 column、Base_Column_List、insert 的列清单：这三处是 MyBatis Generator 生成的 mapper 加列必改的地方 */
    static Set<String> mapperColumns(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher a = COLUMN_ATTR.matcher(xml);
        while (a.find()) {
            out.add(a.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher b = BASE_COLUMNS.matcher(xml);
        if (b.find()) {
            addList(out, b.group(1));
        }
        Matcher i = INSERT_COLUMNS.matcher(XML_TAG.matcher(xml).replaceAll(" "));
        while (i.find()) {
            addList(out, i.group(2));
        }
        return out;
    }

    private static void addList(Set<String> out, String list) {
        for (String part : XML_TAG.matcher(list).replaceAll(" ").split(",")) {
            Matcher m = PLAIN_COLUMN.matcher(part.trim());
            if (m.matches()) {
                out.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    /** mapper 对应的表：先看 insert into，再看 update，最后看第一个 from */
    static String mainTable(String xml) {
        String text = XML_TAG.matcher(xml).replaceAll(" ");
        for (Pattern p : new Pattern[]{INSERT_COLUMNS, UPDATE_TABLE, FROM_TABLE}) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    static List<Hit> fromEntity(String file, String was, String now) {
        Matcher tm = TABLE_NAME.matcher(now);
        if (!tm.find()) {
            return List.of();
        }
        String table = (tm.group(1) != null ? tm.group(1) : tm.group(2)).toLowerCase(Locale.ROOT);
        Map<String, MethodLogicDiff.Member> before = MethodLogicDiff.members(was);
        List<Hit> out = new ArrayList<>();
        for (MethodLogicDiff.Member m : MethodLogicDiff.members(now).values()) {
            if (!"FIELD".equals(m.kind()) || before.containsKey(m.key()) || m.decl().matches("(?s).*\\bstatic\\b.*")) {
                continue;
            }
            String annos = String.join(" ", m.annotations());
            if (NOT_COLUMN.matcher(annos).find()) {
                continue;
            }
            Matcher fc = FIELD_COLUMN.matcher(annos);
            out.add(new Hit(table, fc.find() ? fc.group(1).toLowerCase(Locale.ROOT) : snake(m.name()), file));
        }
        return out;
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** 这些 SQL 文本里有没有给 table 加 column 的语句（ALTER ... ADD，或者建表语句里就带着这一列） */
    static boolean declaredIn(String sql, String table, String column) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        for (DdlChange d : JavaApiSourceParser.parseDdl(sql)) {
            if (d.column() != null && d.column().equalsIgnoreCase(column)
                    && (d.table() == null || d.table().equalsIgnoreCase(table))) {
                return true;
            }
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("create table");
        while (at >= 0) {
            int end = lower.indexOf(';', at);
            String stmt = lower.substring(at, end < 0 ? lower.length() : end);
            if (stmt.matches("(?s)create table\\s+(if not exists\\s+)?`?" + Pattern.quote(table.toLowerCase(Locale.ROOT)) + "`?\\b.*")
                    && stmt.matches("(?s).*[\\s(`,]" + Pattern.quote(column.toLowerCase(Locale.ROOT)) + "`?\\s.*")) {
                return true;
            }
            at = lower.indexOf("create table", at + 1);
        }
        return false;
    }
}
