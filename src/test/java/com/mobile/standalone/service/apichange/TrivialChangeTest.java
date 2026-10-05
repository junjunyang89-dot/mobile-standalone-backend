package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrivialChangeTest {

    private static final String MAPPER_BEFORE = """
            package x;

            public interface DemoMapper {
                @Select({"SELECT 1"})
                int upsert(@Param("matchId") Long matchId);

                @Select({
                        "SELECT url FROM t",
                        "WHERE match_id = #{matchId} LIMIT 1"
                })
                Demo selectJsonUrl(@Param("matchId") Long matchId);

                /** 批量查询 */
                @Select({
                        "<script>",
                        "SELECT match_id FROM t WHERE match_id IN",
                        "<foreach collection='ids' item='mid' open='(' separator=',' close=')'>#{mid}</foreach>",
                        "</script>"
                })
                List<Long> selectExisting(@Param("ids") List<Long> ids);
            }
            """;

    private static final String MAPPER_AFTER = """
            package x;

            public interface DemoMapper {
                @Select({"SELECT 1"})
                int upsert(@Param("matchId") Long matchId);

                /** 批量查询（挪上来了） */
                @Select({
                        "<script>",
                        "SELECT match_id FROM t WHERE match_id IN",
                        "<foreach collection='ids' item='mid' open='(' separator=',' close=')'>#{mid}</foreach>",
                        "</script>"
                })
                List<Long> selectExisting(@Param("ids") List<Long> ids);

                @Select({
                        "SELECT url FROM t",
                        "WHERE match_id = #{matchId} LIMIT 1"
                })
                Demo selectJsonUrl(@Param("matchId") Long matchId);
            }
            """;

    @Test
    void 整个方法挪了位置() {
        assertEquals("只是挪了方法位置，内容没变", ApiChangeAnalyzer.trivialReason(MAPPER_BEFORE, MAPPER_AFTER));
    }

    @Test
    void 只改注释和import() {
        String b = "import a.B;\nclass X {\n  // old\n  void f() { String u = \"http://x\"; }\n}\n";
        String a = "import a.B;\nimport a.C;\nclass X {\n  /* new */\n  void f() {   String u = \"http://x\"; }\n}\n";
        assertEquals("只改了注释、空行或 import，逻辑没变", ApiChangeAnalyzer.trivialReason(b, a));
    }

    @Test
    void 只加了一行多行日志() {
        String b = """
                class S {
                    void run() {
                        String r = post(url, body);
                        handle(r);
                    }
                }
                """;
        String a = """
                class S {
                    void run() {
                        String r = post(url, body);
                        logger.info("addTask response {} {}",
                                url, r);
                        handle(r);
                    }
                }
                """;
        assertEquals("只增删或改了日志，逻辑没变", ApiChangeAnalyzer.trivialReason(b, a));
    }

    @Test
    void 语句换顺序是实质改动() {
        String b = "class S {\n  void f() {\n    a();\n    b();\n  }\n}\n";
        String a = "class S {\n  void f() {\n    b();\n    a();\n  }\n}\n";
        assertNull(ApiChangeAnalyzer.trivialReason(b, a));
    }

    @Test
    void 改了逻辑是实质改动() {
        String b = "class S {\n  int f() {\n    return 1;\n  }\n}\n";
        String a = "class S {\n  int f() {\n    log.info(\"x\");\n    return 2;\n  }\n}\n";
        assertNull(ApiChangeAnalyzer.trivialReason(b, a));
    }

    @Test
    void 两边一字不差说明已合进基线() {
        assertEquals("基线里已经是同一份代码（多半已合进基线），没有差异",
                ApiChangeAnalyzer.trivialReason("class A {}\r\n", "class A {}\n"));
    }

    @Test
    void 新增或删除的文件不判断() {
        assertNull(ApiChangeAnalyzer.trivialReason(null, "class A {}"));
    }
}
