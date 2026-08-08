package com.mobile.standalone.service;

import com.mobile.standalone.entity.AiTestCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把测试用例表格（Excel / CSV / 从表格软件粘贴的 TSV）解析成用例。
 * <p>
 * <b>按表头名认列，不按列序号</b>：用例模板会被人加列、挪列、改顺序，钉死下标的解析器
 * 第一次改模板就全错，而且错得很安静——数据会串到别的字段里去。
 * <p>
 * 「实际结果」「是否已实现自动化」两列<b>刻意忽略</b>：前者是每次执行的数据（放进用例会被
 * 下一轮覆盖），后者由关联的流程推导（人手填的会立刻过期）。忽略了什么会在 warnings 里说清楚，
 * 而不是悄悄丢掉。
 */
public final class TestCaseSheetParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 表头 → 字段。同一字段的多个写法都列上，模板换个叫法不至于整列丢掉 */
    private static final Map<String, String> HEADERS = new LinkedHashMap<>();

    static {
        put("module", "功能模块", "模块", "一级模块", "所属模块");
        put("subModule", "子功能", "二级模块", "子模块");
        put("testItem", "测试项", "测试类型");
        put("testPoint", "测试点", "检查点");
        put("title", "用例标题", "标题", "用例名称", "用例描述");
        put("priority", "用例级别", "优先级", "级别", "等级");
        put("precondition", "初始条件", "前置条件", "预置条件");
        put("steps", "测试步骤", "步骤", "操作步骤");
        put("expectedResult", "期望结果", "预期结果", "预期");
        put("remark", "备注", "说明");
        put("caseId", "用例编号", "编号", "用例id", "caseid");
        // 认出来只为报「已忽略」，不落库
        put("_ignoredActual", "实际结果", "测试结果");
        put("_ignoredAutomated", "是否已实现自动化", "是否自动化", "自动化");
    }

    private static void put(String field, String... headers) {
        for (String h : headers) {
            HEADERS.put(normalizeHeader(h), field);
        }
    }

    private TestCaseSheetParser() {
    }

    public record Result(List<AiTestCase> rows, List<String> warnings) {
    }

    /** 解析上传的文件；xlsx/xls 走 POI，其余按文本（CSV / TSV）处理 */
    public static Result parse(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return parseWorkbook(content);
        }
        return parseText(new String(content, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 从表格软件直接复制粘贴过来的内容：行换行、列 Tab */
    public static Result parseText(String text) {
        List<List<String>> grid = new ArrayList<>();
        for (String line : String.valueOf(text == null ? "" : text).split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String sep = line.contains("\t") ? "\t" : ",";
            grid.add(List.of(line.split(sep, -1)));
        }
        return fromGrid(grid);
    }

    private static Result parseWorkbook(byte[] content) {
        try (InputStream in = new ByteArrayInputStream(content);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("表格里没有工作表");
            }
            DataFormatter fmt = new DataFormatter();
            List<List<String>> grid = new ArrayList<>();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                short last = row.getLastCellNum();
                for (int c = 0; c < Math.max(0, last); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : fmt.formatCellValue(cell));
                }
                grid.add(cells);
            }
            return fromGrid(grid);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("表格解析失败：" + e.getMessage());
        }
    }

    private static Result fromGrid(List<List<String>> grid) {
        List<String> warnings = new ArrayList<>();
        int headerRow = findHeaderRow(grid);
        if (headerRow < 0) {
            throw new IllegalArgumentException(
                    "没找到表头行。请确认表格里有「功能模块 / 测试点 / 测试步骤」这类列名，且表头在前 10 行内");
        }

        Map<Integer, String> colField = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        List<String> header = grid.get(headerRow);
        for (int c = 0; c < header.size(); c++) {
            String raw = header.get(c);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String field = HEADERS.get(normalizeHeader(raw));
            if (field == null) {
                unknown.add(raw.trim());
            } else if (field.startsWith("_ignored")) {
                ignored.add(raw.trim());
            } else {
                colField.put(c, field);
            }
        }
        if (!colField.containsValue("title") && !colField.containsValue("testPoint")) {
            throw new IllegalArgumentException("表头里既没有「用例标题」也没有「测试点」，无法确定用例名");
        }
        if (!ignored.isEmpty()) {
            warnings.add("已忽略列：" + String.join("、", ignored)
                    + "。实际结果属于每次执行的数据（放进用例会被下一轮覆盖）；"
                    + "是否已自动化由关联的流程推导，不用手填。");
        }
        if (!unknown.isEmpty()) {
            warnings.add("认不出的列：" + String.join("、", unknown) + "，这些列不会导入。");
        }

        List<AiTestCase> rows = new ArrayList<>();
        int blank = 0;
        for (int r = headerRow + 1; r < grid.size(); r++) {
            AiTestCase row = toCase(grid.get(r), colField);
            if (row == null) {
                blank++;
                continue;
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("表头之下没有有效数据行");
        }
        if (blank > 0) {
            warnings.add("跳过 " + blank + " 个空行。");
        }
        return new Result(rows, warnings);
    }

    /** 表头未必在第 1 行（上面常有标题、说明、合并单元格），认字段最多的那一行 */
    private static int findHeaderRow(List<List<String>> grid) {
        int best = -1;
        int bestHits = 0;
        for (int r = 0; r < Math.min(grid.size(), 10); r++) {
            int hits = 0;
            for (String cell : grid.get(r)) {
                if (cell != null && HEADERS.containsKey(normalizeHeader(cell))) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = r;
            }
        }
        return bestHits >= 2 ? best : -1;
    }

    private static AiTestCase toCase(List<String> cells, Map<Integer, String> colField) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : colField.entrySet()) {
            int c = e.getKey();
            String v = c < cells.size() ? cells.get(c) : null;
            if (v != null && !v.isBlank()) {
                values.put(e.getValue(), v.trim());
            }
        }
        if (values.isEmpty()) {
            return null;
        }

        AiTestCase row = new AiTestCase();
        row.setModule(values.get("module"));
        row.setSubModule(values.get("subModule"));
        row.setTestItem(values.get("testItem"));
        row.setTestPoint(values.get("testPoint"));
        // 模板里未必有「用例标题」列，退回测试点当标题——那是最贴近「这条在验什么」的一列
        String title = values.getOrDefault("title", values.get("testPoint"));
        if (title == null || title.isBlank()) {
            return null;
        }
        row.setTitle(title);
        row.setCaseId(values.get("caseId"));
        row.setPriority(normalizePriority(values.get("priority")));
        row.setPrecondition(values.get("precondition"));
        row.setExpectedResult(values.get("expectedResult"));
        row.setRemark(values.get("remark"));
        row.setStepsJson(stepsJson(values.get("steps")));
        return row;
    }

    /**
     * 一格里的多行步骤拆成结构化步骤。
     * 模板里常写成「1.启动APP / 2.点击登录」，前面那截序号要去掉，否则会跟自动编号叠成「1. 1.启动APP」。
     */
    static String stepsJson(String raw) {
        ArrayNode arr = JSON.createArrayNode();
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        int no = 1;
        for (String line : raw.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            s = s.replaceFirst("^\\s*\\(?\\d+\\s*[.、)）:：]\\s*", "");
            if (s.isEmpty()) {
                continue;
            }
            ObjectNode node = arr.addObject();
            node.put("step", no++);
            node.put("action", s);
            node.put("target", "");
            node.put("expected", "");
        }
        return arr.toString();
    }

    /** P0/P1/P2 与 高/中/低 两套写法都认；认不出保持 P1，别把用例卡在导入这一步 */
    static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return "P1";
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("P0") || v.contains("高") || "0".equals(v) || "1".equals(v)) {
            return "P0";
        }
        if (v.startsWith("P2") || v.contains("低") || "3".equals(v)) {
            return "P2";
        }
        return "P1";
    }

    /** 空格、全角括号、大小写都不该影响认列 */
    private static String normalizeHeader(String raw) {
        return raw.replaceAll("[\\s\\u00a0*　]+", "")
                .replace("（", "(")
                .replace("）", ")")
                .toLowerCase(Locale.ROOT);
    }
}
