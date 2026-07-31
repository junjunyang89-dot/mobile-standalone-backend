package com.mobile.standalone.locators.ios.settings;

import org.openqa.selenium.By;

/**
 * iOS "设置" 页面元素定位器（来源：automation-service-standalone，完美世界电竞应用）
 */
public final class SettingsLocators {

    private SettingsLocators() {}

    /** 侧边栏相关元素 */
    public enum SideMenu {
        SIDE_MENU_BUTTON(By.xpath("//XCUIElementTypeButton[@name='icon menu dark']")),
        CLICK_LOGIN(By.xpath("//XCUIElementTypeStaticText[@name='点击登录'] | //XCUIElementTypeButton[@name='点击登录']")),
        HOMEPAGE(By.xpath("//XCUIElementTypeButton[@name='主页']")),
        SETTINGS(By.xpath("//XCUIElementTypeStaticText[@name='设置']"));

        private final By by;
        SideMenu(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 设置页面相关元素 */
    public enum SettingsPage {
        SETTINGS_TITLE(By.xpath("//XCUIElementTypeStaticText[@name='设置']")),
        /** 设置页内「我的账号」入口 */
        MY_ACCOUNT(By.xpath("//XCUIElementTypeStaticText[@name='我的账号']")),
        /** 设置页内「我的兴趣」入口 */
        MY_INTEREST(By.xpath("//XCUIElementTypeStaticText[@name='我的兴趣']")),
        /** 设置页内「高校认证」入口 */
        SCHOOL_CERT(By.xpath("//XCUIElementTypeStaticText[@name='高校认证']"));

        private final By by;
        SettingsPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 我的账号页面相关元素 */
    public enum MyAccountPage {
        /** 我的账号页特征元素，用于验证已进入该页 */
        PAGE_TITLE(By.xpath("//XCUIElementTypeStaticText[contains(@name,'账号') or contains(@name,'手机')]"));

        private final By by;
        MyAccountPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 我的兴趣页面相关元素 */
    public enum MyInterestsPage {
        /** 我的兴趣页特征元素，用于验证已进入该页（选择感兴趣的游戏） */
        PAGE_TITLE(By.xpath("//XCUIElementTypeStaticText[@name='选择感兴趣的游戏 ']"));

        private final By by;
        MyInterestsPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 高校认证页面相关元素 */
    public enum SchoolCertPage {
        /** 高校认证页特征元素，用于验证已进入该页 */
        PAGE_TITLE(By.xpath("//XCUIElementTypeStaticText[contains(@name,'高校') or contains(@name,'认证')]"));

        private final By by;
        SchoolCertPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }
}
