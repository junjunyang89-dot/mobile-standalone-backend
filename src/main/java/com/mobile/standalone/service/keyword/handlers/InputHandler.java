package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InputHandler implements KeywordHandler {

    @Override
    public String code() {
        return "INPUT";
    }

    @Override
    public String nameZh() {
        return "输入";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "向元素输入文本；支持 ${变量} 与 ${date}/${timestamp} 等内置变量";
    }

    @Override
    public int sortOrder() {
        return 20;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "元素英文名"),
                ParamDef.string("text", "输入内容")
                        .withHelp("留空则输入空字符串；清空输入框请用「清空」关键字")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> selectors = ctx.selectors("输入");
        String text = ctx.substituted("text");
        if (text == null) {
            text = "";
        }
        boolean ok = false;
        String used = selectors.get(0);
        for (String selector : selectors) {
            used = selector;
            ok = ctx.getDriver().inputTextToElement(ctx.getDeviceId(), selector, text);
            if (ok) break;
        }
        if (!ok) {
            throw ctx.fail("输入", "输入失败: " + ctx.getElementKey(), used);
        }
        ctx.ok("输入", ctx.getElementKey() + " ← " + mask(text));
    }

    /** 手机号/验证码等敏感内容打码，避免执行日志泄露账号 */
    static String mask(String text) {
        if (text == null || text.length() < 7) {
            return "****";
        }
        return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
    }
}
