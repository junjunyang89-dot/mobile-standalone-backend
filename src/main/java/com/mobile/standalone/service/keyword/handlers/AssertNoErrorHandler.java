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
 * 负面断言：确认屏幕上没有出现已知的错误提示。
 * <p>
 * 是 {@link DismissPopupsHandler} 的镜像——同样是一份清单，那个「出现就关掉」，这个「出现就判失败」。
 * <p>
 * 存在的理由是无人值守：截图评审要等人（或 AI）来看，而定时跑的回归当场没人盯。
 * 「按钮点得到但接口返回 500、页面弹了服务器开小差」这类问题，靠元素断言抓不到
 * （元素都在，只是内容不对），靠截图要等复核。这一步能让它当场变红。
 * <p>
 * 用文案而不是元素来匹配：错误 toast 一闪而过，很难预先采集成元素并维护定位，
 * 而控件树里的文字是现成的，一次 getPageSource 就能同时查完所有关键词。
 */
@Component
public class AssertNoErrorHandler implements KeywordHandler {

    /** 常见的服务端/网络异常提示，作为表单默认值，用户可按自家文案调整 */
    private static final String DEFAULT_ERROR_TEXTS = String.join("\n",
            "服务器开小差", "网络异常", "网络连接失败", "加载失败", "请求失败",
            "请稍后重试", "系统繁忙", "服务不可用");

    @Override
    public String code() {
        return "ASSERT_NO_ERROR";
    }

    @Override
    public String nameZh() {
        return "断言无错误提示";
    }

    @Override
    public String category() {
        return "ASSERT";
    }

    @Override
    public String description() {
        return "检查当前页面没有出现已知的错误提示文案（服务器开小差、网络异常等），出现即判失败。"
                + "无人值守时的兜底：接口挂了但按钮还点得到的情况，靠这一步当场变红";
    }

    @Override
    public int sortOrder() {
        return 91;
    }

    /**
     * 不重试：错误提示出现本身就是结论。
     * toast 会自己消失，重试一次「就看不到了」反而会把真问题盖掉。
     */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "查错误提示";
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                new ParamDef("errorTexts", "错误提示文案", ParamDef.Type.TEXTAREA,
                        false, DEFAULT_ERROR_TEXTS, null,
                        "一行一个关键词，命中任意一个即判失败。按自家 APP 的实际文案调整", null),
                ParamDef.bool("failOnMatch", "命中时判失败", true)
                        .withHelp("关掉则只记告警不中断流程，适合先观察一段时间再决定是否收严")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> keywords = parseKeywords(ctx.str("errorTexts"));
        if (keywords.isEmpty()) {
            throw ctx.fail("断言无错误提示", "未配置任何错误提示文案", null);
        }

        String source;
        try {
            source = ctx.getDriver().getPageSource(ctx.getDeviceId());
        } catch (Exception e) {
            // 拿不到控件树就没法判断，如实说明而不是假装通过
            ctx.warn("断言无错误提示", "无法获取控件树，本次跳过检查: " + e.getMessage(), null);
            return;
        }
        if (source == null || source.isBlank()) {
            ctx.warn("断言无错误提示", "控件树为空，本次跳过检查", null);
            return;
        }

        String haystack = source.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String keyword : keywords) {
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits.add(keyword);
            }
        }

        if (hits.isEmpty()) {
            ctx.ok("断言无错误提示", "未发现已知错误提示（检查 " + keywords.size() + " 项）");
            return;
        }

        String detail = "页面出现错误提示：" + String.join("、", hits);
        if (!ctx.boolParam("failOnMatch", true)) {
            ctx.warn("断言无错误提示", detail + "（按配置只告警不中断）", null);
            return;
        }
        throw ctx.fail("断言无错误提示", detail, null, "出现错误提示：" + String.join("、", hits));
    }

    /** 一行一个；也容忍用逗号分隔 */
    private static List<String> parseKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("[\\r\\n,，]+")) {
            String keyword = line.trim();
            if (!keyword.isEmpty() && !out.contains(keyword)) {
                out.add(keyword);
            }
        }
        return out;
    }
}
