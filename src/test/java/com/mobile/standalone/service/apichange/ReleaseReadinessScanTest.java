package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发版前要准备的东西：表要先加的列、各环境要先配的配置，以及别把「拆中间变量」误报成「公式改了」。
 * 样例取自 comment-service 本地-1005-0050：给 comment_score 记得分明细。
 */
class ReleaseReadinessScanTest {

    private static final String SVC = "src/main/java/a/service/impl/CommonServiceImpl.java";

    private static final String CALC_BEFORE = """
            package a;
            @Service
            public class CommonServiceImpl {
                @Value("#{'${comment.score.factor.list}'.split(',')}")
                private List<Double> factors;

                public int calculateCommentScore(int likeCount, int replyCount, double quality) {
                    double score = (quality * factors.get(0) + likeCount * factors.get(1) + replyCount * factors.get(2)) * factors.get(3);
                    return (int) score;
                }

                public int other() {
                    return 1;
                }
            }
            """;

    private static final String CALC_AFTER = """
            package a;
            @Service
            public class CommonServiceImpl {
                @Value("#{'${comment.score.factor.list}'.split(',')}")
                private List<Double> factors;

                public int calculateCommentScore(int likeCount, int replyCount, double quality) {
                    double q = quality * factors.get(0);
                    double like = likeCount * factors.get(1);
                    double reply = replyCount * factors.get(2);
                    double score = (q + like + reply) * factors.get(3);
                    detail.setQuality(q * factors.get(3) * 100);
                    detail.setReport(factors.get(4) * 100);
                    return (int) score;
                }

                public int other() {
                    return 1;
                }
            }
            """;

    @Test
    void 公式只是拆成几个变量_不报公式改了_报多算了几项() {
        List<MethodLogicDiff.Change> cs = MethodLogicDiff.diff(SVC, CALC_BEFORE, CALC_AFTER);
        assertTrue(cs.stream().noneMatch(c -> "FORMULA".equals(c.getKind())), cs.toString());
        MethodLogicDiff.Change calc = cs.stream().filter(c -> "CALC".equals(c.getKind())).findFirst().orElseThrow();
        assertEquals("多算了几项，原来的结果没变", calc.getTitle());
        assertTrue(calc.getAfter().contains("setQuality"), calc.getAfter());
    }

    @Test
    void 列表配置多取了一个下标_提示至少要配几个值() {
        List<MethodLogicDiff.Change> cs = MethodLogicDiff.diff(SVC, CALC_BEFORE, CALC_AFTER);
        MethodLogicDiff.Change c = cs.stream().filter(x -> x.getNeedCount() != null).findFirst().orElseThrow(() -> new AssertionError(cs));
        assertEquals(5, c.getNeedCount());
        assertEquals("comment.score.factor.list", c.getRef());
        assertEquals("配置 comment.score.factor.list 至少要配 5 个值", c.getTitle());
    }

    @Test
    void 只改了一个方法_没调它的方法不算动到() {
        Set<String> touched = MethodLogicDiff.touchedMethods(CALC_BEFORE, CALC_AFTER);
        assertTrue(touched.contains("calculateCommentScore"), String.valueOf(touched));
        assertFalse(touched.contains("other"), String.valueOf(touched));
    }

    @Test
    void 调用了改动方法的同类方法也算动到() {
        String before = CALC_BEFORE.replace("return 1;", "return calculateCommentScore(1, 1, 1);");
        String after = CALC_AFTER.replace("return 1;", "return calculateCommentScore(1, 1, 1);");
        assertTrue(MethodLogicDiff.touchedMethods(before, after).contains("other"));
    }

    // ==================== 新列 ====================

    private static final String ENTITY = "src/main/java/a/model/CommentScore.java";

    @Test
    void 表实体多了字段_算新列() {
        String was = """
                package a;
                @TableName("comment_score")
                public class CommentScore {
                    private Long id;
                    private Integer newScore;
                }
                """;
        String now = was.replace("private Integer newScore;", """
                private Integer newScore;
                    private String scoreDetail;
                    @TableField(exist = false)
                    private String tmp;
                    private static final long serialVersionUID = 1L;
                """);
        List<NewColumnScanner.Hit> hits = NewColumnScanner.scan(List.of(ENTITY), f -> was, f -> now);
        assertEquals(1, hits.size(), hits.toString());
        assertEquals("comment_score", hits.get(0).table());
        assertEquals("score_detail", hits.get(0).column());
    }

