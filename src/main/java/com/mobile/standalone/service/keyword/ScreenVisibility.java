package com.mobile.standalone.service.keyword;

import java.util.List;

/**
 * 「这个元素是不是真的在人眼能看到的地方」。
 *
 * <p>单独抽出来，是因为 {@code isElementPresent} 回答的是另一个问题：<b>控件树里有没有</b>。
 * 这两件事在大多数页面上碰巧一致，唯独在横向滑动条上不一致——而横滑恰恰是最需要判断
 * 「滑到没滑到」的场景：
 *
 * <ul>
 *   <li>HorizontalScrollView、以及没开回收的 RecyclerView，会把屏幕外的子控件<b>原样留在控件树里</b>，
 *       坐标是负数或大于屏宽。</li>
 *   <li>于是「金刚位第 8 个图标存在吗」在一次都没滑之前就返回 true。</li>
 *   <li>基于它写的断言 / 循环条件，会在滑没滑都成立——绿得毫无意义。</li>
 * </ul>
 *
 * <p>判定用<b>中心点</b>而不是「与屏幕有交集」：正在滑出去的那个图标边缘还压在屏幕边上，
 * 按有交集算就算可见，而人眼看到的只是半个残影。这种「勉强算数」的判定会让结果在临界状态下
 * 时对时错，比一直判错更难查。
 *
 * <p>凡是取不到坐标、取不到屏幕尺寸的情况一律<b>放行</b>：证明不了它不可见，
 * 就不该反过来判定它不存在。宁可漏判，不可错判——错判会把一个好用例变成偶现失败。
 */
public final class ScreenVisibility {

    private ScreenVisibility() {
    }

    /** 屏幕尺寸 {宽, 高}；取不到返回 null，调用方据此退回「只看控件树」 */
    public static int[] screenSize(KeywordContext ctx) {
        try {
            var appium = ctx.getRuntime().appiumDriverAdapter();
            if (appium == null) {
                return null;
            }
            int[] size = appium.getWindowSize(ctx.getDeviceId());
            if (size == null || size.length < 2 || size[0] <= 0 || size[1] <= 0) {
                return null;
            }
            return size;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 这一组定位里，有没有任意一条既能命中、又落在屏幕内。
     *
     * @param screen 屏幕尺寸，传 null 表示不做可见性判断，只看控件树
     */
    public static boolean anyOnScreen(KeywordContext ctx, List<String> selectors, int[] screen) {
        if (selectors == null) {
            return false;
        }
        for (String selector : selectors) {
            if (isOnScreen(ctx, selector, screen)) {
                return true;
            }
        }
        return false;
    }

    /** 单条定位：命中且（若给了屏幕尺寸）中心点在屏内 */
    public static boolean isOnScreen(KeywordContext ctx, String selector, int[] screen) {
        try {
            if (!ctx.getDriver().isElementPresent(ctx.getDeviceId(), selector)) {
                return false;
            }
            return screen == null || centerWithin(ctx, selector, screen);
        } catch (Exception e) {
            // 探测期间会话会抖动。一次异常不能当成「元素不存在」——
            // 那会把网络抖动变成用例失败，也就是最典型的偶现问题
            return false;
        }
    }

    /** 元素中心点是否落在屏幕内；取不到坐标时放行 */
    public static boolean centerWithin(KeywordContext ctx, String selector, int[] screen) {
        try {
            int[] box = ctx.getDriver().getElementBounds(ctx.getDeviceId(), selector);
            if (box == null || box.length < 4) {
                return true;
            }
            int cx = (Math.min(box[0], box[2]) + Math.max(box[0], box[2])) / 2;
            int cy = (Math.min(box[1], box[3]) + Math.max(box[1], box[3])) / 2;
            return cx >= 0 && cx < screen[0] && cy >= 0 && cy < screen[1];
        } catch (Exception e) {
            return true;
        }
    }
}
