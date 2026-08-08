package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScreenshotHandler implements KeywordHandler {

    @Override
    public String code() {
        return "SCREENSHOT";
    }

    @Override
    public String nameZh() {
        return "截图";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "保存当前截图";
    }

    @Override
    public int sortOrder() {
        return 80;
    }

    /** 关键字本身就是截图，不再前后各叠一张 */
    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    /** 不重试：截图失败不影响流程，重试无意义 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(ParamDef.string("label", "步骤备注"));
    }

    @Override
    public void execute(KeywordContext ctx) {
        String label = ctx.str("label");
        ctx.screenshot(label != null && !label.isBlank() ? label : "截图");
    }
}
