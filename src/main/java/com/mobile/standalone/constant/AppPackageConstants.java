package com.mobile.standalone.constant;

/**
 * 常用应用包名 / Activity 常量（流程编排、线上回归、安装共用）。
 * 原 LoginTestService 硬编码场景已下线，常量集中于此避免耦合。
 */
public final class AppPackageConstants {

    private AppPackageConstants() {
    }

    /** PogPog 国际版 Android 包名 */
    public static final String POGPOG_INTL_PACKAGE = "com.arca.egames.intl";

    /** PogPog 国际版启动 Activity（相对名） */
    public static final String POGPOG_INTL_ACTIVITY_RELATIVE = ".LauncherActivity";

    /** PogPog 国际版完整 component：package/activity */
    public static final String POGPOG_INTL_COMPONENT =
            POGPOG_INTL_PACKAGE + "/" + POGPOG_INTL_ACTIVITY_RELATIVE;
}
