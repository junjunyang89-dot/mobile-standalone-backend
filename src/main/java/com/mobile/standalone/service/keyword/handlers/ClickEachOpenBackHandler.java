package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 依次点列表项进入 → 点页内元素跳转 →（可选等成功标志）→ 点返回 → 下一项。
 */
@Component
public class ClickEachOpenBackHandler implements KeywordHandler {

    @Override
    public String code() {
        return "CLICK_EACH_OPEN_BACK";
    }

    @Override
    public String nameZh() {
        return "依次进入并返回";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "按文案列表依次点进一项，再点该页元素跳转，确认后点返回，然后下一项。"
                + "取值可以是任意文案（没毛病,加油）或年份区间（2025-2013）";
    }

    @Override
    public int sortOrder() {
        return 13;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<String> aliases() {
        return List.of("CLICK_EACH_VISIT_BACK");
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    @Override
    public String shortLabel() {
        return "依次进出";
    }

    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        List<ParamDef> out = new ArrayList<>(ClickEachHandler.clickParams());
        out.add(ParamDef.element("jumpElementKey", "进入后要点的元素")
                .withHelp("点进当前这一项之后，要点哪个入口去跳转"));
        out.add(ParamDef.element("backElementKey", "返回按钮")
                .withHelp("跳转成功后点这个回到列表，再处理下一项"));
        out.add(ParamDef.element("landedElementKey", "跳转成功标志")
                .optional()
                .withHelp("出现这个元素才算跳转成功，再点返回。不填则只按「跳转后等待」"));
        out.add(ParamDef.number("landedTimeoutSec", "等待成功标志秒", 8)
                .withHelp("仅在选了「跳转成功标志」时生效"));
        out.add(ParamDef.number("waitAfterJumpMs", "跳转后等待毫秒", 300)
                .withHelp("没配成功标志时，点完跳转先等这么久再返回"));
        out.add(ParamDef.number("waitAfterBackMs", "返回后等待毫秒", 300));
        return List.copyOf(out);
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        ClickEachLoop.runOpenBack(ctx, shortLabel());
    }
}
