package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全与字段设计扫描。
 * <p>
 * 这组用例一半在盯<b>不该报什么</b>。这份报告的价值全在可信度上：人核过两三条假的就不看了，
 * 真的那条也一起废掉。所以宁可漏也不能错报——{@code ${criterion.condition}} 那类
 * MyBatis 生成器样板要是全报出来，就是 83 个文件的噪音，真正的 {@code ${offset}} 反而看不见。
 */
class ApiRiskScannerTest {

    private static ApiEndpoint ep(List<ApiParam> params) {
        return new ApiEndpoint("POST", "/a/b", "h", null, "摘要", "VO", params, Set.of());
    }

    private static FieldTreeBuilder.FieldNode fld(String path, String name, String type) {
        FieldTreeBuilder.FieldNode n = new FieldTreeBuilder.FieldNode();
        n.setPath(path);
        n.setName(name);
        n.setType(type);
        return n;
    }

    // ==================== 越权 ====================

    /** 入参直接收身份标识 = 水平越权的典型面：换成别人的 ID 就能看/改别人的数据 */
    @Test
    void 入参带身份标识时提示越权() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanSecurity(
                ep(List.of(new ApiParam("QUERY", "targetSteamId64", "Long", true))), List.of());

        assertEquals(1, out.size());
        assertEquals("SECURITY", out.get(0).kind());
        assertTrue(out.get(0).title().contains("越权"), out.get(0).title());
    }

    @Test
    void 普通业务参数不算越权() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanSecurity(
                ep(List.of(new ApiParam("QUERY", "page", "int", false),
                        new ApiParam("QUERY", "keyword", "String", false))), List.of());

        assertTrue(out.isEmpty(), out.toString());
    }

    // ==================== 敏感字段 ====================

    @Test
    void 返回体里的敏感字段要报出来() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanSecurity(
                ep(List.of()),
                List.of(fld("user.token", "token", "String"), fld("user.nick", "nick", "String")));

        assertEquals(1, out.size());
        assertTrue(out.get(0).title().contains("token"), out.get(0).title());
        assertFalse(out.get(0).title().contains("nick"));
    }

    @Test
    void 下划线与大小写不影响敏感字段识别() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanSecurity(
                ep(List.of()), List.of(fld("a.id_card", "id_card", "String")));

        assertEquals(1, out.size());
    }

    // ==================== SQL 注入 ====================

    /** MyBatis 分页常写成 ${offset}/${limit}，值若来自入参就能注入 */
    @Test
    void mapper里的美元花括号要报出来() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanSqlInjection("M.xml",
                "<select id=\"p\">select * from t limit ${offset}, ${limit}</select>");

        assertEquals(1, out.size());
        assertTrue(out.get(0).title().contains("${offset}"), out.get(0).title());
        assertTrue(out.get(0).title().contains("#{}"), "要给出正确写法：" + out.get(0).title());
    }

    /**
     * 这一条最要紧。MyBatis 逆向生成器的 Example 里全是 ${criterion.condition}，
     * logback 配置里全是 ${LOG_PATH}——照单全收就是 83 个文件的噪音。
     */
    @Test
    void 生成器样板与配置占位不算注入() {
        assertTrue(ApiRiskScanner.scanSqlInjection("X.xml",
                "<if>${criterion.condition}</if><if>${criterion.value}</if>").isEmpty());
        assertTrue(ApiRiskScanner.scanSqlInjection("X.xml", "<file>${LOG_PATH}/a.log</file>").isEmpty());
        assertTrue(ApiRiskScanner.scanSqlInjection("X.xml", "order by ${orderByClause}").isEmpty());
    }

    @Test
    void 只用预编译占位的不报() {
        assertTrue(ApiRiskScanner.scanSqlInjection("M.xml",
                "<select>select * from t where id = #{id}</select>").isEmpty());
        assertTrue(ApiRiskScanner.scanSqlInjection("M.xml", null).isEmpty());
    }

    // ==================== 字段设计 ====================

    /** 跟表那边同一个坑，但这里能精确到字段路径 */
    @Test
    void Long字段提示前端精度问题() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanFieldDesign("GET /a",
                List.of(fld("steamId", "steamId", "Long")));

        assertEquals(1, out.size());
        assertTrue(out.get(0).title().contains("2^53"), out.get(0).title());
    }

    @Test
    void 金额用浮点要报出来() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanFieldDesign("GET /a",
                List.of(fld("totalAmount", "totalAmount", "Double")));

        assertTrue(out.stream().anyMatch(f -> f.title().contains("浮点")), out.toString());
    }

    /** 非金额语义的 Double 不该报——不然每个比率、坐标都会被标 */
    @Test
    void 非金额的浮点不报() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanFieldDesign("GET /a",
                List.of(fld("winRate", "winRate", "Double")));

        assertTrue(out.stream().noneMatch(f -> f.title().contains("浮点")), out.toString());
    }

    @Test
    void 时间字段类型混用时提示() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanFieldDesign("GET /a",
                List.of(fld("startTime", "startTime", "Date"),
                        fld("endTime", "endTime", "String"),
                        fld("createTimeStamp", "createTimeStamp", "long")));

        assertTrue(out.stream().anyMatch(f -> f.title().contains("时间字段类型不统一")), out.toString());
    }

    @Test
    void 时间字段类型一致时不报() {
        List<ApiRiskScanner.Finding> out = ApiRiskScanner.scanFieldDesign("GET /a",
                List.of(fld("startTime", "startTime", "Date"), fld("endTime", "endTime", "Date")));

        assertTrue(out.stream().noneMatch(f -> f.title().contains("时间字段类型不统一")), out.toString());
    }

    @Test
    void 没有字段时什么都不报() {
        assertTrue(ApiRiskScanner.scanFieldDesign("GET /a", List.of()).isEmpty());
        assertTrue(ApiRiskScanner.scanFieldDesign("GET /a", null).isEmpty());
        assertTrue(ApiRiskScanner.scanSecurity(null, List.of()).isEmpty());
    }
}
