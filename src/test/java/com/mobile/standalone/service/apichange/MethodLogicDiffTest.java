package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方法级逻辑对比。样例取自 comment-service「评论得分计算规则调整」：
 * 文件级只能报「CommonServiceImpl 改了」，测试拿着这句话不知道测什么。
 */
class MethodLogicDiffTest {

    private static final String FILE = "src/main/java/a/service/impl/CommonServiceImpl.java";

    private static final String BEFORE = """
            package a;
            @Service
            public class CommonServiceImpl implements CommonService {
                @Value("#{'${comment.score.factor.list:0.5,1.0,1.0,0.5,100.0,0.05}'.split(',')}")
                private List<Double> commentScoreFactorList;

                private static final String SCORE_KEY = "comment:score:";

                @Override
                public int calculateCommentScore(int likeCount, int replyCount, int timeFactor) {
                    if (commentScoreFactorList.size() < 6) {
                        logger.error("invalid");
                        return 0;
                    }
                    double pow = 0.1;
                    if (timeFactor <= 44) {
                        pow = Math.pow(1 - commentScoreFactorList.get(5), timeFactor);
                    }
                    double score = (likeCount * commentScoreFactorList.get(0) + replyCount * commentScoreFactorList.get(1)) * pow;
                    return (int) (score * 100);
                }

                @Transactional
                public void save(Comment c) {
                    commentMapper.insert(c);
                    redisUtil.delete(SCORE_KEY + c.getId());
                }

                public void old() {
                }
            }
            """;

    private static final String AFTER = """
            package a;
            @Service
            public class CommonServiceImpl implements CommonService {
                @Value("#{'${comment.score.factor.list:1.0,0.5,1.0,0.5,100.0,0.95,0.03}'.split(',')}")
                private List<Double> commentScoreFactorList;

                @Value("${comment.score.enabled:true}")
                private boolean enabled;

                private static final String SCORE_KEY = "comment:score:v2:";

                @Override
                public int calculateCommentScore(int likeCount, int replyCount, int timeFactor) {
                    if (likeCount < 0 || replyCount < 0 || timeFactor < 0) {
                        return 0;
                    }
                    if (commentScoreFactorList.size() < 7) {
                        logger.error("invalid");
                        return 0;
                    }
                    double interaction = Math.log(1 + likeCount) * commentScoreFactorList.get(0)
                            + Math.log(1 + replyCount) * commentScoreFactorList.get(1);
                    double decay = commentScoreFactorList.get(6) + (1 - commentScoreFactorList.get(6)) * Math.pow(commentScoreFactorList.get(5), timeFactor);
                    double score = interaction * decay;
                    return (int) (score * 100);
                }

                public void save(Comment c) {
                    commentMapper.insert(c);
                    commentLogMapper.insert(c.toLog());
                    if (c.getContent() == null) {
                        throw new BizException("内容不能为空");
                    }
                }

                @Scheduled(cron = "0 0 3 * * ?")
                public void recalc() {
                    commentMapper.updateScoreBatch();
                }
            }
            """;

    private final List<MethodLogicDiff.Change> changes = MethodLogicDiff.diff(FILE, BEFORE, AFTER);

    private List<MethodLogicDiff.Change> of(String member, String kind) {
        return changes.stream().filter(c -> member.equals(c.getMember()) && kind.equals(c.getKind())).toList();
    }

    private MethodLogicDiff.Change one(String member, String kind, String titlePart) {
        return of(member, kind).stream().filter(c -> c.getTitle().contains(titlePart)).findFirst()
                .orElseThrow(() -> new AssertionError(member + " " + kind + " " + titlePart + " 没找到：\n" + changes));
    }

    /** 本组核心：配置值个数变了，配置中心还是旧值时新代码直接走异常分支——这条不报出来就是线上事故 */
    @Test
    void 配置项默认值个数变了要单独点出来() {
        MethodLogicDiff.Change c = one("commentScoreFactorList", "CONFIG", "默认值改了");
        assertTrue(c.getHint().contains("6 → 7"), c.getHint());
        assertTrue(c.getHint().contains("配置中心"), c.getHint());
    }

    @Test
    void 新增配置项提示各环境要不要配() {
        MethodLogicDiff.Change c = one("enabled", "CONFIG", "新增配置项");
        assertTrue(c.getTitle().contains("comment.score.enabled"), c.getTitle());
    }

    @Test
    void 判断条件的阈值变了报成一对并给出边界值() {
        MethodLogicDiff.Change c = one("calculateCommentScore", "CONDITION", "判断条件改了");
        assertEquals("commentScoreFactorList.size() < 6", c.getBefore());
        assertEquals("commentScoreFactorList.size() < 7", c.getAfter());
        assertTrue(c.getHint().contains("6 和 7"), c.getHint());
    }

