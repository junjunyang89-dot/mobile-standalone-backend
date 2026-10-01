package com.mobile.standalone.service.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认出「Appium 会话已经没了」这件事。
 *
 * <p>为什么值得单测：这个判断错了的后果不是报错，是<b>一直报错且不会自愈</b>——
 * 缓存里那个空壳会话会把之后每一条命令都变成失败，而失败文案长得像
 * 「元素找不到」「读 H5 URL 失败」，把人往完全错误的方向带。
 * 判断对了，只要摘掉重建一次就好。
 */
class AppiumDeadSessionTest {

    /** 真机点选读 H5 URL 时实际撞到的那一条 */
    @Test
    void 会话被回收的原话() {
        assertTrue(AppiumDriverAdapter.isDeadSessionError(
                "A session is either terminated or not started Build info: version: '4.15.0', "
                        + "revision: '1d14b5521b' System info: os.name: 'Mac OS X'"));
    }

    @Test
    void 其它几种说法() {
        assertTrue(AppiumDriverAdapter.isDeadSessionError("invalid session id"));
        assertTrue(AppiumDriverAdapter.isDeadSessionError(
                "org.openqa.selenium.NoSuchSessionException: Session ID is null"));
        assertTrue(AppiumDriverAdapter.isDeadSessionError("Session not started"));
    }

    @Test
    void 大小写不敏感() {
        assertTrue(AppiumDriverAdapter.isDeadSessionError("A SESSION IS EITHER TERMINATED OR NOT STARTED"));
    }

    /** 别把普通失败也当成会话死了——那样会白白重建会话，反而更慢更不稳 */
    @Test
    void 普通失败不算() {
        assertFalse(AppiumDriverAdapter.isDeadSessionError(null));
        assertFalse(AppiumDriverAdapter.isDeadSessionError(""));
        assertFalse(AppiumDriverAdapter.isDeadSessionError(
                "An element could not be located on the page using the given search parameters"));
        assertFalse(AppiumDriverAdapter.isDeadSessionError(
                "Could not proxy command to the remote server. Original error: socket hang up"));
        assertFalse(AppiumDriverAdapter.isDeadSessionError("chromedriver version mismatch"));
    }
}
