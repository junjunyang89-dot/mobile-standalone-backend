package com.mobile.standalone.service;

import com.mobile.standalone.entity.AiTestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例表格解析。
 * <p>
 * 这组用例盯的是「模板会变」：加一列、挪一列、换个叫法、表头不在第一行。
 * 按列序号写的解析器第一次改模板就全错，而且错得很安静——数据会串到别的字段里去，
 * 等发现的时候用例库已经脏了一大片。所以这里按表头名认列，并把各种变形都测掉。
 */
class TestCaseSheetParserTest {

    private static final String HEADER =
            "功能模块\t子功能\t测试项\t测试点\t用例级别\t初始条件\t测试步骤\t期望结果\t实际结果\t是否已实现自动化\t备注";

    private static TestCaseSheetParser.Result parse(String... lines) {
        return TestCaseSheetParser.parseText(String.join("\n", lines));
    }

    // ==================== 基本映射 ====================

    @Test
    void 按表头名把各列映射到字段() {
        TestCaseSheetParser.Result r = parse(HEADER,
                "账号\t验证码登录\t异常输入\t错误手机号登录\tP1\t已安装APP\t打开APP\t登录失败\t未执行\t否\t见缺陷123");

        assertEquals(1, r.rows().size());
        AiTestCase c = r.rows().get(0);
        assertEquals("账号", c.getModule());
        assertEquals("验证码登录", c.getSubModule());
        assertEquals("异常输入", c.getTestItem());
        assertEquals("错误手机号登录", c.getTestPoint());
        assertEquals("错误手机号登录", c.getTitle(), "没有标题列时用测试点当标题");
        assertEquals("P1", c.getPriority());
        assertEquals("已安装APP", c.getPrecondition());
        assertEquals("登录失败", c.getExpectedResult());
        assertEquals("见缺陷123", c.getRemark());
    }

    /** 列换了顺序还得对——模板被人挪列是常事 */
    @Test
    void 列顺序变了仍按名字认() {
        TestCaseSheetParser.Result r = parse(
                "备注\t测试点\t功能模块",
                "备注内容\t错误手机号登录\t账号");

        AiTestCase c = r.rows().get(0);
        assertEquals("账号", c.getModule());
        assertEquals("错误手机号登录", c.getTestPoint());
        assertEquals("备注内容", c.getRemark());
    }

    @Test
    void 表头里的空格与全角括号不影响认列() {
        TestCaseSheetParser.Result r = parse(
                " 功能模块 \t测试 点\t用例级别（P0/P1）",
                "账号\t登录\tP0");

        assertEquals("账号", r.rows().get(0).getModule());
        assertEquals("登录", r.rows().get(0).getTestPoint());
    }

    /** 模板上方常有大标题、说明、合并单元格，表头未必在第一行 */
    @Test
    void 表头不在第一行也能找到() {
        TestCaseSheetParser.Result r = parse(
                "完美世界电竞 登录模块测试用例",
                "编写人：张三",
                "",
                HEADER,
                "账号\t验证码登录\t异常\t错误手机号\tP1\t\t打开APP\t登录失败\t\t\t");

        assertEquals(1, r.rows().size());
        assertEquals("账号", r.rows().get(0).getModule());
    }

    // ==================== 刻意忽略的两列 ====================

    /**
     * 「实际结果」是每次执行的数据，放进用例会被下一轮覆盖；
     * 「是否已实现自动化」由关联的流程推导，手填的会立刻过期。
     * 忽略了必须说出来，不能悄悄丢。
     */
    @Test
    void 实际结果与是否自动化被忽略且有明确提示() {
        TestCaseSheetParser.Result r = parse(HEADER,
                "账号\t登录\t项\t点\tP1\t\t步骤\t期望\t通过\t是\t");

        String w = String.join(" ", r.warnings());
        assertTrue(w.contains("实际结果"), w);
        assertTrue(w.contains("是否已实现自动化"), w);
        assertTrue(w.contains("已忽略列"), w);
    }

