package com.mobile.standalone.service.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCaptureLanIpTest {

    @Test
    void rfc1918识别私网() {
        assertTrue(PacketCaptureService.isRfc1918("192.168.10.10"));
        assertTrue(PacketCaptureService.isRfc1918("10.0.0.1"));
        assertTrue(PacketCaptureService.isRfc1918("172.16.1.1"));
        assertTrue(PacketCaptureService.isRfc1918("172.31.255.1"));
        assertFalse(PacketCaptureService.isRfc1918("172.15.0.1"));
        assertFalse(PacketCaptureService.isRfc1918("169.254.17.95"));
        assertFalse(PacketCaptureService.isRfc1918("100.67.36.106"));
        assertFalse(PacketCaptureService.isRfc1918("8.8.8.8"));
    }

    @Test
    void 本机网卡优先于桥接和隧道() {
        assertTrue(PacketCaptureService.lanIfaceScore("en0") > PacketCaptureService.lanIfaceScore("en11"));
        assertTrue(PacketCaptureService.lanIfaceScore("en0") > PacketCaptureService.lanIfaceScore("utun9"));
        assertTrue(PacketCaptureService.lanIfaceScore("en0") > PacketCaptureService.lanIfaceScore("bridge0"));
    }

    @Test
    void 本机能挑到局域网IP而不是链路本地() {
        String ip = PacketCaptureService.pickLanIp();
        // 跑测试的机器没有网卡时允许 null；有的话不能是 169.254
        if (ip != null) {
            assertFalse(ip.startsWith("169.254."), "不该选链路本地: " + ip);
            assertEquals(true, PacketCaptureService.isRfc1918(ip) || !ip.startsWith("169."), ip);
        }
    }
}
