package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.UiElementHealthService;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 页面元素体检：dump 一次当前控件树，把本页所有 ACTIVE 元素的定位在树上求值一遍。
 *
 * <p><b>为什么做成关键字而不是一个独立的巡检任务</b>：独立巡检任务需要自己导航到每一页，
 * 也就是需要另外维护一套「只为体检而存在」的流程——那套流程一样会腐烂，
 * 等于用一个要维护的东西去维护另一个。做成关键字就可以<b>搭车</b>：
 * 已有的用例本来就会走到那些页面，在那儿插一步，顺手把这一页体检了，额外成本只有一次 dump。
 *
 * <p><b>默认不打断流程。</b>体检是搭车跑的，不该让一条本来正常的用例因为体检结果变红——
 * 那样人就会把这一步删掉。结果写进元素健康数据，去报表里看。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckPageElementsHandler implements KeywordHandler {

    private final UiElementHealthService elementHealthService;

    @Override
    public String code() {
        return "CHECK_PAGE_ELEMENTS";
    }

    @Override
    public String nameZh() {
        return "页面元素体检";
    }

    @Override
    public String category() {
        return "FLOW";
    }

    @Override
    public String description() {
        return "把本页所有已选用元素的定位在当前控件树上求值一遍：命中 0 个＝已失效，命中多个＝定位不唯一。默认不打断流程";
    }

    @Override
    public int sortOrder() {
        return 95;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    /** 只读一次控件树，没有副作用，重试安全 */
    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.string("pageId", "限定页面")
                        .withHelp("只体检这一页的元素；留空则体检该应用该平台下全部已选用元素"),
                ParamDef.bool("failOnMissing", "发现失效就让本步失败", false)
                        .withHelp("默认关闭。体检是搭在正常用例上跑的，让它把用例弄红，人就会把这一步删掉")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String pageId = ctx.str("pageId");
        boolean failOnMissing = ctx.boolParam("failOnMissing", false);

        String xml;
        try {
            xml = ctx.getDriver().getPageSource(ctx.getDeviceId());
        } catch (Exception e) {
            // 拿不到控件树只是体检做不成，不该连累这条用例
            ctx.warn("页面元素体检", "拿不到控件树，跳过体检: " + e.getMessage(), null);
            return;
        }

        UiElementHealthService.ProbeResult r;
        try {
            r = elementHealthService.probe(xml, ctx.getUiAppId(), ctx.getPlatform(), pageId);
        } catch (Exception e) {
            ctx.warn("页面元素体检", "体检失败，跳过: " + e.getMessage(), null);
            return;
        }

        String summary = "共 " + r.getTotal() + " 个，健康 " + r.getHealthy()
                + "，已失效 " + r.getMissing() + "，定位不唯一 " + r.getAmbiguous()
                + "，跳过 " + r.getSkipped() + "（含变量的定位无法静态求值）";

        if (r.getMissing() == 0 && r.getAmbiguous() == 0) {
            ctx.ok("页面元素体检", summary);
            return;
        }

        StringBuilder detail = new StringBuilder(summary);
        int shown = 0;
        for (Map<String, Object> p : r.getProblems()) {
            if (shown++ >= 10) {
                detail.append("\n… 其余 ").append(r.getProblems().size() - 10).append(" 个见元素健康报表");
                break;
            }
            detail.append("\n· ").append(p.get("nameEn"))
                    .append("（").append(p.get("nameZh")).append("）")
                    .append(" → ").append(p.get("verdict"));
        }

        if (failOnMissing && r.getMissing() > 0) {
            throw ctx.fail("页面元素体检", detail.toString(), null,
                    "本页有 " + r.getMissing() + " 个元素定位已失效");
        }
        ctx.warn("页面元素体检", detail.toString(), null);
    }
}
