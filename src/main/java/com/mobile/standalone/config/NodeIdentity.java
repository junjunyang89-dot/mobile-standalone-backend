package com.mobile.standalone.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Locale;

/**
 * 本进程是控制面还是执行节点。
 * <p>
 * ALL＝一台机器两者都做（默认，今天就能调）。CONTROL 不碰手机；WORKER 扫本机设备并执行，
 * 流程/报告仍写控制面那份库。
 */
@Component
public class NodeIdentity {

    public static final String ALL = "ALL";
    public static final String CONTROL = "CONTROL";
    public static final String WORKER = "WORKER";

    private final String role;
    private final String id;
    private final String displayName;
    private final String controlPlaneUrl;
    private final String advertiseUrl;
    private final String hostname;

    public NodeIdentity(
            @Value("${app.node.role:ALL}") String role,
            @Value("${app.node.id:}") String id,
            @Value("${app.node.display-name:}") String displayName,
            @Value("${app.node.control-plane-url:}") String controlPlaneUrl,
            @Value("${app.node.advertise-url:}") String advertiseUrl,
            @Value("${server.port:3080}") int serverPort
    ) {
        this.role = normalizeRole(role);
        this.hostname = resolveHostname();
        this.id = (id == null || id.isBlank()) ? this.hostname : id.trim();
        this.displayName = (displayName == null || displayName.isBlank()) ? this.id : displayName.trim();
        this.controlPlaneUrl = trimSlash(controlPlaneUrl);
        String adv = trimSlash(advertiseUrl);
        this.advertiseUrl = adv.isEmpty() ? ("http://127.0.0.1:" + serverPort + "/api") : adv;
    }

    public String role() {
        return role;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String hostname() {
        return hostname;
    }

    public String controlPlaneUrl() {
        return controlPlaneUrl;
    }

    public String advertiseUrl() {
        return advertiseUrl;
    }

    /** 本进程是否扫 USB / 跑 Appium */
    public boolean executesLocally() {
        return ALL.equals(role) || WORKER.equals(role);
    }

    public boolean isControlOnly() {
        return CONTROL.equals(role);
    }

    public boolean hasRemoteControlPlane() {
        return !controlPlaneUrl.isEmpty() && !sameEndpoint(controlPlaneUrl, advertiseUrl);
    }

    private static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (CONTROL.equals(s) || WORKER.equals(s) || ALL.equals(s)) {
            return s;
        }
        return ALL;
    }

    private static String resolveHostname() {
        try {
            String h = InetAddress.getLocalHost().getHostName();
            return (h == null || h.isBlank()) ? "worker-local" : h.trim();
        } catch (Exception e) {
            return "worker-local";
        }
    }

    private static String trimSlash(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("/+$", "");
    }

    private static boolean sameEndpoint(String a, String b) {
        return trimSlash(a).replace("localhost", "127.0.0.1")
                .equalsIgnoreCase(trimSlash(b).replace("localhost", "127.0.0.1"));
    }
}