    @Test
    void 认不出的列会被点名而不是静默丢弃() {
        TestCaseSheetParser.Result r = parse(
                "功能模块\t测试点\t某个奇怪的列",
                "账号\t登录\t值");

        assertTrue(String.join(" ", r.warnings()).contains("某个奇怪的列"));
    }

    // ==================== 步骤拆分 ====================

    /** 模板里步骤挤在一格，常自带「1.」序号，不去掉会跟自动编号叠成「1. 1.打开APP」 */
    @Test
    void 一格里的多行步骤拆开并去掉自带序号() {
        String json = TestCaseSheetParser.stepsJson("1.启动APP\n2、点击侧边栏\n3）点击登录");

        assertTrue(json.contains("\"step\":1"), json);
        assertTrue(json.contains("\"action\":\"启动APP\""), json);
        assertTrue(json.contains("\"action\":\"点击侧边栏\""), json);
        assertTrue(json.contains("\"action\":\"点击登录\""), json);
        assertTrue(!json.contains("1.启动APP"), "自带序号没去掉：" + json);
    }

    @Test
    void 步骤为空时是合法空数组() {
        assertEquals("[]", TestCaseSheetParser.stepsJson(null));
        assertEquals("[]", TestCaseSheetParser.stepsJson("   "));
    }

    @Test
    void 步骤里的空行被跳过() {
        String json = TestCaseSheetParser.stepsJson("打开APP\n\n\n点击登录");

        assertTrue(json.contains("\"step\":2"), json);
        assertTrue(!json.contains("\"step\":3"), json);
    }

    // ==================== 级别归一 ====================

    @Test
    void 级别认PN与高中低两套写法() {
        assertEquals("P0", TestCaseSheetParser.normalizePriority("P0"));
        assertEquals("P0", TestCaseSheetParser.normalizePriority("高"));
        assertEquals("P2", TestCaseSheetParser.normalizePriority("低"));
        assertEquals("P2", TestCaseSheetParser.normalizePriority("p2"));
        assertEquals("P1", TestCaseSheetParser.normalizePriority("中"));
    }

    /** 认不出别把整条卡在导入这一步，给个默认值让它进来，人再改 */
    @Test
    void 级别认不出时给默认值() {
        assertEquals("P1", TestCaseSheetParser.normalizePriority("紧急"));
        assertEquals("P1", TestCaseSheetParser.normalizePriority(null));
    }

    // ==================== 报错要能照着改 ====================

    @Test
    void 没有表头时报错说清该有哪些列() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("张三\t李四", "值1\t值2"));

        assertTrue(e.getMessage().contains("表头"), e.getMessage());
    }

    @Test
    void 表头认得出但没有用例名时报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse("功能模块\t备注", "账号\t备注内容"));

        assertTrue(e.getMessage().contains("测试点") || e.getMessage().contains("标题"), e.getMessage());
    }

    @Test
    void 只有表头没有数据行时报错() {
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER));
    }

    @Test
    void 空行被跳过并计数() {
        TestCaseSheetParser.Result r = parse(HEADER,
                "账号\t登录\t项\t点\tP1\t\t步骤\t期望\t\t\t",
                "\t\t\t\t\t\t\t\t\t\t",
                "账号\t登录\t项\t点2\tP1\t\t步骤\t期望\t\t\t");

        assertEquals(2, r.rows().size());
    }

    /** 逗号分隔的 CSV 也要能吃，粘贴出来不一定是 Tab */
    @Test
    void 逗号分隔也能解析() {
        TestCaseSheetParser.Result r = TestCaseSheetParser.parseText(
                "功能模块,测试点,用例级别\n账号,错误手机号登录,P0");

        assertEquals("账号", r.rows().get(0).getModule());
        assertEquals("P0", r.rows().get(0).getPriority());
    }

    @Test
    void 未填的单元格落成null而不是空串() {
        TestCaseSheetParser.Result r = parse("功能模块\t测试点\t备注", "账号\t登录\t");

        assertNull(r.rows().get(0).getRemark());
    }
}
