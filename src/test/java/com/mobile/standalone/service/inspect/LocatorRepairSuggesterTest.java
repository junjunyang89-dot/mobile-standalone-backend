package com.mobile.standalone.service.inspect;

import com.mobile.standalone.util.LocatorQuality;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位失效/不唯一时，从控件树里推荐改成什么。
 *
 * <p>这组测试守两条底线：
 * <ol>
 *   <li><b>不能推荐更差的东西</b>——尤其不能推荐靠位置的定位，
 *       那正是「录制出来点不中」那次事故的根源</li>
 *   <li><b>找不到就说找不到</b>——凑几个疑似的出来，人得逐个排除，
 *       比直接说「没找到」更费时间</li>
 * </ol>
 */
class LocatorRepairSuggesterTest {

    /** 横排三个商城 Tab，resource-id 全一样，只有文案不同——这是最典型的「不唯一」现场 */
    private static final String TABS = """
            <hierarchy rotation="0">
              <android.widget.FrameLayout class="android.widget.FrameLayout" bounds="[0,0][1080,1920]">
                <android.widget.TextView resource-id="com.pw.esports:id/mall_category_tab"
                    text="CS商城" content-desc="" class="android.widget.TextView"
                    bounds="[0,200][360,300]"/>
                <android.widget.TextView resource-id="com.pw.esports:id/mall_category_tab"
                    text="刀塔商城" content-desc="" class="android.widget.TextView"
                    bounds="[360,200][720,300]"/>
                <android.widget.TextView resource-id="com.pw.esports:id/mall_category_tab"
                    text="英雄联盟商城" content-desc="" class="android.widget.TextView"
                    bounds="[720,200][1080,300]"/>
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    /** 改版换了包名前缀，控件自己的名字没变 */
    private static final String RENAMED = """
            <hierarchy rotation="0">
              <android.widget.Button resource-id="com.pw.newesports:id/btn_follow"
                  text="关注" content-desc="关注按钮" class="android.widget.Button"
                  bounds="[100,100][300,180]"/>
            </hierarchy>
            """;

    /** 一块纯容器，身上什么都没有 */
    private static final String BARE = """
            <hierarchy rotation="0">
              <android.widget.LinearLayout resource-id="" text="" content-desc=""
                  class="android.widget.LinearLayout" bounds="[0,1386][1080,1941]"/>
            </hierarchy>
            """;

    private static Document doc(String xml) throws Exception {
        return UiHierarchyBuilder.parseDocument(xml);
    }

    // ---------- 不唯一 ----------

    @Test
    void id不唯一时推荐加文案收窄() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(TABS), "id", "com.pw.esports:id/mall_category_tab", "商城分类Tab");
        assertFalse(s.empty(), String.valueOf(s.notes()));
        // 第一条必须是唯一命中的那条，不能是命中 3 个的裸 id
        LocatorRepairSuggester.Candidate top = s.candidates().get(0);
        assertEquals(1, top.matchCount(),
                "第一推荐命中了 " + top.matchCount() + " 个：" + top.locatorValue());
        assertTrue(top.locatorValue().contains("@text"), top.locatorValue());
    }

    /** 同类控件的正解是「一个元素 + 文案变量」，建议里要把这句话说出来 */
    @Test
    void 不唯一时提到文案变量这条正解() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(TABS), "id", "com.pw.esports:id/mall_category_tab", "商城分类Tab");
        assertTrue(s.candidates().stream().anyMatch(c -> c.why().contains("变量")),
                String.valueOf(s.candidates()));
    }

    // ---------- 换了包名 ----------

    @Test
    void 靠id尾段找回改了包名的元素() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(RENAMED), "id", "com.pw.esports:id/btn_follow", "关注按钮");
        assertFalse(s.empty(), String.valueOf(s.notes()));
        assertTrue(s.candidates().stream().anyMatch(
                        c -> "com.pw.newesports:id/btn_follow".equals(c.locatorValue())),
                String.valueOf(s.candidates()));
    }

    /** 都唯一的时候，按质量档排：id 在 content-desc 和文案前面 */
    @Test
    void 同为唯一时按质量档排() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(RENAMED), "id", "com.pw.esports:id/btn_follow", "关注按钮");
        List<LocatorRepairSuggester.Candidate> cs = s.candidates();
        for (LocatorRepairSuggester.Candidate c : cs) {
            assertEquals(1, c.matchCount(), "这一屏只有一个节点，不该有不唯一的候选：" + c);
        }
        for (int i = 1; i < cs.size(); i++) {
            assertTrue(cs.get(i - 1).tier() <= cs.get(i).tier(),
                    "第 " + i + " 条比前一条更稳，排序反了：" + cs);
        }
        assertEquals("id", cs.get(0).locatorType());
    }

    /**
     * 仍然不唯一的候选，绝不能排在唯一的前面。
     *
     * <p>我们正是<b>因为不唯一才来修的</b>。推荐一条还是不唯一的定位等于没修——
     * 人照着改完，「找到了、点了、没反应」会照原样发生，而他以为已经修过了。
     *
     * <p>第一版就写错在这里：沿用了录制那边「唯一性不跨档比」的规则，
     * 于是命中 3 个的裸 id（第 1 档）排在了唯一命中的收窄 xpath（第 3 档）前面。
     */
    @Test
    void 不唯一的候选不能排在唯一的前面() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(TABS), "id", "com.pw.esports:id/mall_category_tab", "刀塔商城");
        List<LocatorRepairSuggester.Candidate> cs = s.candidates();
        boolean seenAmbiguous = false;
        for (LocatorRepairSuggester.Candidate c : cs) {
            if (c.matchCount() != 1) {
                seenAmbiguous = true;
            } else {
                assertFalse(seenAmbiguous,
                        "唯一候选「" + c.locatorValue() + "」排在了不唯一的后面：" + cs);
            }
        }
    }

    // ---------- 底线 ----------

    /**
     * 绝不推荐靠位置的定位。
     *
     * <p>这条是上次事故的直接教训：{@code @bounds} 的 xpath <b>必然唯一命中</b>，
     * 所以任何「优先选唯一的」逻辑都会稳定地把它挑出来，而它换个分辨率就全废。
     */
    @Test
    void 绝不推荐坐标或bounds() throws Exception {
        for (String xml : List.of(TABS, RENAMED, BARE)) {
            LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                    doc(xml), "id", "com.pw.esports:id/mall_category_tab", "商城分类Tab");
            for (LocatorRepairSuggester.Candidate c : s.candidates()) {
                assertTrue(c.tier() < LocatorQuality.TIER_POSITIONAL,
                        "推荐了靠位置的定位：" + c.locatorValue());
                assertFalse(c.locatorValue().contains("@bounds"), c.locatorValue());
            }
        }
    }

    /** 节点身上没有可用属性时，如实说「只能靠位置点」，不要硬凑 */
    @Test
    void 纯容器给不出候选但要说明原因() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(BARE), "xpath", "//android.widget.LinearLayout[@bounds='[0,1386][1080,1941]']", null);
        assertTrue(s.empty());
        assertFalse(s.notes().isEmpty());
    }

    /** 当前定位本来就唯一命中，就别给建议——那是在制造工作量 */
    @Test
    void 当前定位已经唯一时不给建议() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(RENAMED), "id", "com.pw.newesports:id/btn_follow", "关注按钮");
        assertTrue(s.empty());
        assertTrue(s.notes().get(0).contains("不需要改"), String.valueOf(s.notes()));
    }

    /**
     * 这一屏根本不是该出现它的页面时，不能说「定位失效」。
     *
     * <p>这是离线体检最容易犯的错：元素没出现在某一屏上，
     * 可能只是这一屏不是它的页面。误报一个「失效」的代价是人去改一条本来好好的定位。
     */
    @Test
    void 屏上没有对得上的节点时不说失效() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(RENAMED), "id", "com.pw.esports:id/btn_share", "分享按钮");
        assertTrue(s.empty());
        assertTrue(s.notes().stream().anyMatch(n -> n.contains("不能据此判定定位失效")),
                String.valueOf(s.notes()));
    }

    /**
     * 线索匹配必须是<b>精确相等</b>，不能退化成模糊包含。
     *
     * <p>「商城」这个线索，模糊匹配会把 CS商城、刀塔商城、英雄联盟商城全拽进来，
     * 于是候选列表里塞满近似项，人得逐个排除——而这正是体检想省掉的事。
     * 找不到就说找不到，比给一堆疑似的更有用。
     *
     * <p>加这条测试是因为：我把匹配改成 contains 之后，原有的测试<b>一条都没红</b>——
     * 那几个现场里压根没有会被模糊匹配多拽进来的节点，所以那道防线当时是没人守的。
     */
    @Test
    void 线索只认精确相等不做模糊包含() throws Exception {
        LocatorRepairSuggester.Suggestion s = LocatorRepairSuggester.suggest(
                doc(TABS), "id", "com.pw.esports:id/gone_tab", "商城");
        assertTrue(s.empty(),
                "「商城」被模糊匹配到了具体的商城 Tab 上：" + s.candidates());
        assertTrue(s.notes().stream().anyMatch(n -> n.contains("找不到")),
                String.valueOf(s.notes()));
    }

    // ---------- 线索提取 ----------

    @Test
    void 线索取id尾段和写死的文案() {
        Set<String> c = LocatorRepairSuggester.clues(
                "//*[@resource-id='com.x:id/mall_category_tab' and @text='刀塔商城']", "商城分类Tab");
        assertTrue(c.contains("刀塔商城"), String.valueOf(c));
        assertTrue(c.contains("商城分类Tab"), String.valueOf(c));
    }

    /** 太宽的线索会把一堆无关节点拽进来，等于没筛 */
    @Test
    void 丢掉太宽的线索() {
        Set<String> c = LocatorRepairSuggester.clues("//*[@index='2']", "3");
        assertTrue(c.stream().noneMatch(x -> x.chars().allMatch(Character::isDigit)),
                String.valueOf(c));
        assertTrue(c.stream().noneMatch(x -> x.length() < 2), String.valueOf(c));
    }

    @Test
    void 属性名本身不算线索() {
        Set<String> c = LocatorRepairSuggester.clues("//*[@resource-id='com.x:id/a_b']", null);
        assertTrue(c.stream().noneMatch(x -> x.contains("@")), String.valueOf(c));
    }
}
