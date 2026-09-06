package com.mobile.standalone.service.driver;

/**
 * 在元素矩形内按百分比取点击点。
 * <p>
 * H5 评论筛选栏在原生树上常是一整条 View，五个词挤在同一个 text 里。
 * 屏幕绝对坐标换机就漂；相对这条栏的宽度取点（「最新」大约 90%）才能跨分辨率。
 */
public final class ClickTapPoint {

    private ClickTapPoint() {
    }

    /**
     * @param xPercent 0=左沿，50=中心，100=右沿
     * @param yPercent 0=上沿，50=中心，100=下沿
     */
    static int[] of(int left, int top, int width, int height, int xPercent, int yPercent) {
        int xp = Math.max(0, Math.min(100, xPercent));
        int yp = Math.max(0, Math.min(100, yPercent));
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int x = left + clampInset(Math.round(w * xp / 100.0f), w);
        int y = top + clampInset(Math.round(h * yp / 100.0f), h);
        return new int[]{x, y};
    }

    public static boolean isCustom(int xPercent, int yPercent) {
        return xPercent != 50 || yPercent != 50;
    }

    /** 点击落在矩形内时，换算成 0–100 的相对百分比（跨分辨率用）。 */
    public static int[] percentOf(int left, int top, int width, int height, int x, int y) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int xp = (int) Math.round((x - left) * 100.0 / w);
        int yp = (int) Math.round((y - top) * 100.0 / h);
        return new int[]{clampPercent(xp), clampPercent(yp)};
    }

    private static int clampPercent(int p) {
        return Math.max(0, Math.min(100, p));
    }

    private static int clampInset(int offset, int size) {
        if (size <= 2) {
            return 1;
        }
        return Math.max(1, Math.min(size - 1, offset));
    }
}
