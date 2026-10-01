package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位串里的命名变量。
 * <p>
 * 要解决的是这类定位：{@code //*[@name="赛事高光 · 26分钟前"]}——钉着相对时间戳，
 * 过几分钟就匹配不上，换到测试环境（数据完全不同）更是必挂。
 * 写成变量之后，同一条定位在两个环境都能用。
 */
class LocatorVariableTest {

    private static final String TPL =
            "//XCUIElementTypeStaticText[contains(@name,\"${newsKeyword}\")]";

    @Test
    void 按参数替换变量() {
        String out = LocatorTemplate.expandVariables(TPL, Map.of("newsKeyword", "赛事高光"));

        assertEquals("//XCUIElementTypeStaticText[contains(@name,\"赛事高光\")]", out);
    }

    @Test
    void 同名变量多处出现都替换() {
        String tpl = "//*[@a=\"${k}\" and @b=\"${k}\"]";

        assertEquals("//*[@a=\"x\" and @b=\"x\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("k", "x")));
    }

    /**
     * 这条是本组最重要的。缺变量若静默留着字面量，执行会拿 {@code ${newsKeyword}}
     * 去页面上找，得到一句「元素未找到」——看着像页面改了，实际是忘了填参数。
     * 这种误导排查起来最费时间。
     */
    @Test
    void 变量没填时报错而不是留下字面量() {
        LocatorTemplate.MissingVariableException e = assertThrows(
                LocatorTemplate.MissingVariableException.class,
                () -> LocatorTemplate.expandVariables(TPL, Map.of()));

        assertTrue(e.getNames().contains("newsKeyword"));
        assertTrue(e.getMessage().contains("newsKeyword"), e.getMessage());
    }

    @Test
    void 空串也算没填() {
        assertThrows(LocatorTemplate.MissingVariableException.class,
                () -> LocatorTemplate.expandVariables(TPL, Map.of("newsKeyword", "   ")));
    }

    /** 一次把缺的都列出来，免得填一个报一个 */
    @Test
    void 一次报出全部缺失变量() {
        String tpl = "//*[@a=\"${x}\" and @b=\"${y}\"]";

        LocatorTemplate.MissingVariableException e = assertThrows(
                LocatorTemplate.MissingVariableException.class,
                () -> LocatorTemplate.expandVariables(tpl, Map.of()));

        assertEquals(List.of("x", "y"), e.getNames());
    }

    @Test
    void 没有变量的定位原样返回() {
        String plain = "com.demo.app:id/login_btn";

        assertEquals(plain, LocatorTemplate.expandVariables(plain, Map.of()));
        assertEquals(plain, LocatorTemplate.expandVariables(plain, null));
    }

    @Test
    void 列出定位用到的变量名并去重() {
        assertEquals(List.of("newsKeyword"), LocatorTemplate.variablesIn(TPL));
        assertEquals(List.of("a", "b"),
                LocatorTemplate.variablesIn("//*[@x=\"${a}\" and @y=\"${b}\" and @z=\"${a}\"]"));
        assertTrue(LocatorTemplate.variablesIn("com.demo:id/x").isEmpty());
        assertTrue(LocatorTemplate.variablesIn(null).isEmpty());
    }

    @Test
    void 文案变量可填固定数或随机区间() {
        String tpl = "//*[@text=\"${expandNo}\"]";

        assertEquals("//*[@text=\"3\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "3")));
        assertEquals("//*[@text=\"2\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "2-2")));
        assertEquals("//*[@text=\"4\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "[X:4-4]")));
        assertEquals("//*[@text=\"没毛病\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "没毛病")));
        assertEquals("//*[@text=\"0\"]",
                LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "0-0")));
    }

    @Test
    void 文案变量随机落在区间内() {
        String tpl = "//*[@text=\"${expandNo}\"]";
        for (int i = 0; i < 20; i++) {
            String out = LocatorTemplate.expandVariables(tpl, Map.of("expandNo", "1-5"));
            int n = Integer.parseInt(out.substring(out.indexOf('"') + 1, out.lastIndexOf('"')));
            assertTrue(n >= 1 && n <= 5, out);
        }
    }

    /** 随机下标是既有能力，两者要能共存 */
    @Test
    void 变量与随机下标互不干扰() {
        String tpl = "(//*[contains(@name,\"${kw}\")])[X:2-2]";

        String withVar = LocatorTemplate.expandVariables(tpl, Map.of("kw", "赛事"));
        assertEquals("(//*[contains(@name,\"赛事\")])[X:2-2]", withVar);
        assertEquals("(//*[contains(@name,\"赛事\")])[2]",
                LocatorTemplate.expandRandomIndexes(withVar));
    }

    @Test
    void 范围内下标全部展开而不是随机抽一个() {
        String tpl = "//android.widget.LinearLayout[@resource-id=\"vgList\"]"
                + "/android.widget.RelativeLayout[X:1-3]/android.widget.ImageView[1]";

        assertEquals(List.of(
                "//android.widget.LinearLayout[@resource-id=\"vgList\"]"
                        + "/android.widget.RelativeLayout[1]/android.widget.ImageView[1]",
                "//android.widget.LinearLayout[@resource-id=\"vgList\"]"
                        + "/android.widget.RelativeLayout[2]/android.widget.ImageView[1]",
                "//android.widget.LinearLayout[@resource-id=\"vgList\"]"
                        + "/android.widget.RelativeLayout[3]/android.widget.ImageView[1]"
        ), LocatorTemplate.expandAllIndexes(tpl));
    }

    @Test
    void 全角冒号的范围也认() {
        assertEquals(List.of("card[1]", "card[2]"),
                LocatorTemplate.expandAllIndexes("card[X：1-2]"));
    }
}
