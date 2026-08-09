package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回到手机主屏幕（桌面）。
 * <p>
 * iOS：底部上滑（全面屏）+ Home 键；Android：Home / KEYCODE_HOME。
 * 与「滑动」不同——那是 App 内滑动，本关键字是系统级回桌面。
 */
@Component
public class GoHomeHandler implements KeywordHandler {

    @Override
    public String code() {
        return "GO_HOME";
    }

  @Override
  public String nameZh() {
    return "回主屏幕";
  }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "离开当前 App 回手机桌面。"
                + "iOS：底部中央快速上滑（Home Indicator，不是左右滑）；"
                + "Android：系统 Home 键";
    }

    @Override
    public int sortOrder() {
        return 71;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public String shortLabel() {
        return "回主屏";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.number("waitAfterMs", "回主屏后等待毫秒", 500)
                        .withHelp("桌面动画收完再继续下一步，可按机型调大")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (!ctx.isIos() && !ctx.isAndroidLike()) {
            throw ctx.fail("回到主屏幕",
                    "仅支持 Android / iOS，当前平台: " + ctx.getPlatform(),
                    null,
                    "回主屏平台不支持: " + ctx.getPlatform());
        }
        if (!ctx.getDriver().pressHome(ctx.getDeviceId())) {
            throw ctx.fail("回到主屏幕", "回主屏失败", null, "回主屏失败");
        }
        long waitMs = ctx.longParam("waitAfterMs", 500);
        if (waitMs > 0) {
            try {
                Thread.sleep(Math.min(waitMs, 10_000));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        String how = ctx.isIos()
                ? "iOS：底部中央快速上滑（非左右滑）"
                : "Android：Home 键";
        ctx.ok("回主屏幕", how);
    }
}