    @Test
    void mapper的resultMap和insert多了列_算新列() {
        String was = """
                <mapper namespace="a.CommentScoreMapper">
                  <resultMap id="BaseResultMap" type="a.CommentScore">
                    <id column="id" property="id"/>
                    <result column="new_score" property="newScore"/>
                  </resultMap>
                  <insert id="insert">insert into comment_score (id, new_score) values (#{id}, #{newScore})</insert>
                </mapper>
                """;
        String now = was.replace("<result column=\"new_score\" property=\"newScore\"/>",
                        "<result column=\"new_score\" property=\"newScore\"/>\n<result column=\"score_detail\" property=\"scoreDetail\"/>")
                .replace("(id, new_score)", "(id, new_score, score_detail)");
        List<NewColumnScanner.Hit> hits = NewColumnScanner.scan(List.of("src/main/resources/mapper/CommentScoreMapper.xml"),
                f -> was, f -> now);
        assertEquals(List.of("comment_score.score_detail"), hits.stream().map(h -> h.table() + "." + h.column()).toList());
    }

    @Test
    void 新建的实体不看_分不清是不是新表() {
        assertTrue(NewColumnScanner.scan(List.of(ENTITY), f -> null, f -> "@TableName(\"x\") class X { private String a; }").isEmpty());
    }

    @Test
    void 分支SQL里有ALTER或建表带这一列_算有SQL() {
        assertTrue(NewColumnScanner.declaredIn("ALTER TABLE comment_score ADD COLUMN score_detail text;", "comment_score", "score_detail"));
        assertTrue(NewColumnScanner.declaredIn("CREATE TABLE `comment_score` (\n `id` bigint,\n `score_detail` text\n);", "comment_score", "score_detail"));
        assertFalse(NewColumnScanner.declaredIn("ALTER TABLE other ADD COLUMN score_detail text;", "comment_score", "score_detail"));
    }

    // ==================== 配置在各环境有没有 ====================

    private static MethodLogicDiff.Change cfg(String key, boolean noDefault, Integer need) {
        MethodLogicDiff.Change c = new MethodLogicDiff.Change();
        c.setKind("CONFIG");
        c.setTitle("新增配置项 " + key);
        c.setRef(key);
        c.setNoDefault(noDefault);
        c.setNeedCount(need);
        return c;
    }

    @Test
    void 各环境配置文件逐个查_dev配了prod没配() {
        Map<String, String> files = Map.of(
                "src/main/resources/application.properties", "server.port=8080\n",
                "src/main/resources/application-dev.properties", "comment.score.factorList=1,2,3,4,5,6,7\nfeature.on=true\n",
                "src/main/resources/application-prod.yml", "comment:\n  score:\n    factor_list: 1,2,3,4,5,6\n");
        List<ConfigPresence> ps = ConfigPresence.of(
                List.of(cfg("comment.score.factor-list", false, 7), cfg("feature.on", true, null)),
                List.copyOf(files.keySet()), files::get);
        ConfigPresence list = ps.stream().filter(p -> p.getKey().startsWith("comment")).findFirst().orElseThrow();
        assertEquals(7, list.getNeedCount());
        assertEquals(7, ConfigPresence.count(list.getProfiles().get("dev")));
        assertEquals(6, ConfigPresence.count(list.getProfiles().get("prod")));

        ConfigPresence on = ps.stream().filter(p -> p.getKey().equals("feature.on")).findFirst().orElseThrow();
        assertTrue(on.isNoDefault());
        assertEquals("true", on.getProfiles().get("dev"));
        assertNull(on.getProfiles().get("prod"));
        assertTrue(on.getProfiles().containsKey("prod"));
    }

    @Test
    void 仓库里没有配置文件_不当成都配好了() {
        List<ConfigPresence> ps = ConfigPresence.of(List.of(cfg("feature.on", true, null)), List.of(), f -> null);
        assertTrue(ps.get(0).getProfiles().isEmpty());
    }

    @Test
    void 测试目录和target里的配置文件不算() {
        assertTrue(ConfigPresence.isConfigFile("src/main/resources/application-prod.yml"));
        assertFalse(ConfigPresence.isConfigFile("src/test/resources/application.yml"));
        assertFalse(ConfigPresence.isConfigFile("svc/target/classes/application.properties"));
    }
}
