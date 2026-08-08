package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载产物是不是安装包。
 * <p>
 * 实测踩过：蒲公英的应用短链是一张下载**页面**，拿它下载得到 41KB 的 HTML，
 * 存成 .apk 丢给 adb，只回一句含糊的「安装失败」——人和 AI 都只能并列猜签名、
 * 存储、权限五种可能。校验放在下载后立刻做，报错才能指到真正的原因上。
 */
class DownloadedPackageCheckTest {

    @TempDir
    Path tmp;

    private void check(Path file) {
        ReflectionTestUtils.invokeMethod(
                new AppPackageInstallService(null, null, null, null, null, null),
                "assertLooksLikePackage", file, "https://www.pgyer.com/demo");
    }

    @Test
    void 真正的zip包通过校验() throws Exception {
        Path apk = tmp.resolve("ok.apk");
        // APK / IPA 都是 ZIP，首四字节 PK\x03\x04
        Files.write(apk, new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0});

        check(apk);
    }

    @Test
    void 下载到网页时报错并说明该怎么改() throws Exception {
        Path fake = tmp.resolve("page.apk");
        Files.writeString(fake,
                "<!DOCTYPE html>\n<html><head><title>完美世界电竞 APK for Android Download</title>",
                StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(fake));

        assertTrue(e.getMessage().contains("不是安装包"), e.getMessage());
        assertTrue(e.getMessage().contains("buildKey"), "要告诉人改用什么方式：" + e.getMessage());
    }

    /** 留着一个假包在磁盘上，下次排查会以为包是好的 */
    @Test
    void 校验不通过时删掉下载的文件() throws Exception {
        Path fake = tmp.resolve("page.apk");
        Files.writeString(fake, "<html>not an apk</html>");

        assertThrows(IllegalStateException.class, () -> check(fake));
        assertFalse(Files.exists(fake));
    }

    @Test
    void 空文件也报错() throws Exception {
        Path empty = tmp.resolve("empty.apk");
        Files.write(empty, new byte[0]);

        assertThrows(IllegalStateException.class, () -> check(empty));
    }
}