    @Test
    void 新增的校验给出负数边界() {
        MethodLogicDiff.Change c = one("calculateCommentScore", "CONDITION", "新增判断");
        assertTrue(c.getHint().contains("likeCount 取 -1 和 0"), c.getHint());
    }

    /** 44 天的上限去掉了：这是行为变化，原来超过 44 天的评论现在怎么算要有人确认 */
    @Test
    void 去掉的判断要找开发确认() {
        MethodLogicDiff.Change c = one("calculateCommentScore", "CONDITION", "去掉了判断");
        assertEquals("timeFactor <= 44", c.getBefore());
    }

    @Test
    void 公式改了要按需求手算对比() {
        MethodLogicDiff.Change c = one("calculateCommentScore", "FORMULA", "计算方式");
        assertTrue(c.getAfter().contains("Math.log"), c.getAfter());
        assertTrue(c.getHint().contains("手算"), c.getHint());
    }

    @Test
    void 每个改了的方法都有改动行兜底() {
        MethodLogicDiff.Change c = one("calculateCommentScore", "METHOD_CHANGED", "改了");
        assertNotNull(c.getAfter());
    }

    @Test
    void 缓存key常量变了按缓存问题提示() {
        MethodLogicDiff.Change c = one("SCORE_KEY", "CONSTANT", "SCORE_KEY");
        assertTrue(c.getHint().contains("缓存"), c.getHint());
    }

    @Test
    void 去掉事务_新增写库_去掉缓存操作_新增报错() {
        one("save", "TRANSACTION", "去掉了事务");
        assertTrue(one("save", "WRITE", "新增写库").getAfter().contains("commentLogMapper.insert"));
        assertTrue(one("save", "CACHE", "去掉了缓存操作").getBefore().contains("redisUtil.delete"));
        assertTrue(one("save", "EXCEPTION", "新增报错").getAfter().contains("内容不能为空"));
    }

    @Test
    void 新增定时任务方法提示手动触发() {
        MethodLogicDiff.Change c = one("recalc", "METHOD_ADDED", "新增方法");
        assertTrue(c.getHint().contains("定时任务"), c.getHint());
        assertTrue(c.getHint().contains("commentMapper.updateScoreBatch"), c.getHint());
    }

    @Test
    void 删掉的方法提示按名字引用的地方() {
        one("old", "METHOD_REMOVED", "删除了方法");
    }

    @Test
    void 没改的方法不报() {
        List<MethodLogicDiff.Change> same = MethodLogicDiff.diff(FILE, BEFORE, BEFORE);
        assertTrue(same.isEmpty(), same.toString());
    }

    /** 只加了一个参数的方法算修改，不能报成「删一个加一个」，否则丢了新旧对比 */
    @Test
    void 签名改了的同名方法按修改处理() {
        String a = "class A {\n public int f(int x) {\n if (x > 3) { return 1; }\n return 0;\n }\n}";
        String b = "class A {\n public int f(int x, int y) {\n if (x > 5) { return 1; }\n return 0;\n }\n}";
        List<MethodLogicDiff.Change> cs = MethodLogicDiff.diff("A.java", a, b);
        assertTrue(cs.stream().noneMatch(c -> c.getKind().equals("METHOD_ADDED") || c.getKind().equals("METHOD_REMOVED")), cs.toString());
        assertTrue(cs.stream().anyMatch(c -> c.getKind().equals("CONDITION") && "x > 5".equals(c.getAfter())), cs.toString());
    }

    @Test
    void 字段初始化里的lambda不会把成员切碎() {
        String a = "class A {\n private final Runnable r = () -> { run(); };\n public void g() { if (k == 1) { x(); } }\n}";
        String b = "class A {\n private final Runnable r = () -> { run(); };\n public void g() { if (k == 2) { x(); } }\n}";
        List<MethodLogicDiff.Change> cs = MethodLogicDiff.diff("A.java", a, b);
        assertFalse(cs.isEmpty());
        assertTrue(cs.stream().allMatch(c -> "g".equals(c.getMember())), cs.toString());
    }

    @Test
    void 边界值推导() {
        assertEquals(List.of("a 取 6 和 7"), MethodLogicDiff.boundaries("a < 7"));
        assertEquals(List.of("a 取 7 和 8"), MethodLogicDiff.boundaries("a <= 7"));
        assertTrue(MethodLogicDiff.boundaries("user == null").get(0).contains("null"));
        assertTrue(MethodLogicDiff.boundaries("\"VIP\".equals(type)").get(0).contains("\"VIP\""));
    }
}
