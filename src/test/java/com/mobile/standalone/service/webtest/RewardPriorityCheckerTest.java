package com.mobile.standalone.service.webtest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用「2026 S3 国服战令·上」线上页面的真实奖品名做基准。
 * <p>
 * 这些名字直接取自活动页，带着 (31天)/(93天)/x2 这类修饰——归一化认不认得出它们，
 * 决定了整个校验是真在比优先级，还是在比字符串。
 */
class RewardPriorityCheckerTest {

    private static final RewardPriorityChecker.Ladder LADDER =
            RewardPriorityChecker.Ladder.defaultLadder();

    private static RewardPriorityChecker.Result check(List<String> names) {
        return RewardPriorityChecker.check(names, LADDER, Map.of());
    }

    // ---------- 线上页面六个任务，应当全部通过 ----------

    @Test
    void 福利任务I_大会员在好运券之前() {
        var r = check(List.of("大会员(31天)", "ROLL房好运券x1", "神马币x200"));
        assertThat(r.ok()).isTrue();
        assertThat(r.items().get(0).category()).isEqualTo("大会员");
    }

    @Test
    void 福利任务II_重置卡_保护盾_天梯前缀() {
        var r = check(List.of("赛季重置卡", "段位保护盾x2", "天梯前缀", "神马币x200"));
        assertThat(r.ok()).isTrue();
        assertThat(r.items().get(0).tier()).isLessThan(r.items().get(1).tier());
    }

    @Test
    void 福利任务III_击杀特效在进房音效之前() {
        var r = check(List.of("击杀特效", "进房音效(93天)", "ROLL房好运券", "神马币x400"));
        assertThat(r.ok()).isTrue();
    }

    /**
     * 这一组我一开始按需求文档里 ID 的排列顺序判成了「顺序不一致」，其实是错的：
     * 礼包和赛季头像框都落在最后一级，<b>同级之间不要求顺序</b>。
     * 拿文档里的 ID 排列当基准本身就不对，真正的基准是这条优先级阶梯。
     */
    @Test
    void 福利任务IV_礼包与赛季头像框同级_顺序不作要求() {
        assertThat(check(List.of("改名卡(31天)", "礼包", "赛季头像框", "神马币x800")).ok()).isTrue();
        assertThat(check(List.of("改名卡(31天)", "赛季头像框", "礼包", "神马币x800")).ok()).isTrue();
    }

    @Test
    void 福利任务V与VI() {
        assertThat(check(List.of("大会员(31天)", "赛季徽章", "礼包", "神马币x1200")).ok()).isTrue();
        assertThat(check(List.of("进房特效(93天)", "赛季头像", "礼包", "神马币x1800")).ok()).isTrue();
    }

    // ---------- 抓得住排错 ----------

    @Test
    void 高优先级排到了后面要报出来并说清是谁和谁() {
        var r = check(List.of("段位保护盾x2", "大会员(31天)"));

        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).singleElement().satisfies(v -> {
            assertThat(v.message()).contains("大会员").contains("段位保护盾");
            // 直接引用文档里那条规则片段，人不用回去数阶梯
            assertThat(v.message()).contains("大会员 > 段位保护盾");
        });
    }

    /** 一处错位只报一次，不该因为前面有好几个更高级的就刷出一串 */
    @Test
    void 一处错位只报一条() {
        var r = check(List.of("大会员(31天)", "改名卡(31天)", "赛季重置卡", "段位保护盾x2", "击杀特效", "天梯双倍卡"));

        assertThat(r.violations()).hasSize(1);
        assertThat(r.violations().get(0).later().raw()).isEqualTo("天梯双倍卡");
        // 跟最靠前的那个越级项比，指向最有说服力的那一处
        assertThat(r.violations().get(0).earlier().raw()).isEqualTo("击杀特效");
    }

    // ---------- 名字归一 ----------

    @Test
    void 归一化去掉时效与数量修饰() {
        assertThat(RewardPriorityChecker.normalize("大会员(31天)")).isEqualTo("大会员");
        assertThat(RewardPriorityChecker.normalize("段位保护盾x2")).isEqualTo("段位保护盾");
        assertThat(RewardPriorityChecker.normalize("赛季限定击杀特效（永久）")).isEqualTo("赛季限定击杀特效");
        assertThat(RewardPriorityChecker.normalize("神马币*200")).isEqualTo("神马币");
    }

    /** 名字里本来就带字母的不能被当成数量后缀切掉 */
    @Test
    void 归一化不切掉名字自带的字母() {
        assertThat(RewardPriorityChecker.normalize("ROLL房好运券")).isEqualTo("ROLL房好运券");
    }

    /**
     * 认不出的必须报出来。
     * <p>
     * 不报的话它会被静默当成「其他」丢到最后一级——一个本该排前面的奖品排错了也看不出来。
     * 「进房特效」算不算规则里的「进房音效」，只有人能定。
     */
    @Test
    void 认不出的类目要报出来而不是静默归到其他() {
        var r = check(List.of("大会员(31天)", "进房特效(93天)", "某种新道具"));

        assertThat(r.unmatched()).contains("进房特效(93天)", "某种新道具");
        assertThat(r.items().get(1).matched()).isFalse();
        assertThat(r.items().get(1).tier()).isEqualTo(10);
    }

    /** 人工指定的映射优先于自动匹配——这是兜住认不出那类的唯一手段 */
    @Test
    void 人工别名可以指定类目() {
        var r = RewardPriorityChecker.check(
                List.of("进房特效(93天)", "段位保护盾x2"),
                LADDER,
                Map.of("进房特效", "进房音效"));

        assertThat(r.items().get(0).category()).isEqualTo("进房音效");
        // 进房音效是第7级，排在第5级的段位保护盾前面 → 应当报错
        assertThat(r.ok()).isFalse();
    }

    /** 取最长匹配：类目之间有包含关系时，短的先命中会把级别判错 */
    @Test
    void 取最长匹配避免被短类目抢先命中() {
        var ladder = new RewardPriorityChecker.Ladder(List.of(
                List.of("天梯房间背景"), List.of("天梯双倍卡"), List.of("其他")));
        var r = RewardPriorityChecker.check(List.of("赛季天梯房间背景"), ladder, Map.of());

        assertThat(r.items().get(0).category()).isEqualTo("天梯房间背景");
    }

    @Test
    void 空列表与单项不算违规() {
        assertThat(check(List.of()).ok()).isTrue();
        assertThat(check(List.of("礼包")).ok()).isTrue();
    }
}
