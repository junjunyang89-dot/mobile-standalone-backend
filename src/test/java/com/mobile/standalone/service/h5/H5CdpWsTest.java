package com.mobile.standalone.service.h5;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H5CdpWsTest {

    @Test
    void localhost改成ipv4() {
        assertEquals("ws://127.0.0.1:9222/devtools/page/1",
                H5CdpWs.rewriteLoopback("ws://localhost:9222/devtools/page/1"));
    }

    @Test
    void 把json里的ws改到本机转发端口() {
        assertEquals("ws://127.0.0.1:18000/devtools/page/3",
                H5CdpWs.retarget("ws://localhost:9222/devtools/page/3", "127.0.0.1", 18000));
    }

    @Test
    void 认出套了一层的连接拒绝() {
        assertTrue(H5CdpWs.isConnectFailure(new ExecutionException(new ConnectException("Connection refused"))));
    }

    @Test
    void 从unix表里抽出devtools套接字() {
        String table = """
                Num RefCount Protocol Flags    Type St Inode Path
                00000000: 00000002 00000000 00010000 0001 01 1 @webview_devtools_remote_4321
                00000000: 00000002 00000000 00010000 0001 01 2 /dev/socket/other
                00000000: 00000002 00000000 00010000 0001 01 3 @chrome_devtools_remote
                """;
        assertEquals(2, H5AdbCdp.parseAbstractSockets(table).size());
        assertTrue(H5AdbCdp.parseAbstractSockets(table).contains("webview_devtools_remote_4321"));
    }

    @Test
    void json页按目标url挑选() throws Exception {
        String json = """
                [
                  {"type":"page","url":"about:blank","webSocketDebuggerUrl":"ws://127.0.0.1:1/devtools/page/0"},
                  {"type":"page","url":"https://news.wmpvp.com/gameMall.html#/index?tagId=1",
                   "webSocketDebuggerUrl":"ws://localhost:9222/devtools/page/2",
                   "description":"{\\"visible\\":true}"}
                ]
                """;
        H5AdbCdp.Hit hit = H5AdbCdp.pickJsonPage(new ObjectMapper().readTree(json),
                "https://news.wmpvp.com/gameMall.html#/index?tagId=1", 19000);
        assertNotNull(hit);
        assertEquals("ws://127.0.0.1:19000/devtools/page/2", hit.wsUrl());
    }
}
