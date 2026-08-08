package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口完整字段清单（含嵌套）。
 * <p>
 * 它回答的是「这个接口到底有哪些字段、每个什么意思」——测试拿着抓包结果对字段时要的是这个，
 * 而不是只知道「这次改了什么」。
 * <p>
 * 最要紧的两条：<b>@JsonIgnore 的字段不能列</b>（列出来人会对着抓包找一个永远找不到的东西），
 * <b>@JsonProperty 改过名要按改后的显示</b>（抓包看到的是那个名字）。这两条错了，
 * 整份字段表就跟抓包对不上，而对不上比没有更糟——人会连带怀疑整份报告。
 */
class FieldTreeBuilderTest {

    private final Map<String, String> sources = new HashMap<>();

    private java.util.function.Function<String, String> locate() {
        return sources::get;
    }

    private List<FieldTreeBuilder.FieldNode> build(String root) {
        return FieldTreeBuilder.build(root, locate(), Map.of());
    }

    @Test
    void 列出全部字段并带中文名() {
        sources.put("UserVO", """
                @Data
                public class UserVO {
                    /** 用户ID */
                    private Long userId;
                    /** 昵称 */
                    private String name;
                }
                """);

        List<FieldTreeBuilder.FieldNode> out = build("UserVO");

        assertEquals(2, out.size());
        assertEquals("userId", out.get(0).getPath());
        assertEquals("用户ID", out.get(0).getComment());
        assertEquals("Long", out.get(0).getType());
    }

    /** @JsonIgnore 的字段压根不出现在 JSON 里 */
    @Test
    void JsonIgnore的字段不列出来() {
        sources.put("A", """
                @Data
                public class A {
                    private Long id;
                    @JsonIgnore
                    private String secret;
                }
                """);

        List<FieldTreeBuilder.FieldNode> out = build("A");

        assertEquals(1, out.size());
        assertFalse(out.stream().anyMatch(f -> "secret".equals(f.getName())), out.toString());
    }

    /** 抓包看到的是 @JsonProperty 改后的名字，不是 Java 字段名 */
    @Test
    void JsonProperty改名后按改后的显示() {
        sources.put("A", """
                @Data
                public class A {
                    @JsonProperty("steam_id")
                    private Long steamId;
                }
                """);

        assertEquals("steam_id", build("A").get(0).getName());
    }

    // ==================== 嵌套 ====================

    @Test
    void 嵌套对象展开且路径带层级() {
        sources.put("Outer", """
                @Data
                public class Outer {
                    /** 用户 */
                    private UserVO user;
                }
                """);
        sources.put("UserVO", """
                @Data
                public class UserVO {
                    /** 昵称 */
                    private String name;
                }
                """);

        List<FieldTreeBuilder.FieldNode> out = build("Outer");

        assertEquals("user", out.get(0).getPath());
        assertEquals("user.name", out.get(1).getPath());
        assertEquals(1, out.get(1).getDepth(), "缩进要能看出层级");
    }

    /** 数组要标 []，否则抓包时分不清是对象还是列表 */
    @Test
    void 集合字段路径带方括号() {
        sources.put("Outer", """
                @Data
                public class Outer {
                    private List<UserVO> users;
                }
                """);
        sources.put("UserVO", "@Data\npublic class UserVO { private String name; }");

        List<FieldTreeBuilder.FieldNode> out = build("Outer");

        assertEquals("users[]", out.get(0).getPath());
        assertEquals("users[].name", out.get(1).getPath());
    }

    /** 对象图里互相引用很常见（用户里有帖子、帖子里有用户），没有环检测会栈溢出 */
    @Test
    void 互相引用不会无限展开() {
        sources.put("A", "@Data\npublic class A { private B b; }");
        sources.put("B", "@Data\npublic class B { private A a; }");

        List<FieldTreeBuilder.FieldNode> out = build("A");

        assertTrue(out.size() < 10, "环没断开：" + out.size());
    }

    @Test
    void 找不到类时安全返回() {
        assertTrue(build("NotExist").isEmpty());
        assertTrue(build(null).isEmpty());
    }

    @Test
    void 基本类型不再往下展开() {
        sources.put("A", "@Data\npublic class A { private String name; private Integer age; }");

        assertEquals(2, build("A").size());
    }

    // ==================== 标出本次变更 ====================

    /** 一张表就能回答「有哪些字段」和「哪些是这次改的」，不用两个列表来回对照 */
    @Test
    void 本次变更的字段在完整清单里标出来() {
        sources.put("A", "@Data\npublic class A { private String a; private String b; }");

        List<FieldTreeBuilder.FieldNode> out =
                FieldTreeBuilder.build("A", locate(), Map.of("b", "ADDED"));

        assertEquals(null, out.get(0).getChangeType());
        assertEquals("ADDED", out.get(1).getChangeType());
    }

    // ==================== 类型剥离 ====================

    @Test
    void 从容器类型里取出元素类型() {
        assertEquals("UserVO", FieldTreeBuilder.elementTypeOf("List<UserVO>"));
        assertEquals("UserVO", FieldTreeBuilder.elementTypeOf("Map<String,UserVO>"));
        assertEquals("UserVO", FieldTreeBuilder.elementTypeOf("List<Map<String,UserVO>>"));
        assertEquals("UserVO", FieldTreeBuilder.elementTypeOf("UserVO[]"));
        assertEquals("String", FieldTreeBuilder.elementTypeOf("String"));
    }
}
