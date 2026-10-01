package com.mobile.standalone.service.inspect;

import com.mobile.standalone.util.LocatorQuality;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 元素定位失效/不唯一时，从控件树里找出该改成什么。
 *
 * <p><b>为什么这一步不交给模型</b>：控件树是结构化的，「哪个节点是它」有线索可循
 * （resource-id 的尾段、原定位里写死的文案、元素中文名），
 * 而「哪条定位更好」平台已经有唯一的判据了（{@link LocatorQuality}）。
 * 两头都硬，中间就不需要猜——模型只该在这套机械办法<b>一个候选都找不出来</b>时才出场。
 *
 * <p>先机械后模型，还有个实际好处：机械筛完通常只剩几个元素，
 * 一次体检就从「一千次模型调用」变成「几次」。
 *
 * <p><b>产物一律是建议，不自动改库。</b>定位是断言的地基——
 * 自动改对了没人知道，改错了会让一批用例从「报错」变成「全绿但什么都没验」，
 * 而后者要到下次真出 bug 时才会暴露。
 */
public final class LocatorRepairSuggester {

    private LocatorRepairSuggester() {
    }

    /** 候选里最多给几条。给多了人一样要挑，等于把判断又推回去 */
    private static final int MAX_CANDIDATES = 4;

    /** xpath 里写死的字面量，用来反推这个元素原本认的是什么文案 */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']{1,60})'");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"]{1,60})\"");

    /**
     * @param tier       质量档，越小越稳（见 {@link LocatorQuality}）
     * @param matchCount 这条候选在当前树上命中几个。1 才是可用的
     * @param why        为什么给出这条，直接显示给人看
     */
    public record Candidate(String locatorType, String locatorValue,
                            int tier, int matchCount, String why) {
    }

    /**
     * @param candidates 按推荐度排序，空表示机械办法没找出来
     * @param notes      给人的说明；没有候选时这里会写清楚为什么
     */
    public record Suggestion(List<Candidate> candidates, List<String> notes) {
        public boolean empty() {
            return candidates.isEmpty();
        }
    }

    /**
     * @param nameZh 元素中文名。它经常就是界面上的文案（「刀塔商城」），是最有用的一条线索
     */
    public static Suggestion suggest(Document doc, String currentType, String currentValue, String nameZh) {
        List<String> notes = new ArrayList<>();
        if (doc == null) {
            notes.add("没有控件树可比对");
            return new Suggestion(List.of(), notes);
        }

        int now = LocatorMatcher.count(doc, currentType, currentValue);
        if (now == 1) {
            notes.add("当前定位在这一屏上命中恰好 1 个，不需要改");
            return new Suggestion(List.of(), notes);
        }

        Set<String> clues = clues(currentValue, nameZh);
        if (clues.isEmpty()) {
            // 说清楚是「没线索」而不是「没救」——前者人补个中文名就能再试，后者会让人直接重录
            notes.add("原定位里没有可用的线索（没有 id 尾段、没有写死的文案），"
                    + "元素中文名也不像界面文案。补一个和界面文案一致的中文名再体检一次，或者重新点选");
            return new Suggestion(List.of(), notes);
        }

        List<Element> hits = nodesMatchingAny(doc, clues);
        if (hits.isEmpty()) {
            notes.add("这一屏上找不到和「" + String.join("、", clues) + "」对得上的节点——"
                    + "多半这一屏根本不是该出现它的页面，不能据此判定定位失效");
            return new Suggestion(List.of(), notes);
        }
        if (hits.size() > 1) {
            notes.add("这一屏上有 " + hits.size() + " 个节点对得上线索，下面的候选按「稳 + 唯一」排过序");
        }

        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element el : hits) {
            for (Candidate c : candidatesFor(doc, el)) {
                if (seen.add(c.locatorType() + "|" + c.locatorValue())) {
                    out.add(c);
                }
            }
        }

        out.sort((a, b) -> Integer.compare(score(a), score(b)));
        List<Candidate> top = out.size() <= MAX_CANDIDATES ? out : out.subList(0, MAX_CANDIDATES);
        if (top.isEmpty()) {
            notes.add("对得上的节点身上没有能用的定位属性（没有 id、没有 content-desc、没有文案），"
                    + "只能靠位置点——这种不该当元素存，建议在流程里改点它里面带文字的子控件");
        }
        return new Suggestion(top, notes);
    }

    /**
     * 排序：<b>先看唯不唯一，再比质量档</b>。
     *
     * <p>这和录制挑定位的规则是反的，而那个反差是有理由的，值得写清楚——
     * 因为「唯一性优先」正是上次那场事故的写法。
     *
     * <p>录制面对的问题是「这个节点该用哪条定位记下来」，答案必须偏向<b>耐久</b>：
     * 一个命中 3 个的 id 也好过一个唯一命中的像素方框，所以那里唯一性绝不跨档比。
     *
     * <p>这里面对的问题不同：我们正是<b>因为定位不唯一才来修它的</b>。
     * 推荐一条仍然不唯一的定位，等于没修——人照着改完，
     * 「找到了、点了、没反应」还会照原样发生，而且他会以为已经修过了。
     *
     * <p>为什么这里可以安全地把唯一性放到第一位：<b>靠位置的候选在
     * {@link #add} 里就已经被整档剔掉了</b>，压根进不到排序。
     * 像素方框那个陷阱是「唯一性把靠位置的顶上来」，
     * 而这里没有靠位置的可顶——闸门在上游，不在排序里。
     */
    private static int score(Candidate c) {
        return (c.matchCount() == 1 ? 0 : 10_000) + c.tier() * 100;
    }

    // ==================== 线索 ====================

    static Set<String> clues(String currentValue, String nameZh) {
        Set<String> out = new LinkedHashSet<>();
        if (currentValue != null && !currentValue.isBlank()) {
            String v = currentValue.trim();
            // resource-id 的尾段：com.x:id/btn_follow → btn_follow。
            // 改版常改包名或前缀，控件自己的名字反而稳
            int slash = v.lastIndexOf('/');
            if (slash >= 0 && slash < v.length() - 1) {
                String tail = v.substring(slash + 1).trim();
                if (isIdLike(tail)) {
                    out.add(tail);
                }
            } else if (isIdLike(v)) {
                out.add(v);
            }
            addQuoted(SINGLE_QUOTED, v, out);
            addQuoted(DOUBLE_QUOTED, v, out);
        }
        if (nameZh != null && !nameZh.isBlank() && nameZh.trim().length() <= 30) {
            out.add(nameZh.trim());
        }
        // 纯数字、单字符这类线索匹配面太宽，拿它去找节点只会找出一堆无关的
        out.removeIf(c -> c.length() < 2 || c.chars().allMatch(Character::isDigit));
        return out;
    }

    private static void addQuoted(Pattern p, String v, Set<String> out) {
        Matcher m = p.matcher(v);
        while (m.find()) {
            String g = m.group(1).trim();
            // 属性名本身（resource-id / content-desc）不是线索
            if (!g.isEmpty() && !g.contains("@") && !g.startsWith("//")) {
                out.add(g);
            }
        }
    }

    private static boolean isIdLike(String s) {
        return s.matches("[A-Za-z][A-Za-z0-9_]{1,60}");
    }

    // ==================== 找节点 ====================

    /**
     * 只认<b>精确相等</b>，不做模糊包含。
     *
     * <p>模糊匹配在这里是负收益：它会把「商城」匹配到「周边商城」「刀塔商城」「商城分类」，
     * 于是候选列表里塞满近似项，人得逐个排除——而这正是体检想省掉的事。
     * 找不到就如实说找不到，比给一堆疑似的更有用。
     */
    static List<Element> nodesMatchingAny(Document doc, Set<String> clues) {
        List<Element> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element el)) {
                continue;
            }
            if (matchesAny(el, clues)) {
                out.add(el);
            }
        }
        return out;
    }

    private static boolean matchesAny(Element el, Set<String> clues) {
        String ridTail = tailOf(attr(el, "resource-id", "resourceId", "id"));
        String text = attr(el, "text", "label", "value");
        String desc = attr(el, "content-desc", "contentDescription", "name");
        for (String c : clues) {
            if (c.equals(ridTail) || c.equals(text) || c.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 造候选 ====================

    private static List<Candidate> candidatesFor(Document doc, Element el) {
        List<Candidate> out = new ArrayList<>();
        String rid = attr(el, "resource-id", "resourceId", "id");
        String desc = attr(el, "content-desc", "contentDescription", "name");
        String text = attr(el, "text", "label", "value");

        add(out, doc, "id", rid, "resource-id，改版后最稳的一档");
        add(out, doc, "accessibilityId", desc, "content-desc，仅次于 id");
        add(out, doc, "text", text, "界面文案，文案一改就失效，但比靠位置好");

        // id 命中多个时，加上文案收窄。这是横排同名控件（Tab、列表项）唯一可靠的写法
        if (rid != null && text != null && LocatorMatcher.count(doc, "id", rid) > 1) {
            String xp = "//*[@resource-id=" + LocatorMatcher.literal(rid)
                    + " and @text=" + LocatorMatcher.literal(text) + "]";
            add(out, doc, "xpath", xp,
                    "id 在这一屏上不唯一，加文案收窄；同类控件建议把文案做成 ${变量}");
        }
        return out;
    }

    private static void add(List<Candidate> out, Document doc, String type, String value, String why) {
        if (value == null || value.isBlank()) {
            return;
        }
        int tier = LocatorQuality.tier(type, value);
        if (tier >= LocatorQuality.TIER_POSITIONAL) {
            return;
        }
        int n = LocatorMatcher.count(doc, type, value);
        // 命中 0 的候选毫无意义；UNKNOWN 说明这类定位没法校验，同样不推荐
        if (n <= 0) {
            return;
        }
        out.add(new Candidate(type, value, tier, n,
                n == 1 ? why : why + "（注意：这一屏上命中 " + n + " 个，回放只会取第 1 个）"));
    }

    // ==================== 小工具 ====================

    private static String tailOf(String rid) {
        if (rid == null) {
            return null;
        }
        int slash = rid.lastIndexOf('/');
        return slash >= 0 && slash < rid.length() - 1 ? rid.substring(slash + 1) : rid;
    }

    private static String attr(Element el, String... names) {
        for (String n : names) {
            String v = el.getAttribute(n);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
