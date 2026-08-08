package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClearHandler implements KeywordHandler {

    @Override
    public String code() {
        return "CLEAR";
    }

    @Override
    public String nameZh() {
        return "清空";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "清空输入框";
    }

    @Override
    public int sortOrder() {
        return 30;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(ParamDef.element("elementKey", "元素英文名"));
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String selector = ctx.selector("清空");
        if (!ctx.getDriver().inputTextToElement(ctx.getDeviceId(), selector, "")) {
            throw ctx.fail("清空", "清空失败: " + ctx.getElementKey(), selector);
        }
        ctx.ok("清空", ctx.getElementKey());
    }
}
