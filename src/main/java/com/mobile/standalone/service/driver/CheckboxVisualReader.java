package com.mobile.standalone.service.driver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * 从截图像素推断自定义勾选框是否已勾选（蓝勾 vs 灰空圈）。
 * <p>
 * 协议勾选等 {@code ImageView} 在无障碍树里常没有可靠的 {@code checked}，
 * 但 UI 上勾选后会呈现主题色勾号。
 */
public final class CheckboxVisualReader {

    private CheckboxVisualReader() {
    }

    public static Boolean inferCheckedFromPng(byte[] png, int x, int y, int width, int height) {
        if (png == null || png.length == 0) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            return inferCheckedFromImage(img, x, y, width, height);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return true=检测到蓝色勾号，false=偏灰空圈，null=无法判断
     */
    public static Boolean inferCheckedFromImage(BufferedImage img, int x, int y, int width, int height) {
        if (img == null || width <= 0 || height <= 0) {
            return null;
        }
        int x0 = Math.max(0, x + width / 5);
        int y0 = Math.max(0, y + height / 5);
        int x1 = Math.min(img.getWidth(), x + width * 4 / 5);
        int y1 = Math.min(img.getHeight(), y + height * 4 / 5);
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }

        int blueish = 0;
        int neutral = 0;
        int samples = 0;
        for (int px = x0; px < x1; px += 2) {
            for (int py = y0; py < y1; py += 2) {
                int rgb = img.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                samples++;
                // 登录页协议勾选：勾号为蓝色；空圈为浅灰/白
                if (b >= 110 && b >= r + 20 && b >= g + 12) {
                    blueish++;
                } else {
                    int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    if (spread < 40) {
                        neutral++;
                    }
                }
            }
        }
        if (samples < 4) {
            return null;
        }
        double blueRatio = (double) blueish / samples;
        double neutralRatio = (double) neutral / samples;
        if (blueRatio >= 0.05) {
            return true;
        }
        if (neutralRatio >= 0.20 && blueRatio < 0.02) {
            return false;
        }
        return null;
    }
}
