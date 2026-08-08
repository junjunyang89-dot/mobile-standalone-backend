package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WaitElementHandler implements KeywordHandler {

    @Override
    public String code() {
        return "WAIT_ELEMENT";
    }

    @Override
    public String nameZh() {
        return "等待元素";
    }

    @Override
    public String category() {
        return "WAIT";
    }

    @Override
    public String description() {
        return "智能等待元素出现/可见/可点";
    }

    @Override
    public int sortOrder() {
        return 50;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.BURST;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "元素英文名"),
                ParamDef.enumOf("waitType", "等待类型", List.of("Presence", "Visible", "Clickable"), "Visible"),
                ParamDef.number("timeoutSec", "超时秒", 10)
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("等待元素");
        int timeout = (int) ctx.longParam("timeoutSec", 10);
        if (!ctx.getDriver().waitForElement(ctx.getDeviceId(), selector, timeout)) {
            throw ctx.fail("等待元素", "超时未出现: " + ctx.getElementKey(), selector,
                    "等待元素超时: " + ctx.getElementKey());
        }
        ctx.ok("等待元素", ctx.getElementKey());
    }
}
