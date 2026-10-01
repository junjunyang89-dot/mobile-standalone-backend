package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一对方向写在同一步：选「上下」就同时出现向上/向下次数，选「左右」同理。
 * <p>
 * 不是两根手指一起滑，而是一个关键字里把正反两向配齐，谁先谁后由人定。
 */
@Component
public class SwipePairHandler implements KeywordHandler {

    static final String AXIS_VERTICAL = "上下";
    static final String AXIS_HORIZONTAL = "左右";
    static final String ORDER_UP_DOWN = "先上后下";
    static final String ORDER_DOWN_UP = "先下后上";
    static final String ORDER_LEFT_RIGHT = "先左后右";
    static final String ORDER_RIGHT_LEFT = "先右后左";

    @Override
    public String code() {
        return "SWIPE_PAIR";
    }

    @Override
    public String nameZh() {
        return "双向滑动";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "一对方向配在同一步。选上下就同时出现「向上次数 / 向下次数」；选左右则是左/右。"
                + "先做哪一边可以自己选。次数填 0 表示这一向不做";
    }

    @Override
    public int sortOrder() {
        return 61;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public String shortLabel() {
        return "双向滑";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("axis", "轴向", List.of(AXIS_VERTICAL, AXIS_HORIZONTAL), AXIS_VERTICAL)
                        .withHelp("选上下只出现上/下次数；选左右只出现左/右次数"),
                ParamDef.enumOf("orderVertical", "谁先谁后", List.of(ORDER_UP_DOWN, ORDER_DOWN_UP), ORDER_UP_DOWN)
                        .withHelp("先把第一向的次数滑完，再滑第二向")
                        .showWhen("axis", AXIS_VERTICAL),
                ParamDef.number("upTimes", "向上滑动次数", 1)
                        .withHelp("手指往上滑，看到下面内容。0＝不做这一向")
                        .showWhen("axis", AXIS_VERTICAL),
                ParamDef.number("downTimes", "向下滑动次数", 1)
                        .withHelp("手指往下滑。0＝不做这一向")
                        .showWhen("axis", AXIS_VERTICAL),
                ParamDef.enumOf("orderHorizontal", "谁先谁后", List.of(ORDER_LEFT_RIGHT, ORDER_RIGHT_LEFT), ORDER_LEFT_RIGHT)
                        .withHelp("先把第一向的次数滑完，再滑第二向")
                        .showWhen("axis", AXIS_HORIZONTAL),
                ParamDef.number("leftTimes", "向左滑动次数", 1)
                        .withHelp("0＝不做这一向")
                        .showWhen("axis", AXIS_HORIZONTAL),
                ParamDef.number("rightTimes", "向右滑动次数", 1)
                        .withHelp("0＝不做这一向")
                        .showWhen("axis", AXIS_HORIZONTAL),
                ParamDef.number("durationMs", "单次滑动毫秒", 500),
                ParamDef.number("intervalMs", "两次滑动间隔毫秒", 300)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<Stroke> plan = plan(ctx);
        if (plan.isEmpty()) {
            throw ctx.fail("双向滑动", "两个方向的次数都是 0，没有可滑的", null);
        }
        int duration = (int) ctx.longParam("durationMs", 500);
        int intervalMs = (int) Math.max(0, ctx.longParam("intervalMs", 300));
        int done = 0;
        int total = plan.size();
        for (Stroke s : plan) {
            ctx.ensureNotCancelled();
            if (!ctx.getRuntime().directionalSwipe(ctx.getDriver(), ctx.getDeviceId(), s.direction, duration)) {
                throw ctx.fail("双向滑动", "滑动失败: " + s.label, null, "双向滑动失败");
            }
            done++;
            if (done < total && intervalMs > 0) {
                ctx.sleep(intervalMs);
            }
        }
        ctx.ok("双向滑动", summarize(ctx, plan));
    }

    static List<Stroke> plan(KeywordContext ctx) {
        String axis = KeywordContext.firstNonBlank(ctx.str("axis"), AXIS_VERTICAL);
        if (isHorizontal(axis)) {
            int left = clampTimes(ctx.longParam("leftTimes", 1));
            int right = clampTimes(ctx.longParam("rightTimes", 1));
            String order = KeywordContext.firstNonBlank(ctx.str("orderHorizontal"), ORDER_LEFT_RIGHT);
            boolean leftFirst = !ORDER_RIGHT_LEFT.equals(order);
            return sequence(
                    leftFirst ? "left" : "right", leftFirst ? left : right, leftFirst ? "向左" : "向右",
                    leftFirst ? "right" : "left", leftFirst ? right : left, leftFirst ? "向右" : "向左");
        }
        int up = clampTimes(ctx.longParam("upTimes", 1));
        int down = clampTimes(ctx.longParam("downTimes", 1));
        String order = KeywordContext.firstNonBlank(ctx.str("orderVertical"), ORDER_UP_DOWN);
        boolean upFirst = !ORDER_DOWN_UP.equals(order);
        return sequence(
                upFirst ? "up" : "down", upFirst ? up : down, upFirst ? "向上" : "向下",
                upFirst ? "down" : "up", upFirst ? down : up, upFirst ? "向下" : "向上");
    }

    static List<Stroke> sequence(String d1, int n1, String l1, String d2, int n2, String l2) {
        List<Stroke> out = new ArrayList<>();
        for (int i = 0; i < n1; i++) {
            out.add(new Stroke(d1, l1));
        }
        for (int i = 0; i < n2; i++) {
            out.add(new Stroke(d2, l2));
        }
        return out;
    }

    static int clampTimes(long raw) {
        return (int) Math.min(30, Math.max(0, raw));
    }

    static boolean isHorizontal(String axis) {
        if (axis == null) {
            return false;
        }
        String a = axis.trim().toLowerCase(Locale.ROOT);
        return AXIS_HORIZONTAL.equals(axis) || a.contains("horizontal") || a.contains("左右");
    }

    private static String summarize(KeywordContext ctx, List<Stroke> plan) {
        StringBuilder sb = new StringBuilder();
        String axis = KeywordContext.firstNonBlank(ctx.str("axis"), AXIS_VERTICAL);
        sb.append(axis).append("：");
        String last = null;
        int count = 0;
        for (Stroke s : plan) {
            if (last == null) {
                last = s.label;
                count = 1;
            } else if (last.equals(s.label)) {
                count++;
            } else {
                sb.append(last).append("×").append(count).append(" → ");
                last = s.label;
                count = 1;
            }
        }
        if (last != null) {
            sb.append(last).append("×").append(count);
        }
        return sb.toString();
    }

    record Stroke(String direction, String label) {
    }
}
