package com.mobile.standalone.service.driver;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebElement;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppiumInputTargetTest {

    @Test
    void textViewPlaceholderIsNotEditable() {
        assertFalse(AppiumDriverAdapter.isEditable(el("android.widget.TextView", "友好评论，友善社区")));
    }

    @Test
    void editTextIsEditable() {
        assertTrue(AppiumDriverAdapter.isEditable(el("android.widget.EditText", "")));
    }

    @Test
    void textLandedLooksAtAttribute() {
        assertTrue(AppiumDriverAdapter.textLanded(el("android.widget.EditText", "不错"), "不错"));
        assertFalse(AppiumDriverAdapter.textLanded(el("android.widget.EditText", ""), "不错"));
    }

    private static WebElement el(String cls, String text) {
        return new WebElement() {
            @Override
            public void click() {
            }

            @Override
            public void submit() {
            }

            @Override
            public void sendKeys(CharSequence... keysToSend) {
            }

            @Override
            public void clear() {
            }

            @Override
            public String getTagName() {
                return "element";
            }

            @Override
            public String getAttribute(String name) {
                if ("class".equals(name) || "className".equals(name)) {
                    return cls;
                }
                if ("text".equals(name) || "value".equals(name)) {
                    return text;
                }
                return null;
            }

            @Override
            public boolean isSelected() {
                return false;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String getText() {
                return text;
            }

            @Override
            public List<WebElement> findElements(By by) {
                return List.of();
            }

            @Override
            public WebElement findElement(By by) {
                return this;
            }

            @Override
            public boolean isDisplayed() {
                return true;
            }

            @Override
            public Point getLocation() {
                return new Point(0, 0);
            }

            @Override
            public Dimension getSize() {
                return new Dimension(1, 1);
            }

            @Override
            public Rectangle getRect() {
                return new Rectangle(0, 0, 1, 1);
            }

            @Override
            public String getCssValue(String propertyName) {
                return "";
            }

            @Override
            public <X> X getScreenshotAs(OutputType<X> target) {
                return null;
            }

            @Override
            public String getDomProperty(String name) {
                return getAttribute(name);
            }
        };
    }
}
