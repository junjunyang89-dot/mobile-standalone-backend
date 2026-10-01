package com.mobile.standalone.service.h5;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 先试 Appium 给的 websocket；连不上就自己 adb forward 设备 devtools。
 */
@Slf4j
public final class H5CdpLink implements AutoCloseable {

    private final String wsUrl;
    private final H5AdbCdp.Session adb;

    private H5CdpLink(String wsUrl, H5AdbCdp.Session adb) {
        this.wsUrl = wsUrl;
        this.adb = adb;
    }

    public String wsUrl() {
        return wsUrl;
    }

    public static H5CdpLink open(String deviceId, H5ContextPages.Target page) throws Exception {
        String preferred = page == null ? null : H5CdpWs.rewriteLoopback(page.wsUrl());
        if (preferred != null && !preferred.isBlank() && probe(preferred)) {
            log.info("H5 CDP 使用 Appium websocket");
            return new H5CdpLink(preferred, null);
        }
        String want = page == null ? null : page.url();
        H5AdbCdp.Session session = H5AdbCdp.open(deviceId, want);
        return new H5CdpLink(session.wsUrl(), session);
    }

    private static boolean probe(String wsUrl) {
        try {
            H5CdpClient.evaluate(wsUrl, "1", Duration.ofSeconds(2));
            return true;
        } catch (Exception e) {
            if (H5CdpWs.isConnectFailure(e)) {
                log.info("Appium websocket 本机连不上，改走 adb forward: {}", e.getMessage());
                return false;
            }
            // 能连上但脚本失败，通道本身是活的
            log.debug("CDP probe 非连接错误（视为通道可用）: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void close() {
        if (adb != null) {
            adb.close();
        }
    }
}
