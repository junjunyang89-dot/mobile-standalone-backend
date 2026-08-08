package com.mobile.standalone.service.driver;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckboxVisualReaderTest {

    @Test
    void 蓝色像素判定为已勾选() {
        BufferedImage img = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        for (int x = 15; x < 45; x++) {
            for (int y = 15; y < 45; y++) {
                img.setRGB(x, y, new Color(30, 120, 240).getRGB());
            }
        }
        assertTrue(CheckboxVisualReader.inferCheckedFromImage(img, 10, 10, 40, 40));
    }

    @Test
    void 灰色像素判定为未勾选() {
        BufferedImage img = new BufferedImage(60, 60, BufferedImage.TYPE_INT_RGB);
        for (int x = 15; x < 45; x++) {
            for (int y = 15; y < 45; y++) {
                img.setRGB(x, y, new Color(200, 200, 200).getRGB());
            }
        }
        assertFalse(CheckboxVisualReader.inferCheckedFromImage(img, 10, 10, 40, 40));
    }
}
