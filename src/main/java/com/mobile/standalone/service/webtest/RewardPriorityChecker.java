package com.mobile.standalone.service.webtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 奖励展示顺序校验。
 * <p>
 * 活动 H5 每期换皮、奖品全换，但<b>展示优先级是同一条规则</b>——所以这是这类页面里
 * 少数几个「写一次能长期用」的断言。它还跨环境：测试环境和生产环境配的奖品不同，
 * 但都该按同一条阶梯排。
 * <p>
 * 人工核这个特别费劲：十来级优先级 × 六七个任务 × 每个三四件奖品，眼睛过一遍又累又
 * 容易滑过去，而排错了用户是直接看得见的。
 * <p>
 * <b>真正的难点在名字匹配，不在比大小。</b>规则里写的是类目（「大会员」），页面上是
 * 带修饰的具体名（「大会员(31天)」「ROLL房好运券x1」）。所以先归一再匹配，
 * 而且匹配不上的<b>要报出来</b>——不报的话它会被静默当成「其他」丢到最后一级，
 * 一个本该在前面的奖品排错了也看不出来。
 */
public final class RewardPriorityChecker {

    private RewardPriorityChecker() {
    }

    /**
     * 优先级阶梯。每一层可以有多个类目，用来表达规则里的「动态头像＝其他」这种同级关系。
     * 同级之间顺序不作要求。
     */
    public record Ladder(List<List<String>> tiers) {

        /** 需求里给的那条：大会员＞改名卡＞…＞动态头像＝其他 */
        public static Ladder defaultLadder() {
            return new Ladder(List.of(
                    List.of("大会员"),
                    List.of("改名卡"),
                    List.of("赛季重置卡"),
                    List.of("天梯双倍卡"),
                    List.of("段位保护盾"),
                    List.of("击杀特效"),
                    List.of("进房音效"),
                    List.of("个人主页背景"),
                    List.of("天梯房间背景"),
                    List.of("赛季房间卡背景"),
                    List.of("动态头像", "其他")));
        }

        /** 没匹配上的一律落到最后一级（「其他」所在的那层） */
        int fallbackTier() {
            return tiers.size() - 1;
        }
    }

    /**
     * @param index     在列表中的位置，从 0 起
     * @param raw       页面上的原始文案
     * @param category  匹配到的类目；没匹配上时为 null
     * @param tier      优先级层级，越小越靠前
     * @param matched   是否匹配到了明确类目
     */
    public record Item(int index, String raw, String category, int tier, boolean matched) {
    }

    /**
     * 一处顺序问题。
     *
     * @param message 直接说清楚是谁排在了谁前面，以及各自第几级——
     *                只说「顺序不对」的话，人还得自己回去数一遍
     */
    public record Violation(Item earlier, Item later, String message) {
    }

    public record Result(List<Item> items, List<Violation> violations, List<String> unmatched) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    /** 页面上的奖品名常带这些修饰，比之前先去掉：x1 / *2 / (31天) / （永久） */
    private static final String[] TRAILING_QTY = {"x", "X", "*", "×"};

    /**
     * 校验一组<b>有序</b>的奖品名。
     *
     * @param rewardNames 页面上从左到右／从上到下读出来的奖品名
     * @param ladder      优先级阶梯
     * @param aliases     人工指定的「页面名 → 类目」映射，用来兜住自动匹配认不出的。
     *                    比如「进房特效」到底算不算规则里的「进房音效」，只能由人定
     */
    public static Result check(List<String> rewardNames, Ladder ladder, Map<String, String> aliases) {
        Ladder l = ladder == null ? Ladder.defaultLadder() : ladder;
        Map<String, String> alias = aliases == null ? Map.of() : normalizeKeys(aliases);

        List<Item> items = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (int i = 0; i < rewardNames.size(); i++) {
            String raw = rewardNames.get(i) == null ? "" : rewardNames.get(i);
            String norm = normalize(raw);
            String category = alias.get(norm.toLowerCase(Locale.ROOT));
            if (category == null) {
                category = matchCategory(norm, l);
            }
            int tier = category == null ? l.fallbackTier() : tierOf(category, l);
            if (category == null) {
                unmatched.add(raw);
            }
            items.add(new Item(i, raw, category, tier, category != null));
        }

        List<Violation> violations = new ArrayList<>();
        for (int i = 1; i < items.size(); i++) {
            Item later = items.get(i);
            // 只跟前面最靠前的那个越级项比一次，避免一处错位刷出一串重复告警
            Item worst = null;
            for (int j = 0; j < i; j++) {
                Item earlier = items.get(j);
                if (earlier.tier() > later.tier() && (worst == null || earlier.tier() > worst.tier())) {
                    worst = earlier;
                }
            }
            if (worst != null) {
                violations.add(new Violation(worst, later, describe(worst, later)));
            }
        }
        return new Result(items, violations, unmatched);
    }

    /**
     * 直接引用需求文档里那条规则的相关片段。
     * <p>
     * 报「第1级 / 第5级」的话，人还得回去把阶梯数一遍才知道谁该在前——
     * 而他真正要核对的就是文档里那句「大会员 &gt; 段位保护盾」。
     */
    private static String describe(Item earlier, Item later) {
        return "「" + later.raw() + "」应排在「" + earlier.raw() + "」前面"
                + "——按优先级：" + name(later) + " > " + name(earlier);
    }

    private static String name(Item it) {
        return it.matched() ? it.category() : it.raw() + "(未匹配，按「其他」)";
    }

    /**
     * 取<b>最长</b>的匹配类目。
     * <p>
     * 用最长而不是第一个命中：类目之间存在包含关系时（比如「天梯房间背景」与假想的
     * 「天梯房间」），短的会先命中并把它错判成另一级。
     */
    private static String matchCategory(String normalized, Ladder ladder) {
        String best = null;
        for (List<String> tier : ladder.tiers()) {
            for (String cat : tier) {
                if ("其他".equals(cat)) {
                    continue;
                }
                if (normalized.contains(cat) && (best == null || cat.length() > best.length())) {
                    best = cat;
                }
            }
        }
        return best;
    }

    private static int tierOf(String category, Ladder ladder) {
        for (int t = 0; t < ladder.tiers().size(); t++) {
            if (ladder.tiers().get(t).contains(category)) {
                return t;
            }
        }
        return ladder.fallbackTier();
    }

    /**
     * 去掉数量与时效修饰：「大会员(31天)」→「大会员」、「段位保护盾x2」→「段位保护盾」。
     * <p>
     * 括号一律去掉：里面装的是时效（31天/93天/永久）或说明，不参与类目判断。
     */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[（(\\[][^）)\\]]*[）)\\]]", "");
        for (String q : TRAILING_QTY) {
            int i = s.lastIndexOf(q);
            // 只在后缀确实是「x + 数字」时才截，否则会把名字里本来就有的字母切掉
            if (i > 0 && i < s.length() - 1 && s.substring(i + 1).trim().matches("\\d+")) {
                s = s.substring(0, i);
            }
        }
        return s.trim();
    }

    private static Map<String, String> normalizeKeys(Map<String, String> aliases) {
        Map<String, String> out = new LinkedHashMap<>();
        aliases.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(normalize(k).toLowerCase(Locale.ROOT), v.trim());
            }
        });
        return out;
    }
}
