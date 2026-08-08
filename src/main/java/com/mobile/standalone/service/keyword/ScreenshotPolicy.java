package com.mobile.standalone.service.keyword;

/**
 * 关键字的步骤截图策略。
 * <p>
 * 以前散在 {@code shouldCaptureBefore} / {@code shouldCaptureAfter} / {@code usesScreenshotBurst}
 * 三个平行 switch 里，加一个关键字要记得三处都补一遍。现在由关键字自己声明。
 */
public enum ScreenshotPolicy {

    /** 不拍：关键字自己管截图（启动类），或画面无意义（等待、返回提示） */
    NONE,

    /** 动作前后各拍一张 */
    SINGLE,

    /** 动作前一张 + 动作后连拍：需要观察跳转/动画的操作 */
    BURST;

    public boolean capturesBefore() {
        return this != NONE;
    }

    public boolean capturesAfter() {
        return this != NONE;
    }

    public boolean burstsAfter() {
        return this == BURST;
    }
}
