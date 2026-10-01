package com.mobile.standalone.service.h5;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.ExecutionException;

/**
 * Appium 给的 CDP 地址经常是 {@code ws://localhost:端口}：
 * 端口多半是列 context 时临时转发的，列完就拆；Java 连 localhost 还可能走 IPv6。
 */
public final class H5CdpWs {

    private H5CdpWs() {
    }

    public static String rewriteLoopback(String wsUrl) {
        if (wsUrl == null || wsUrl.isBlank()) {
            return wsUrl;
        }
        return wsUrl
                .replace("://localhost", "://127.0.0.1")
                .replace("://[::1]", "://127.0.0.1");
    }

    public static String retarget(String wsUrl, String host, int port) {
        if (wsUrl == null || wsUrl.isBlank()) {
            return "ws://" + host + ":" + port;
        }
        URI u = URI.create(wsUrl);
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String query = u.getRawQuery();
        return "ws://" + host + ":" + port + path + (query == null || query.isBlank() ? "" : "?" + query);
    }

    public static boolean isConnectFailure(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof ConnectException) {
                return true;
            }
            String m = c.getMessage() == null ? "" : c.getMessage().toLowerCase();
            if (m.contains("connection refused") || m.contains("connectexception")) {
                return true;
            }
            if (c instanceof ExecutionException && c.getCause() != null) {
                c = c.getCause();
                continue;
            }
            if (c.getCause() == null || c.getCause() == c) {
                break;
            }
            c = c.getCause();
        }
        return false;
    }
}
