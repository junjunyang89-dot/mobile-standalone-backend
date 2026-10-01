package com.mobile.standalone.service.pack;

import java.util.List;

/**
 * 完美电竞安卓打包命令。
 * <p>
 * 固定：{@code testing} 分支 + {@code assembleDebug}。页面上不给选，避免打成别的包。
 */
public final class AndroidPackCommand {

    public static final String BRANCH = "testing";
    public static final String PRODUCT = "debug";
    public static final String OUTPUT_TYPE = "APK";

    private AndroidPackCommand() {
    }

    public static List<String> gradleArgs() {
        return List.of(":app:assembleDebug", "--no-daemon");
    }
}
