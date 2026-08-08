package com.mobile.standalone.service.apichange;

import com.mobile.standalone.service.apichange.ApiChangeAnalyzer.FieldChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段级 diff 与仓库校验。
 * <p>
 * 最要紧的是 <b>TYPE_CHANGED 必须单独成一类</b>：字段名还在、抓包看着一切正常，
 * 但 {@code String -> int} 会让前端按老类型解析时静默出错。这类问题最难定位到后端，
 * 只报「新增/删除」两类的话它会被完全漏掉——这也正是在 global-data 真实分支上抓到的那种。
 */
class ApiChangeAnalyzerTest {

    private final ApiChangeAnalyzer analyzer = new ApiChangeAnalyzer();

    private static DtoField f(String name, String type) {
        return new DtoField(name, type, null);
    }

    private static DtoField f(String name, String type, String comment) {
        return new DtoField(name, type, comment);
    }

    private static FieldChange find(List<FieldChange> list, String name) {
        return list.stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    // ==================== 字段 diff ====================

    @Test
    void 新增字段带出中文名() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("id", "Long")),
                List.of(f("id", "Long"), f("winRate", "String", "胜率")));

        assertEquals(1, out.size());
        assertEquals("ADDED", out.get(0).getChangeType());
        assertEquals("winRate", out.get(0).getName());
        assertEquals("胜率", out.get(0).getComment(), "javadoc 的中文名要带出来，只给英文名没法一眼看懂");
        assertEquals("String", out.get(0).getNewType());
    }

    @Test
    void 删除字段能认出来() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("id", "Long"), f("oldField", "String")),
                List.of(f("id", "Long")));

        assertEquals("REMOVED", find(out, "oldField").getChangeType());
        assertEquals("String", find(out, "oldField").getOldType());
    }

    /**
     * 本组核心。真实案例：global-data 的 {@code /gld/deadlock/match/detail}
     * 把 {@code matchMode} 从 String 改成了 int——字段还在，抓包也看不出问题。
     */
    @Test
    void 类型变更单独成一类而不是当成没变() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("matchMode", "String")),
                List.of(f("matchMode", "int")));

        assertEquals(1, out.size());
        FieldChange c = out.get(0);
        assertEquals("TYPE_CHANGED", c.getChangeType());
        assertEquals("String", c.getOldType());
        assertEquals("int", c.getNewType());
    }

    @Test
    void 泛型类型变化也算类型变更() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("ids", "List<Integer>")),
                List.of(f("ids", "List<Long>")));

        assertEquals("TYPE_CHANGED", out.get(0).getChangeType());
    }

    @Test
    void 没有变化时不报任何东西() {
        List<DtoField> same = List.of(f("id", "Long"), f("name", "String"));

        assertTrue(ApiChangeAnalyzer.diffFields(same, same).isEmpty());
    }

    /** 新增文件（基线侧为空）时，所有字段都算新增 */
    @Test
    void 新建返回体的字段全部算新增() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(), List.of(f("a", "Long"), f("b", "String")));

        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(c -> "ADDED".equals(c.getChangeType())));
    }

    @Test
    void 删除文件时所有字段都算删除() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("a", "Long")), List.of());

        assertEquals("REMOVED", out.get(0).getChangeType());
    }

    /** 只改了注释不算字段变更，否则一次批量补注释会刷出几百条噪音 */
    @Test
    void 只改注释不算变更() {
        List<FieldChange> out = ApiChangeAnalyzer.diffFields(
                List.of(f("id", "Long", "主键")),
                List.of(f("id", "Long", "主键ID")));

        assertTrue(out.isEmpty(), out.toString());
    }

    // ==================== 目录对目录（拿不到内网时用） ====================

    /**
     * 两个目录常来自不同机器：一份 LF、一份 CRLF。不抹平行尾就是「每个文件都变了」——
     * 实测 data-center vs APP-8928 报出 1149 个变更文件、83 张表，报告直接失去意义。
     * 这不是代码差异，是换行符差异。
     */
    @Test
    void 行尾差异不算代码差异() {
        String lf = "package a;\nclass B {\n}\n";
        String crlf = "package a;\r\nclass B {\r\n}\r\n";

        assertEquals(ApiChangeAnalyzer.normalizeText(lf), ApiChangeAnalyzer.normalizeText(crlf));
    }

    @Test
    void BOM也要抹掉() {
        assertEquals("package a;", ApiChangeAnalyzer.normalizeText("\uFEFFpackage a;"));
        assertNull(ApiChangeAnalyzer.normalizeText(null));
    }

    /** 没有 git 时靠按行取差集认新增内容，用来找表名和 DDL */
    @Test
    void 按行取差集只留新增的行() {
        String added = ApiChangeAnalyzer.diffAddedByLine(
                "select * from app_account\n",
                "select * from app_account\ninsert into app_user_follow (id) values (1)\n");

        assertTrue(added.contains("app_user_follow"), added);
        assertTrue(!added.contains("app_account"), "没变的行不该算新增：" + added);
    }

    @Test
    void 基线侧文件不存在时整份都算新增() {
        String added = ApiChangeAnalyzer.diffAddedByLine(null, "create table t (id int)\n");

        assertTrue(added.contains("create table t"), added);
    }

    /**
     * 拷过来的目录常多套一层工程根（APP-8928/data-center/pom.xml），
     * 让人自己记这层嵌套只会填错路径。
     */
    @Test
    void 自动定位到有pom的工程根(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path nested = tmp.resolve("data-center");
        java.nio.file.Files.createDirectories(nested);
        java.nio.file.Files.writeString(nested.resolve("pom.xml"), "<project/>");

        assertEquals(nested, ApiChangeAnalyzer.projectRoot(tmp));
        assertEquals(nested, ApiChangeAnalyzer.projectRoot(nested), "已经是工程根就别再下沉");
    }

    @Test
    void 找不到工程根时按原路径处理(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        assertEquals(tmp, ApiChangeAnalyzer.projectRoot(tmp));
    }

    @Test
    void 目录不存在时报出是哪一侧() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyzeDirs("p", "/tmp/no-such-base-12345", "/tmp/no-such-head-12345"));

        assertTrue(e.getMessage().contains("基线目录"), e.getMessage());
    }

    // ==================== 仓库校验：报错要能照着改 ====================

    @Test
    void 没配仓库路径时提示去哪儿配() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("p", null, "master", "dev"));

        assertTrue(e.getMessage().contains("仓库路径"), e.getMessage());
    }

    @Test
    void 目录不存在时报出路径() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("p", "/tmp/definitely-not-here-12345", "master", "dev"));

        assertTrue(e.getMessage().contains("目录不存在"), e.getMessage());
    }

    /** 普通目录不是 git 仓，要说清楚缺什么 */
    @Test
    void 不是git仓库时说明原因() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("p", System.getProperty("java.io.tmpdir"), "master", "dev"));

        assertTrue(e.getMessage().contains(".git"), e.getMessage());
    }

    // ==================== 风险分级 ====================

    private static ApiChangeAnalyzer.EndpointChange ep(String changeType) {
        ApiChangeAnalyzer.EndpointChange e = new ApiChangeAnalyzer.EndpointChange();
        e.setChangeType(changeType);
        return e;
    }

    private static FieldChange fc(String type) {
        FieldChange c = new FieldChange();
        c.setChangeType(type);
        return c;
    }

    private static ApiChangeAnalyzer.ParamChange pc(String type, boolean required) {
        ApiChangeAnalyzer.ParamChange c = new ApiChangeAnalyzer.ParamChange();
        c.setChangeType(type);
        c.setRequired(required);
        return c;
    }

    /**
     * 新接口的入参当然「全是新增必填」，但没有老客户端在调它。
     * 判成不兼容会把真正的破坏性变更淹掉——APP-8928 上 6 个新接口全被标红，就是这么来的。
     */
    @Test
    void 新接口不算不兼容() {
        ApiChangeAnalyzer.EndpointChange e = ep("ADDED");
        e.setParams(List.of(pc("ADDED", true)));

        assertEquals("NEW", ApiChangeAnalyzer.riskOf(e));
    }

    /** 新增一个必填入参，老客户端不带它就是直接 400，比返回体多个字段严重得多 */
    @Test
    void 已有接口新增必填入参算不兼容() {
        ApiChangeAnalyzer.EndpointChange e = ep("MODIFIED");
        e.setParams(List.of(pc("ADDED", true)));

        assertEquals("BREAKING", ApiChangeAnalyzer.riskOf(e));
    }

    @Test
    void 新增可选入参不算不兼容() {
        ApiChangeAnalyzer.EndpointChange e = ep("MODIFIED");
        e.setParams(List.of(pc("ADDED", false)));

        assertEquals("COMPATIBLE", ApiChangeAnalyzer.riskOf(e));
    }

    @Test
    void 删字段与改类型都算不兼容() {
        ApiChangeAnalyzer.EndpointChange removed = ep("MODIFIED");
        removed.setFields(List.of(fc("REMOVED")));
        assertEquals("BREAKING", ApiChangeAnalyzer.riskOf(removed));

        ApiChangeAnalyzer.EndpointChange typed = ep("MODIFIED");
        typed.setFields(List.of(fc("TYPE_CHANGED")));
        assertEquals("BREAKING", ApiChangeAnalyzer.riskOf(typed));
    }

    @Test
    void 只加返回字段是兼容变更() {
        ApiChangeAnalyzer.EndpointChange e = ep("MODIFIED");
        e.setFields(List.of(fc("ADDED")));

        assertEquals("COMPATIBLE", ApiChangeAnalyzer.riskOf(e));
    }

    /**
     * 签名和返回体都没变、只有实现改了——这一档单独存在的意义是：
     * 不标出来的话这类接口在报告里一个字都不会出现，人会以为不用测。
     */
    @Test
    void 只有实现改了单独归一档() {
        ApiChangeAnalyzer.EndpointChange e = ep("MODIFIED");
        e.setChangedImpls(List.of("DeadlockWeeklyServiceImpl"));

        assertEquals("IMPL", ApiChangeAnalyzer.riskOf(e));
    }

    // ==================== 入参 diff ====================

    @Test
    void 入参类型变化与必填变化都要报() {
        List<ApiChangeAnalyzer.ParamChange> out = ApiChangeAnalyzer.diffParams(
                List.of(new ApiParam("QUERY", "mode", "int", true),
                        new ApiParam("QUERY", "keyword", "String", false)),
                List.of(new ApiParam("QUERY", "mode", "String", true),
                        new ApiParam("QUERY", "keyword", "String", true)));

        assertEquals("TYPE_CHANGED", out.stream()
                .filter(c -> "mode".equals(c.getName())).findFirst().orElseThrow().getChangeType());
        assertEquals("REQUIRED_CHANGED", out.stream()
                .filter(c -> "keyword".equals(c.getName())).findFirst().orElseThrow().getChangeType(),
                "可选改必填同样会打挂老客户端");
    }

    @Test
    void 同名但来源不同的入参算两个() {
        List<ApiChangeAnalyzer.ParamChange> out = ApiChangeAnalyzer.diffParams(
                List.of(new ApiParam("QUERY", "id", "Long", true)),
                List.of(new ApiParam("PATH", "id", "Long", true)));

        assertEquals(2, out.size(), "从 query 挪到 path 是接口形态变了，不能当成没变");
    }
}
