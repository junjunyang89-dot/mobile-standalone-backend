package com.mobile.standalone.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 把真机截图压到能塞进模型请求的大小。
 * <p>
 * iOS retina 单张 PNG 实测 5MB，转 base64 还要再涨三分之一。原图直接发既超请求上限也慢得离谱，
 * 而归因要看的是「页面停在哪、按钮在不在、有没有弹窗盖住」，这些在长边 1024 上完全看得清。
 * <p>
 * 转 JPEG 而不是继续用 PNG：截图是照片式的连续色调内容，PNG 无损压缩对它效果很差。
 */
public final class ScreenshotDownscaler {

    /** 长边上限。再大对判读没有增益，只是多花钱 */
    public static final int DEFAULT_MAX_EDGE = 1024;

    private ScreenshotDownscaler() {
    }

    public record Scaled(byte[] bytes, String mimeType, int width, int height) {
    }

    public static Scaled scale(Path png) throws IOException {
        return scale(png, DEFAULT_MAX_EDGE);
    }

    public static Scaled scale(Path source, int maxEdge) throws IOException {
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) {
            throw new IOException("无法解析为图片: " + source);
        }
        int w = original.getWidth();
        int h = original.getHeight();
        double ratio = (double) maxEdge / Math.max(w, h);
        int targetW = ratio < 1 ? (int) Math.round(w * ratio) : w;
        int targetH = ratio < 1 ? (int) Math.round(h * ratio) : h;

        // JPEG 不支持透明通道，带 alpha 的图直接写会得到一张发黑的图
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(target, "jpg", out)) {
            throw new IOException("当前 JRE 不支持写出 JPEG");
        }
        return new Scaled(out.toByteArray(), "image/jpeg", targetW, targetH);
    }
}
