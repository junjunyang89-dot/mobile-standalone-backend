package com.mobile.standalone.locators.android.settings;

import org.openqa.selenium.By;

/**
 * Android "设置" 页面元素定位器（来源：automation-service-standalone，完美世界电竞应用）
 */
public final class SettingsLocators {

    private SettingsLocators() {}

    /** 侧边栏相关元素 */
    public enum SideMenu {
        SIDE_MENU_BUTTON(By.id("com.pwrd.steam.esports:id/sideView")),
        CLICK_LOGIN(By.xpath("//android.widget.TextView[@text='点击登录']")),
        HOMEPAGE(By.xpath("//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_mine_homepage']")),
        SETTINGS(By.xpath("//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_tool_mame' and @text='设置']"));

        private final By by;
        SideMenu(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 设置页面相关元素 */
    public enum SettingsPage {
        SETTINGS_TITLE(By.xpath("//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_title']")),
        LOGOUT_BUTTON(By.xpath("//android.widget.TextView[@resource-id='com.pwrd.steam.esports:id/tv_logout']")),
        /** 设置页内「我的账号」入口 */
        MY_ACCOUNT(By.xpath("//android.widget.TextView[@text='我的账号']")),
        /** 设置页内「我的兴趣」入口（可点击整行） */
        MY_INTEREST(By.id("com.pwrd.steam.esports:id/my_interest_rl")),
        /** 设置页内「高校认证」入口 */
        SCHOOL_CERT(By.xpath("//android.widget.TextView[@text='高校认证']"));

        private final By by;
        SettingsPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 我的账号页面相关元素 */
    public enum MyAccountPage {
        /** 我的账号页特征元素，用于验证已进入该页 */
        PAGE_TITLE(By.xpath("//android.widget.TextView[contains(@text,'账号') or contains(@text,'手机')]"));

        private final By by;
        MyAccountPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 我的兴趣页面相关元素 */
    public enum MyInterestsPage {
        /** 我的兴趣页特征元素，用于验证已进入该页（选择感兴趣的游戏） */
        PAGE_TITLE(By.id("com.pwrd.steam.esports:id/choose_interested_game_text"));

        private final By by;
        MyInterestsPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }

    /** 高校认证页面相关元素 */
    public enum SchoolCertPage {
        /** 高校认证页特征元素，用于验证已进入该页 */
        PAGE_TITLE(By.xpath("//android.widget.TextView[contains(@text,'高校') or contains(@text,'认证')]"));

        private final By by;
        SchoolCertPage(By by) { this.by = by; }
        public By getBy() { return by; }
    }
}
