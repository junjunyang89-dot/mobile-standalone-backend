package com.mobile.standalone.service;

import java.util.Locale;

/**
 * 批次级装包策略。
 * <p>
 * 装包从流程里提出来，是因为它<b>本来就不是用例</b>：
 * <ul>
 *   <li><b>版本是执行时的参数</b>，而流程是静态的——写死 buildKey 每发一版都要改流程，
 *       不写死就只能自动取最新版、选不了</li>
 *   <li><b>一批只该装一次</b>，现在靠「把安装流程排在第一条」保证。排错了、忘勾了、
 *       或者只想重跑第 5 条，就装不上或装两遍</li>
 *   <li><b>混在勾选列表里稀释通过率</b>：20 条里两条是装包，那两条通过不说明任何功能是好的</li>
 * </ul>
 */
public enum BatchInstallStrategy {

    /** 不装，直接用设备上现有的包。反复调试同一条流程时用，省掉每次重装的几分钟 */
    NONE,

    /** 装最新版。常规回归 */
    LATEST,

    /** 装指定版本。验某个特定构建时用 */
    SPECIFIC,

    /**
     * 先装上一版、再覆盖装最新版 —— <b>升级测试</b>。
     * <p>
     * 这一档是把「装旧版 → 用一遍 → 覆盖装新版」这个编排收进一个选项。
     * 覆盖安装要验的是「旧数据 + 新代码」：本地库表结构变了、缓存格式变了，
     * 这几类<b>全新安装永远测不出来</b>，而线上真实用户几乎全是覆盖升级。
     * <p>
     * 注意它<b>不包含「用一遍产生数据」那步</b>——那要靠批次里勾的流程本身完成。
     * 所以选这一档时，勾选列表的前几条应该是能产生本地数据的流程（登录、看一次赛程）。
     */
    PREVIOUS_THEN_LATEST;

    public static BatchInstallStrategy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 认不出来时按「不装」处理：宁可用设备上现有的包跑一轮，
            // 也不要因为一个拼错的策略名去装一个没人预期的版本
            return NONE;
        }
    }

    /** 要不要在装最新版之前先垫一个旧版本 */
    public boolean needsPreviousFirst() {
        return this == PREVIOUS_THEN_LATEST;
    }

    /** 最终要装的是不是最新版 */
    public boolean installsLatest() {
        return this == LATEST || this == PREVIOUS_THEN_LATEST;
    }

    public String describe() {
        return switch (this) {
            case NONE -> "不装包，用设备上现有的";
            case LATEST -> "装最新版";
            case SPECIFIC -> "装指定版本";
            case PREVIOUS_THEN_LATEST -> "升级测试：先装上一版，跑完前面的流程产生数据后覆盖装最新版";
        };
    }
}
