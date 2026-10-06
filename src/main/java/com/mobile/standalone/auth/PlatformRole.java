package com.mobile.standalone.auth;

import java.util.Locale;

/**
 * 平台角色，三档。
 * <p>
 * 角色只管「能读 / 能改 / 能删」。「能看见哪些功能入口」是另一条轴，按账号勾，
 * 见 {@link PlatformModules}；默认不限制，只给确实只该碰某几块的账号勾。
 * <p>
 * 三档之间是<b>包含关系</b>，不是并列：管理员能做测试能做的一切。
 * 所以用 {@link #rank} 比大小，不用一堆 boolean——加第四档时只改这里。
 */
public enum PlatformRole {

    /** 只能看：报告、用例、元素。改不了任何东西 */
    VIEWER("只读", 0),

    /**
     * 日常干活的那一档：跑用例、编排流程、改元素、跑线上回归。
     * <p>
     * <b>删不了东西</b>——这是和管理员的唯一分界。测试过程中最贵的误操作不是改错，
     * 改错还能改回来；是删了一批元素或用例，那个没有回退。
     */
    TESTER("测试", 10),

    /** 全权限：删数据、改配置、管账号 */
    ADMIN("管理员", 20);

    private final String label;
    private final int rank;

    PlatformRole(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public int rank() {
        return rank;
    }

    /** 当前角色够不够 {@code required} 这一档 */
    public boolean satisfies(PlatformRole required) {
        return required == null || this.rank >= required.rank;
    }

    /**
     * 认不出来的角色一律按<b>只读</b>处理。
     * <p>
     * 方向很重要：数据库里存了个拼错的角色名时，宁可让人少看见几个按钮来找你，
     * 也不能悄悄放成管理员——前者会被立刻发现，后者不会。
     */
    public static PlatformRole from(String raw) {
        if (raw == null || raw.isBlank()) {
            return VIEWER;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VIEWER;
        }
    }
}
