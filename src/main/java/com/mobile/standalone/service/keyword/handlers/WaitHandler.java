package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WaitHandler implements KeywordHandler {

    @Override
    public String code() {
        return "WAIT";
    }

    @Override
    public String nameZh() {
        return "固定延迟";
    }

    @Override
    public String category() {
        return "WAIT";
    }

    @Override
    public String description() {
        return "固定等待一段时间（可配置，不写死在代码里）；等待期间可被「停止」打断";
    }

    @Override
    public int sortOrder() {
        return 40;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    /** 不重试：固定等待不会失败 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(ParamDef.number("durationMs", "毫秒", 1000));
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        long ms = ctx.longParam("durationMs", 1000);
        ctx.ok("固定延迟", ms + "ms");
        ctx.sleep(ms);
    }
}
