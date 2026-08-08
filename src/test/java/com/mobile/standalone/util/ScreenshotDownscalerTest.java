package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截图压缩。
 * <p>
 * 真机 iOS 截图实测单张 5MB，原图转 base64 后既超请求体上限也慢得没法用。
 * 归因要看的只是「页面停在哪、按钮在不在、有没有被盖住」，长边 1024 完全够。
 */
class ScreenshotDownscalerTest {

    @TempDir
    Path tmp;

    private Path writePng(String name, int w, int h, boolean withAlpha) throws IOException {
        BufferedImage img = new BufferedImage(w, h,
                withAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w / 2, h);
        g.dispose();
        Path p = tmp.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void 按长边等比缩放() throws IOException {
        Path png = writePng("big.png", 1170, 2532, false);

        ScreenshotDownscaler.Scaled s = ScreenshotDownscaler.scale(png, 1024);

        assertEquals(1024, Math.max(s.width(), s.height()));
        // 等比：宽高比要跟原图一致，拉伸变形会让坐标判断失真
        assertEquals(1170.0 / 2532, (double) s.width() / s.height(), 0.01);
    }

    @Test
    void 体积显著变小() throws IOException {
        Path png = writePng("big.png", 1170, 2532, false);
        long before = Files.size(png);

        ScreenshotDownscaler.Scaled s = ScreenshotDownscaler.scale(png, 1024);

        assertTrue(s.bytes().length < before, "压缩后应更小: " + s.bytes().length + " vs " + before);
        assertEquals("image/jpeg", s.mimeType());
    }

    /** 比上限还小的图不该被放大——放大只会增加体积，不增加任何信息 */
    @Test
    void 小图不放大() throws IOException {
        Path png = writePng("small.png", 400, 300, false);

        ScreenshotDownscaler.Scaled s = ScreenshotDownscaler.scale(png, 1024);

        assertEquals(400, s.width());
        assertEquals(300, s.height());
    }

    /** JPEG 没有透明通道，带 alpha 的图直接写会得到一张发黑的图 */
    @Test
    void 带透明通道的图不会压成黑图() throws IOException {
        Path png = writePng("alpha.png", 200, 100, true);

        ScreenshotDownscaler.Scaled s = ScreenshotDownscaler.scale(png, 1024);
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(s.bytes()));

        int left = back.getRGB(50, 50);
        assertTrue(new Color(left).getRed() > 100, "左半应仍是红色，实际 " + new Color(left));
    }

    @Test
    void 非图片文件报错() throws IOException {
        Path txt = tmp.resolve("a.txt");
        Files.writeString(txt, "not an image");

        assertThrows(IOException.class, () -> ScreenshotDownscaler.scale(txt, 1024));
    }
}
