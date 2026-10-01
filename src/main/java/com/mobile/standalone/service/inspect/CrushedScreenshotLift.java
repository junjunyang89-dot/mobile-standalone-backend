package com.mobile.standalone.service.inspect;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Android 模拟器（尤其 API 35/36）上 {@code screencap} 有时把一整屏压成几乎全黑，
 * 窗口里人眼看着是正常的。资讯页那种硬件叠层最容易中招。
 * <p>
 * 坐标没坏，只是亮度被压扁了：中间亮度只有个位数，提亮后才能在平台上点。
 */
@Slf4j
public final class CrushedScreenshotLift {

    private CrushedScreenshotLift() {
    }

    /** @return 是否改写了文件 */
    public static boolean liftIfNeeded(Path png) {
        if (png == null || !Files.isRegularFile(png)) return false;
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return false;
            Stats s = sample(img);
            if (!looksCrushed(s.mean, s.p95)) return false;
            float gain = 220f / Math.max(s.p95, 1f);
            applyGain(img, gain);
            ImageIO.write(img, "png", png.toFile());
            log.info("截图过暗已提亮: {} mean={} p95={} gain={}",
                    png.getFileName(), round1(s.mean), round1(s.p95), round1(gain));
            return true;
        } catch (Exception e) {
            log.warn("截图提亮失败: {}", e.getMessage());
            return false;
        }
    }

    static boolean looksCrushed(double mean, double p95) {
        return mean < 25 && p95 >= 4 && p95 < 50;
    }

    private static void applyGain(BufferedImage img, float gain) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = clamp((int) (((argb >>> 16) & 0xff) * gain));
                int g = clamp((int) (((argb >>> 8) & 0xff) * gain));
                int b = clamp((int) ((argb & 0xff) * gain));
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Stats sample(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int stepX = Math.max(1, w / 80);
        int stepY = Math.max(1, h / 160);
        int n = 0;
        double sum = 0;
        int[] buckets = new int[256];
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int argb = img.getRGB(x, y);
                int lum = (((argb >>> 16) & 0xff) + ((argb >>> 8) & 0xff) + (argb & 0xff)) / 3;
                buckets[lum]++;
                sum += lum;
                n++;
            }
        }
        double mean = n == 0 ? 0 : sum / n;
        int need = Math.max(1, (int) Math.ceil(n * 0.95));
        int acc = 0;
        int p95 = 0;
        for (int i = 0; i < 256; i++) {
            acc += buckets[i];
            if (acc >= need) {
                p95 = i;
                break;
            }
        }
        return new Stats(mean, p95);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private record Stats(double mean, int p95) {
    }
}
