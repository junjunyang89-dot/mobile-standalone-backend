package com.mobile.standalone.service.apitest;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从开发给的接口文档（Confluence 导出的 .docx）里抽出<b>参数约束</b>。
 * <p>
 * 抓包和文档回答的是两个不同的问题，缺一不可：
 * <ul>
 *   <li><b>抓包</b>：这个接口<b>实际</b>被怎么调了——真实路径、真实入参值、真实响应结构</li>
 *   <li><b>文档</b>：这个接口<b>应该</b>怎么调——必填/可选、默认值、枚举回落、边界、可空</li>
 * </ul>
 * 只有抓包，生成的用例是「把这次的调用重放一遍」，验不出边界；
 * 更糟的是它会给「查不到可空」的字段生成非空断言，正常情况下也会红。
 * <p>
 * 只认<b>表格</b>，不猜正文：Confluence 的接口文档入参出参都是表，
 * 而正文是给人读的散文。从散文里正则出约束，错报率高到没人会再看第二眼。
 */
@Slf4j
public final class InterfaceDocParser {

    private InterfaceDocParser() {
    }

    private static final Pattern TABLE = Pattern.compile("<w:tbl>.*?</w:tbl>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<w:tr[ >].*?</w:tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<w:tc>.*?</w:tc>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    /** {@code GET /gld/deadlock/user/match/cnt} —— 正文里的接口声明 */
    private static final Pattern ENDPOINT = Pattern.compile(
            "\\b(GET|POST|PUT|DELETE|PATCH)\\s+(/[A-Za-z0-9_\\-/{}.]+)");

    /** 入参表的表头长这样：字段 / 必填 / 默认 / 说明 */
    private static final List<String> REQUIRED_WORDS = List.of("必填", "必传", "required");
    private static final List<String> TYPE_WORDS = List.of("类型", "type");

    /**
     * @param name     字段名
     * @param required 文档说必填
     * @param defaultValue 默认值；{@code —} 之类的占位会归一成 null
     * @param note     原文说明。<b>原样留着</b>——「其它回落 1」这种话，
     *                 归纳成结构化字段一定会丢信息，而人看得懂
     */
    public record Param(String name, boolean required, String defaultValue, String note) {
    }

    /**
     * @param nullable 文档说这个字段可空。<b>可空的不要生成非空断言</b>，
     *                 否则正常情况下也会红，而人查半天发现是断言写错了
     */
    public record Field(String name, String type, String note, boolean nullable) {
    }

    public record Endpoint(String httpMethod, String path, List<Param> params, List<Field> fields) {
    }

    public record Result(List<Endpoint> endpoints, List<String> branches, String text) {
    }

    /** 文档里出现的分支名，用来把这份文档和对比分析的结果对上 */
    private static final Pattern BRANCH = Pattern.compile(
            "\\b((?:fch|feature|hotfix|backend|release)/[A-Za-z0-9._/\\-]+)");

    public static Result parse(InputStream docx) {
        String xml = readDocumentXml(docx);
        if (xml == null || xml.isBlank()) {
            return new Result(List.of(), List.of(), "");
        }
        String text = plain(xml);

        List<String> branches = new ArrayList<>();
        Matcher bm = BRANCH.matcher(text);
        while (bm.find()) {
            String b = bm.group(1);
            if (!branches.contains(b)) {
                branches.add(b);
            }
        }

        // 文档里接口声明和它的表是按出现顺序交替的：
        // 「GET /a」→ 入参表 → 出参表 →「GET /b」→ ……
        // 所以按<b>位置</b>把表归给它前面最近的那个接口声明，而不是靠标题猜
        List<int[]> tablePos = new ArrayList<>();
        List<String> tableXml = new ArrayList<>();
        Matcher tm = TABLE.matcher(xml);
        while (tm.find()) {
            tablePos.add(new int[]{tm.start()});
            tableXml.add(tm.group());
        }

        List<Object[]> declPos = new ArrayList<>();
        Matcher em = ENDPOINT.matcher(xml);
        while (em.find()) {
            declPos.add(new Object[]{em.start(), em.group(1), em.group(2)});
        }

        Map<String, Endpoint> byKey = new LinkedHashMap<>();
        for (int i = 0; i < tableXml.size(); i++) {
            int pos = tablePos.get(i)[0];
            Object[] owner = null;
            for (Object[] d : declPos) {
                if ((int) d[0] < pos) {
                    owner = d;
                } else {
                    break;
                }
            }
            if (owner == null) {
                continue;
            }
            String method = (String) owner[1];
            String path = (String) owner[2];
            String key = method + " " + path;

            List<List<String>> rows = rowsOf(tableXml.get(i));
            if (rows.isEmpty()) {
                continue;
            }
            Cols cols = detectCols(rows);
            if (!cols.isParamTable() && !cols.isFieldTable()) {
                // 认不出来的整张丢掉，不猜
                continue;
            }
            Endpoint ep = byKey.computeIfAbsent(key,
                    k -> new Endpoint(method, path, new ArrayList<>(), new ArrayList<>()));
            if (cols.isParamTable()) {
                ep.params().addAll(toParams(rows, cols));
            } else {
                ep.fields().addAll(toFields(rows, cols));
            }
        }
        // 一个接口都认不出来时，别硬造：宁可让人知道这份文档没解析出东西，
        // 也不要给出几条不知道属于谁的参数
        return new Result(new ArrayList<>(byKey.values()), branches, text);
    }

    /** 认得出的类型写法。认不出的列不当类型列，宁可整张表跳过 */
    private static final Pattern TYPE_CELL = Pattern.compile(
            "^(String|Integer|int|Long|long|Double|double|Float|Boolean|boolean|Number|Date"
                    + "|BigDecimal|Object|Map|List<[^>]*>|[A-Z][A-Za-z0-9]*DTO|[A-Za-z]+/[A-Za-z]+)$");

    /**
     * 一张表里每一列是干什么的。
     *
     * @param name     字段名列
     * @param required 必填列（是/否）；{@code -1} 表示没有 —— 那就不是入参表
     * @param type     类型列；{@code -1} 表示没有
     * @param note     说明列；{@code -1} 表示没有
     * @param def      默认值列；{@code -1} 表示没有
     */
    record Cols(int name, int required, int type, int note, int def) {
        boolean isParamTable() {
            return required >= 0;
        }

        boolean isFieldTable() {
            return required < 0 && type >= 0;
        }
    }

    /**
     * 按<b>整列的内容</b>判断列的角色，不看表头。
     * <p>
     * Confluence 导出会把表头样式丢掉，第一行常常是空的；而列数也不可靠——
     * 入参表有 3 列的（字段/必填/说明）也有 4 列的（字段/必填/默认/说明），
     * 出参表有 {@code 字段/类型/说明}，还有 {@code 字段/类型/必填} 这种其实是入参的。
     * 靠列数分，三种都会串。
     * <p>
     * <b>认不出必填列也认不出类型列的，整张表丢掉。</b>
     * 文档里混着大量散文表（「DB：无新表/无迁移」这种），
     * 硬当成字段解析出来，生成的用例会去断言一个不存在的字段——
     * 错报比不报糟得多：人核过两三条假的，真的那条也会一起被忽略。
     */
    static Cols detectCols(List<List<String>> rows) {
        int width = 0;
        for (List<String> r : rows) {
            width = Math.max(width, r.size());
        }
        if (width < 2) {
            return new Cols(-1, -1, -1, -1, -1);
        }
        int requiredCol = -1;
        int typeCol = -1;
        for (int c = 1; c < width; c++) {
            int req = 0;
            int type = 0;
            int seen = 0;
            for (List<String> r : rows) {
                if (r.size() <= c) {
                    continue;
                }
                String v = r.get(c).trim();
                if (v.isEmpty()) {
                    continue;
                }
                seen++;
                if ("是".equals(v) || "否".equals(v) || "Y".equalsIgnoreCase(v) || "N".equalsIgnoreCase(v)) {
                    req++;
                } else if (TYPE_CELL.matcher(v).matches()) {
                    type++;
                }
            }
            if (seen == 0) {
                continue;
            }
            // 过半才算数：偶尔一格写着「是」不代表整列是必填列
            if (requiredCol < 0 && req * 2 > seen) {
                requiredCol = c;
            } else if (typeCol < 0 && type * 2 > seen) {
                typeCol = c;
            }
        }
        if (requiredCol < 0 && typeCol < 0) {
            return new Cols(-1, -1, -1, -1, -1);
        }
        // 说明列固定取最后一列——它是唯一稳定的约定，而且原文说明必须留住
        int noteCol = width - 1;
        if (noteCol == requiredCol || noteCol == typeCol || noteCol == 0) {
            noteCol = -1;
        }
        // 默认值列：入参表里既不是名字/必填/说明的那一列
        int defCol = -1;
        if (requiredCol >= 0) {
            for (int c = 1; c < width; c++) {
                if (c != requiredCol && c != noteCol && c != typeCol) {
                    defCol = c;
                    break;
                }
            }
        }
        return new Cols(0, requiredCol, typeCol, noteCol, defCol);
    }

    private static List<Param> toParams(List<List<String>> rows, Cols c) {
        List<Param> out = new ArrayList<>();
        for (List<String> r : rows) {
            if (r.isEmpty() || !isFieldName(r.get(c.name()))) {
                continue;
            }
            String req = cell(r, c.required());
            String def = blankIfPlaceholder(cell(r, c.def()));
            String note = cell(r, c.note());
            // 默认值列里装的是一整句说明时，那张表其实没有默认值列——
            // 把说明当默认值，生成的用例会拿「非法 → PARAM_ERROR」当参数值去发请求
            if (def != null && looksLikeSentence(def)) {
                if (note == null || note.isBlank()) {
                    note = def;
                }
                def = null;
            }
            out.add(new Param(r.get(c.name()).trim(),
                    "是".equals(req) || "Y".equalsIgnoreCase(req), def, note == null ? "" : note));
        }
        return out;
    }

    private static List<Field> toFields(List<List<String>> rows, Cols c) {
        List<Field> out = new ArrayList<>();
        for (List<String> r : rows) {
            if (r.isEmpty() || !isFieldName(r.get(c.name()))) {
                continue;
            }
            String type = cell(r, c.type());
            // 类型格里不是类型的，说明这行不是字段规格（散文表混进来了）
            if (type == null || !TYPE_CELL.matcher(type).matches()) {
                continue;
            }
            String note = cell(r, c.note());
            note = note == null ? "" : note;
            out.add(new Field(r.get(c.name()).trim(), type, note, sayNullable(note)));
        }
        return out;
    }

    private static String cell(List<String> row, int idx) {
        if (idx < 0 || idx >= row.size()) {
            return null;
        }
        String v = row.get(idx).trim();
        return v.isEmpty() ? null : v;
    }

    /** 「非法 → PARAM_ERROR」这种是说明不是默认值。默认值都是短标量 */
    static boolean looksLikeSentence(String v) {
        if (v == null) {
            return false;
        }
        return v.length() > 12 || v.contains("→") || v.contains("；") || v.contains("，")
                || v.contains(" ") || v.contains("：");
    }

    /** 「查不到可空」「可为空」「可能为 null」——这些一律不生成非空断言 */
    static boolean sayNullable(String note) {
        if (note == null) {
            return false;
        }
        String n = note.toLowerCase(Locale.ROOT);
        return n.contains("可空") || n.contains("可为空") || n.contains("可能为空")
                || n.contains("nullable") || n.contains("可能为 null") || n.contains("可能为null");
    }

    /** {@code —} / {@code -} / {@code 无} 是「没有默认值」的写法，不是默认值本身 */
    static String blankIfPlaceholder(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty() || "—".equals(s) || "-".equals(s) || "无".equals(s) || "/".equals(s)) {
            return null;
        }
        return s;
    }

    private static boolean isFieldName(String s) {
        String v = s == null ? "" : s.trim();
        return v.matches("[A-Za-z_][A-Za-z0-9_.\\[\\]]{0,63}");
    }

    private static List<List<String>> rowsOf(String tableXml) {
        List<List<String>> out = new ArrayList<>();
        Matcher rm = ROW.matcher(tableXml);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = CELL.matcher(rm.group());
            while (cm.find()) {
                cells.add(plain(cm.group()));
            }
            if (!cells.isEmpty()) {
                out.add(cells);
            }
        }
        return out;
    }

    private static String plain(String xml) {
        String s = xml.replaceAll("</w:p>", "\n");
        s = TAG.matcher(s).replaceAll("");
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").trim();
    }

    private static String readDocumentXml(InputStream in) {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    return new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ex) {
            log.warn("[doc] 读取 docx 失败: {}", ex.getMessage());
        }
        return null;
    }
}
