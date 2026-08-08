package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控件树摘要。
 * <p>
 * 这一层的价值全在「压缩之后还留得住判据」：布局容器可以丢，但文案、id、可点击、
 * 以及「在树里但不可见」这些用来区分遮挡与未渲染的信号，一个都不能丢。
 */
class PageSourceDigestTest {

    private static final String ANDROID_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <hierarchy rotation="0">
              <android.widget.FrameLayout index="0" bounds="[0,0][1080,2340]">
                <android.widget.LinearLayout index="0" bounds="[0,0][1080,2340]">
                  <android.widget.TextView index="0" text="欢迎登录"
                      resource-id="com.demo.app:id/title" bounds="[100,200][980,300]"/>
                  <android.widget.EditText index="1" text=""
                      resource-id="com.demo.app:id/phone_input" content-desc="手机号输入框"
                      clickable="true" bounds="[100,400][980,500]"/>
                  <android.widget.Button index="2" text="获取验证码"
                      resource-id="com.demo.app:id/send_code" clickable="true"
                      enabled="false" bounds="[100,600][980,700]"/>
                </android.widget.LinearLayout>
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    private static final String IOS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <AppiumAUT>
              <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Demo"
                  x="0" y="0" width="390" height="844">
                <XCUIElementTypeOther type="XCUIElementTypeOther" x="0" y="0" width="390" height="844">
                  <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" name="titleLabel"
                      label="欢迎登录" value="欢迎登录" x="20" y="100" width="350" height="40"/>
                  <XCUIElementTypeButton type="XCUIElementTypeButton" name="loginBtn"
                      label="登录" visible="false" x="20" y="700" width="350" height="50"/>
                </XCUIElementTypeOther>
              </XCUIElementTypeApplication>
            </AppiumAUT>
            """;

    @Test
    void 保留有文案有id的节点丢掉纯布局容器() {
        PageSourceDigest.Result r = PageSourceDigest.of(ANDROID_XML);

        assertTrue(r.outline().contains("欢迎登录"));
        assertTrue(r.outline().contains("phone_input"));
        assertTrue(r.outline().contains("获取验证码"));
        // FrameLayout / LinearLayout 没有文案没有 id，是白占篇幅的容器
        assertFalse(r.outline().contains("FrameLayout"), r.outline());
        assertFalse(r.outline().contains("LinearLayout"), r.outline());
        assertTrue(r.keptNodes() < r.totalNodes());
    }

    /** 包名前缀占的是长度，去掉后 id 仍然唯一可辨 */
    @Test
    void 类名与资源id都取短名() {
        PageSourceDigest.Result r = PageSourceDigest.of(ANDROID_XML);

        assertTrue(r.outline().contains("TextView"));
        assertFalse(r.outline().contains("android.widget.TextView"));
        assertTrue(r.outline().contains("id=\"title\""));
        assertFalse(r.outline().contains("com.demo.app:id/title"));
    }

    /**
     * disabled / not-displayed 是区分「按钮在但点不动」「元素在树里但被盖住」的关键，
     * 少了它们归因只能靠猜。
     */
    @Test
    void 保留不可点与不可见标记() {
        String android = PageSourceDigest.of(ANDROID_XML).outline();
        assertTrue(android.contains("disabled"), android);

        String ios = PageSourceDigest.of(IOS_XML).outline();
        assertTrue(ios.contains("not-displayed"), ios);
    }

    @Test
    void 兼容ios的属性命名() {
        PageSourceDigest.Result r = PageSourceDigest.of(IOS_XML);

        assertEquals("AppiumAUT", r.rootTag());
        // label/value/name 对应 Android 的 text/content-desc/resource-id
        assertTrue(r.outline().contains("欢迎登录"), r.outline());
        assertTrue(r.outline().contains("id=\"titleLabel\""), r.outline());
        // XCUIElementType 前缀对判读没有信息量
        assertTrue(r.outline().contains("StaticText"));
        assertFalse(r.outline().contains("XCUIElementTypeStaticText"));
    }

    @Test
    void desc与text相同时不重复输出() {
        // iOS 的 label 与 value 常常一模一样
        String outline = PageSourceDigest.of(IOS_XML).outline();
        long count = outline.lines()
                .filter(l -> l.contains("titleLabel"))
                .filter(l -> l.contains("desc="))
                .count();
        assertEquals(0, count, outline);
    }

    /**
     * 截断必须能被上层看见。悄悄截断会让下游以为「树里没有这个元素」，
     * 从而把「元素在更下面」误判成「页面没渲染」。
     */
    @Test
    void 超出上限时截断并如实标记() {
        StringBuilder big = new StringBuilder("<hierarchy>");
        for (int i = 0; i < 500; i++) {
            big.append("<android.widget.TextView text=\"条目").append(i)
                    .append("\" resource-id=\"com.demo.app:id/item_").append(i).append("\"/>");
        }
        big.append("</hierarchy>");

        PageSourceDigest.Result r = PageSourceDigest.of(big.toString(), 500);

        assertTrue(r.truncated());
        assertTrue(r.outline().length() <= 500);
        // 即使截断，总节点数也要数全，上层才知道丢了多少
        assertEquals(501, r.totalNodes());
        assertTrue(r.keptNodes() < r.totalNodes());
    }

    /**
     * 抓控件树时进程被杀会留下半截 XML。这时给残缺证据也好过什么都不给——
     * 页面顶部那几行文案往往已经足够判断停在哪一页了。
     */
    @Test
    void 非法xml退回纯文本裁剪而不是丢弃() {
        String broken = "<hierarchy><android.widget.TextView text=\"验证码已发送\"";

        PageSourceDigest.Result r = PageSourceDigest.of(broken);

        assertNotNull(r.outline());
        assertTrue(r.outline().contains("验证码已发送"), r.outline());
    }

    @Test
    void 空输入返回空结果不抛异常() {
        assertEquals(0, PageSourceDigest.of(null).totalNodes());
        assertEquals("", PageSourceDigest.of("   ").outline());
    }

    @Test
    void 可见文案按出现顺序去重() {
        List<String> texts = PageSourceDigest.visibleTexts(ANDROID_XML, 10);

        assertEquals(List.of("欢迎登录", "获取验证码"), texts);
    }

    @Test
    void 可见文案受limit限制() {
        assertEquals(1, PageSourceDigest.visibleTexts(ANDROID_XML, 1).size());
    }

    /**
     * 控件树来自被测设备，属于不可信输入：外部实体必须解析不动，
     * 否则一份构造过的 pageSource 就能让服务端去读本地文件。
     */
    @Test
    void 拒绝外部实体不读本地文件() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <hierarchy><android.widget.TextView text="&xxe;"/></hierarchy>
                """;

        PageSourceDigest.Result r = PageSourceDigest.of(xxe);

        assertFalse(r.outline().contains("root:"), "不应读到 /etc/passwd 内容");
    }
}
