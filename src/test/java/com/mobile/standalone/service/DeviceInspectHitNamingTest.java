package com.mobile.standalone.service;

import com.mobile.standalone.entity.Device;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论页点「最新」曾被命名成「底栏我的」：底栏在树里仍在，点击 X 又靠右，
 * 旧逻辑只按 X 对齐就会套上「我的」。
 */
class DeviceInspectHitNamingTest {

    /**
     * 帖子详情：H5 把筛选挤成一串，右侧还有赞数父壳（id 还复用 vg_function_list），
     * 底栏「我的」仍在树底。
     */
    private static final String COMMENT_PAGE = """
            <?xml version='1.0' encoding='UTF-8'?>
            <hierarchy rotation="0">
              <android.widget.FrameLayout bounds="[0,0][1080,2400]" displayed="true">
                <android.webkit.WebView resource-id="com.pwrd.steam.esports:id/pwX5WebView"
                    bounds="[0,200][1080,2200]" displayed="true">
                  <android.view.View text="全部评论只看楼主 热门 最早 最新"
                      bounds="[40,300][1040,380]" displayed="true"/>
                  <android.widget.RelativeLayout class="android.widget.RelativeLayout"
                      resource-id="com.pwrd.steam.esports:id/vg_function_list"
                      clickable="true" bounds="[956,302][1080,533]" displayed="true">
                    <android.widget.TextView text="884" bounds="[980,450][1060,500]" displayed="true"/>
                  </android.widget.RelativeLayout>
                </android.webkit.WebView>
                <android.widget.LinearLayout resource-id="com.pwrd.steam.esports:id/bottomTabLayout"
                    bounds="[0,2200][1080,2400]" displayed="true">
                  <android.widget.TextView text="发现" bounds="[0,2220][216,2380]" displayed="true"/>
                  <android.widget.TextView text="赛事" bounds="[216,2220][432,2380]" displayed="true"/>
                  <android.widget.TextView text="社区" bounds="[432,2220][648,2380]" displayed="true"/>
                  <android.widget.TextView text="数据" bounds="[648,2220][864,2380]" displayed="true"/>
                  <android.widget.TextView text="我的" bounds="[864,2220][1080,2380]" displayed="true"/>
                </android.widget.LinearLayout>
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    @SuppressWarnings("unchecked")
    private List<DeviceInspectService.HitCandidate> hit(String xml, int px, int py) throws Exception {
        DeviceInspectService svc = new DeviceInspectService(null, null, null, null, null);
        Method m = DeviceInspectService.class.getDeclaredMethod(
                "parseAndHit", String.class, int.class, int.class,
                Device.Platform.class, String.class, List.class);
        m.setAccessible(true);
        List<String> warnings = new ArrayList<>();
        return (List<DeviceInspectService.HitCandidate>) m.invoke(
                svc, xml, px, py, Device.Platform.ANDROID, null, warnings);
    }

    @Test
    void 点评论区最新不能叫底栏我的() throws Exception {
        List<DeviceInspectService.HitCandidate> hits = hit(COMMENT_PAGE, 1000, 340);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSuggestedNameZh()).isEqualTo("最新");
        assertThat(hits.get(0).getSuggestedNameEn()).startsWith("community_comment_sort_newest");
        assertThat(hits.stream().map(DeviceInspectService.HitCandidate::getSuggestedNameZh))
                .noneMatch(n -> n != null && n.contains("底栏我的"));
        assertThat(hits.stream().map(DeviceInspectService.HitCandidate::getSuggestedNameEn))
                .noneMatch("home_tab_mine"::equals);
    }

    @Test
    void 点最新正下方赞数也不该串成底栏() throws Exception {
        List<DeviceInspectService.HitCandidate> hits = hit(COMMENT_PAGE, 1000, 470);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getSuggestedNameZh()).isEqualTo("最新");
        assertThat(hits.stream().map(DeviceInspectService.HitCandidate::getSuggestedNameEn))
                .noneMatch("home_tab_mine"::equals);
    }

    @Test
    void dump输出里混进提示行也能抽出XML() {
        String raw = "UI hierchary dumped to: /dev/tty\n<hierarchy rotation=\"0\"><node bounds=\"[0,0][1,1]\"/></hierarchy>\nOK\n";
        assertThat(DeviceInspectService.extractHierarchyXml(raw)).contains("<hierarchy");
        assertThat(DeviceInspectService.extractHierarchyXml("KO: unknown command")).isNull();
    }

    @Test
    void 定位分层能分开原生和H5() {
        DeviceInspectService.HitCandidate nativeId = new DeviceInspectService.HitCandidate();
        nativeId.setLocatorType("id");
        nativeId.setLocatorValue("com.pwrd.steam.esports:id/tv_title");
        assertThat(DeviceInspectService.classifyLocatorLayer(nativeId)).isEqualTo("NATIVE");

        DeviceInspectService.HitCandidate shell = new DeviceInspectService.HitCandidate();
        shell.setLocatorType("id");
        shell.setLocatorValue("com.pwrd.steam.esports:id/layoutMain");
        assertThat(DeviceInspectService.classifyLocatorLayer(shell)).isEqualTo("H5_SHELL");

        DeviceInspectService.HitCandidate h5 = new DeviceInspectService.HitCandidate();
        h5.setLocatorType("coordinate");
        h5.setLocatorValue("120,340");
        h5.setSuggestedNameZh("H5坐标点选");
        h5.setVisualHint("搜索框在 X5 里");
        assertThat(DeviceInspectService.classifyLocatorLayer(h5)).isEqualTo("H5");
    }

    @Test
    void 商城搜索框命中的layoutMain算H5页壳() {
        assertThat(DeviceInspectService.isH5PageShellLocator(
                "id", "com.pwrd.steam.esports:id/layoutMain")).isTrue();
        assertThat(DeviceInspectService.isH5PageShellLocator(
                "id", "com.pwrd.steam.esports:id/vg_web_root")).isTrue();
        assertThat(DeviceInspectService.isH5PageShellLocator(
                "xpath", "//android.widget.LinearLayout[@resource-id='com.pwrd.steam.esports:id/layoutMain']"))
                .isTrue();
        assertThat(DeviceInspectService.isH5PageShellLocator("id", "com.pwrd.steam.esports:id/tv_search")).isFalse();
    }

    @Test
    void 真点底栏我的仍叫底栏我的() throws Exception {
        List<DeviceInspectService.HitCandidate> hits = hit(COMMENT_PAGE, 970, 2300);
        assertThat(hits).isNotEmpty();
        assertThat(hits.stream().map(DeviceInspectService.HitCandidate::getSuggestedNameEn))
                .anyMatch("home_tab_mine"::equals);
        assertThat(hits.get(0).getSuggestedNameZh()).contains("我的");
    }
}
