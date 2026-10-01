package com.mobile.standalone.service.h5;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H5ContextPagesTest {

    @Test
    void 优先可见页再退到带hash的页() {
        List<Map<String, Object>> raw = List.of(Map.of(
                "webview", "WEBVIEW_com.pwrd.steam.esports",
                "pages", List.of(
                        Map.of("url", "about:blank", "webSocketDebuggerUrl", "ws://1"),
                        Map.of(
                                "url", "https://news.wmpvp.com/gameMall.html",
                                "webSocketDebuggerUrl", "ws://hidden",
                                "description", "{\"screenX\":0,\"screenY\":0,\"visible\":false,\"width\":1080,\"height\":2400}"
                        ),
                        Map.of(
                                "url", "https://news.wmpvp.com/gameMall.html#/index?tagId=1",
                                "webSocketDebuggerUrl", "ws://vis",
                                "description", "{\"screenX\":0,\"screenY\":80,\"visible\":true,\"width\":1080,\"height\":2200}"
                        )
                )
        ));

        List<H5ContextPages.Target> pages = H5ContextPages.parse(raw);
        assertEquals(2, pages.size());
        H5ContextPages.Target pick = H5ContextPages.pick(pages);
        assertNotNull(pick);
        assertTrue(pick.visible());
        assertEquals("ws://vis", pick.wsUrl());
        assertEquals(80, pick.screenY());
    }

    @Test
    void 坐标按页面物理尺寸相对CSS视口缩放() {
        H5ContextPages.Target page = new H5ContextPages.Target(
                "https://a", "ws://x", 10, 20, 1080, 2160, true);
        assertEquals(10 + 540, H5ContextPages.tapX(page, 180, 360));
        assertEquals(20 + 1080, H5ContextPages.tapY(page, 360, 720));
    }
}
