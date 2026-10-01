package com.mobile.standalone.service.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CrushedScreenshotLiftTest {

    @TempDir
    Path tmp;

    @Test
    void 正常亮图不动() {
        assertThat(CrushedScreenshotLift.looksCrushed(221, 255)).isFalse();
    }

    @Test
    void 真黑屏不硬提() {
        assertThat(CrushedScreenshotLift.looksCrushed(1, 2)).isFalse();
    }

    @Test
    void 资讯页那种压暗要提() {
        assertThat(CrushedScreenshotLift.looksCrushed(6.2, 8)).isTrue();
    }

    @Test
    void 压暗png写回后变亮() throws Exception {
        Path png = tmp.resolve("dark.png");
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        int dark = (8 << 16) | (8 << 8) | 8;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                img.setRGB(x, y, dark);
            }
        }
        ImageIO.write(img, "png", png.toFile());
        assertThat(CrushedScreenshotLift.liftIfNeeded(png)).isTrue();
        BufferedImage out = ImageIO.read(png.toFile());
        int rgb = out.getRGB(10, 10);
        int lum = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
        assertThat(lum).isGreaterThan(150);
    }
}
