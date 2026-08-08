package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BackHandler implements KeywordHandler {

    @Override
    public String code() {
        return "BACK";
    }

    @Override
    public String nameZh() {
        return "返回";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "系统返回键";
    }

    @Override
    public int sortOrder() {
        return 70;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of();
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        if (!ctx.getDriver().pressBack(ctx.getDeviceId())) {
            throw ctx.fail("返回", "系统返回失败", null, "返回失败");
        }
        ctx.ok("返回", "系统返回键");
    }
}
