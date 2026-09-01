package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 控件树取源的挑选依据。
 * <p>
 * 这里盯的是「用什么指标比较两份控件树」——原先只比 {@code text="} 的个数，
 * 而多窗口带出来的弹层里常常全是图标按钮，一个 text 都没有，只比文案会把这类差异
 * 整个漏掉，于是 Appium 那份更全的树永远选不上。
 */
class InspectSourcePickTest {

    /** dump 的格式：每个控件是 &lt;node&gt;，属性里带 bounds */
    private static final String DUMP_XML = """
            <?xml version='1.0' encoding='UTF-8'?>
            <hierarchy rotation="0">
              <node index="0" text="首页" class="android.widget.TextView" bounds="[0,0][100,50]"/>
              <node index="1" text="我的" class="android.widget.TextView" bounds="[0,50][100,100]"/>
            </hierarchy>""";

    /**
     * Appium 的格式：标签是类名而不是 node，且多带了一个弹层里的图标按钮——
     * 那个按钮没有 text，正是只比文案时会漏掉的东西。
     */
    private static final String APPIUM_XML = """
            <?xml version='1.0' encoding='UTF-8'?>
            <hierarchy class="hierarchy" rotation="0">
              <android.widget.TextView text="首页" bounds="[0,0][100,50]"/>
              <android.widget.TextView text="我的" bounds="[0,50][100,100]"/>
              <android.widget.ImageButton content-desc="关闭" bounds="[200,200][240,240]"/>
              <android.widget.ImageButton content-desc="分享" bounds="[250,200][290,240]"/>
            </hierarchy>""";

    private static int countAttr(String xml, String token) throws Exception {
        Method m = DeviceInspectService.class.getDeclaredMethod("countAttr", String.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, xml, token);
    }

    /**
     * bounds= 是两种格式共有的「每节点一个」属性。dump 用 &lt;node&gt;、Appium 用类名做标签，
     * 所以数标签名行不通，只能数属性。
     */
    @Test
    void boundsCountsNodesInBothXmlDialects() throws Exception {
        assertThat(countAttr(DUMP_XML, "bounds=\"")).isEqualTo(2);
        assertThat(countAttr(APPIUM_XML, "bounds=\"")).isEqualTo(4);
    }

    /** 换成节点数之后，Appium 那份更全的树才比得出来 */
    @Test
    void nodeCountPicksTheRicherTreeWhereTextCountWouldNot() throws Exception {
        int dumpTexts = countAttr(DUMP_XML, "text=\"");
        int appiumTexts = countAttr(APPIUM_XML, "text=\"");
        // 旧判据：文案一样多，且原先还要求 appium 比 dump 多 2 个才肯换 —— 选不上
        assertThat(appiumTexts).isEqualTo(dumpTexts);
        assertThat(appiumTexts > dumpTexts + 2).isFalse();

        // 新判据：节点数看得出多窗口带来的两个图标按钮
        assertThat(countAttr(APPIUM_XML, "bounds=\""))
                .isGreaterThan(countAttr(DUMP_XML, "bounds=\""));
    }

    @Test
    void countAttrHandlesNullAndAbsentToken() throws Exception {
        assertThat(countAttr(null, "bounds=\"")).isZero();
        assertThat(countAttr(DUMP_XML, "nonexistent=\"")).isZero();
    }
}
