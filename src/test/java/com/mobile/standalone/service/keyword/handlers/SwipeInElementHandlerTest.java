package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.inspect.UiHierarchyBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwipeInElementHandlerTest {

    @Test
    void 外壳不可滑时改用条带里的RecyclerView() throws Exception {
        String xml = """
                <hierarchy>
                  <android.widget.FrameLayout bounds="[0,0][1080,2400]" scrollable="false">
                    <androidx.viewpager.widget.ViewPager bounds="[0,200][1080,2400]" scrollable="true">
                      <android.widget.LinearLayout resource-id="com.pwrd.steam.esports:id/vgList"
                          bounds="[0,1616][1080,1901]" scrollable="false">
                        <androidx.recyclerview.widget.RecyclerView
                            bounds="[0,1616][1080,1901]" scrollable="true"/>
                      </android.widget.LinearLayout>
                    </androidx.viewpager.widget.ViewPager>
                  </android.widget.FrameLayout>
                </hierarchy>
                """;
        UiHierarchyBuilder.HierarchyNode root =
                UiHierarchyBuilder.build(UiHierarchyBuilder.parseDocument(xml), 1.0, 1.0);
        int[] box = SwipeInElementHandler.pickScrollableBounds(root, 0, 1616, 1080, 1901, true);
        assertArrayEquals(new int[]{0, 1616, 1080, 1901}, box);
    }

    @Test
    void 资讯大列表和条带相交时用里面的横滑条() throws Exception {
        String xml = """
                <hierarchy>
                  <androidx.recyclerview.widget.RecyclerView
                      resource-id="com.pwrd.steam.esports:id/rv_list"
                      bounds="[0,400][1080,2200]" scrollable="true">
                    <android.widget.LinearLayout resource-id="com.pwrd.steam.esports:id/vgList"
                        bounds="[0,1616][1080,1901]" scrollable="false">
                      <android.widget.HorizontalScrollView
                          bounds="[0,1616][1080,1901]" scrollable="true"/>
                    </android.widget.LinearLayout>
                  </androidx.recyclerview.widget.RecyclerView>
                </hierarchy>
                """;
        UiHierarchyBuilder.HierarchyNode root =
                UiHierarchyBuilder.build(UiHierarchyBuilder.parseDocument(xml), 1.0, 1.0);
        int[] box = SwipeInElementHandler.pickScrollableBounds(root, 0, 1616, 1080, 1901, true);
        assertArrayEquals(new int[]{0, 1616, 1080, 1901}, box);
    }

    @Test
    void 单次不拉满区域宽度() {
        int[] ep = SwipeInElementHandler.inRegionEndpoints(0, 1616, 1080, 1901, "left", 12, 28);
        int travel = Math.abs(ep[0] - ep[2]);
        assertTrue(travel < 400, "travel=" + travel);
        assertTrue(travel >= 24);
        assertTrue(ep[1] == ep[3]);
    }

    @Test
    void 全屏ViewPager即使和条带相交也不当滑动层() throws Exception {
        String xml = """
                <hierarchy>
                  <androidx.viewpager.widget.ViewPager bounds="[0,0][1080,2400]" scrollable="true"/>
                </hierarchy>
                """;
        UiHierarchyBuilder.HierarchyNode root =
                UiHierarchyBuilder.build(UiHierarchyBuilder.parseDocument(xml), 1.0, 1.0);
        assertNull(SwipeInElementHandler.pickScrollableBounds(root, 0, 1616, 1080, 1901, true));
    }
}
