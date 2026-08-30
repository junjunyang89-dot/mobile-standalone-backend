package com.mobile.standalone.service;

import java.util.Locale;

/**
 * 喂给生成器的素材是哪一种。
 * <p>
 * 分开是必须的：两种素材要的提示词是<b>相反</b>的。
 * 页面截图上能看到控件，所以要约束「不要臆造截图里没有的入口」；
 * 需求文档上一个控件都没有，同一句约束会让模型去找「文档这一页上的按钮」，
 * 要么跑偏，要么因为找不到控件只吐三五条。
 */
public enum MaterialKind {

    /** APP 页面截图：图上有真实控件，用例要贴着控件写 */
    PAGE_SHOT("APP 页面截图"),

    /**
     * 需求文档（截图或文字）：图上是条款，不是界面。
     * <p>
     * 这一档还要多做一件页面截图不需要的事——<b>把文档里自相矛盾、边界没定义的地方挑出来</b>。
     * 需求文档常常是「原始需求 + 若干轮优化」拼起来的，两份之间互相打架很常见，
     * 而模型默认会照着两边各写一遍用例，让人测到一半才发现两条用例的预期是反的。
     */
    REQUIREMENT_DOC("需求文档");

    private final String label;

    MaterialKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 认不出来按页面截图处理——那是原来的行为，不传这个参数的老调用方不该被改变 */
    public static MaterialKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return PAGE_SHOT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PAGE_SHOT;
        }
    }
}
