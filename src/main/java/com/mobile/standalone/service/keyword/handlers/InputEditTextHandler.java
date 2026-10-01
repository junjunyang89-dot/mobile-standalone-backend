package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向当前焦点输入框写字。
 * <p>
 * 「输入」会对定位到的节点 sendKeys。资讯底栏「友好评论，友善社区」是占位 TextView，
 * 点开后真正可写的是 {@code et_comment} 这类 EditText。本关键字先点所选元素开门，
 * 再对焦点框输入，不 clear hint。
 */
@Component
public class InputEditTextHandler implements KeywordHandler {

    @Override
    public String code() {
        return "INPUT_EDITTEXT";
    }

    @Override
    public List<String> aliases() {
        return List.of("EDITTEXT");
    }

    @Override
    public String nameZh() {
        return "EditText输入";
    }

    @Override
    public String category() {
        return "ACTION";
    }

    @Override
    public String description() {
        return "向当前焦点 EditText / 输入框写入文本。点开「友好评论」这类占位后用这个，不要对占位文案用「输入」";
    }

    @Override
    public int sortOrder() {
        return 21;
    }

    @Override
    public boolean needsElement() {
        return true;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.element("elementKey", "先点的元素")
                        .withHelp("占位文案、真正的输入框都可以。会先点它再向焦点框输入"),
                ParamDef.string("text", "输入内容")
                        .withHelp("留空则输入空字符串；支持 ${变量} 与 ${date}/${timestamp}")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        List<String> selectors = ctx.selectors("EditText输入");
        String text = ctx.substituted("text");
        if (text == null) {
            text = "";
        }
        boolean ok = false;
        String used = selectors.get(0);
        for (String selector : selectors) {
            used = selector;
            if (ctx.getDriver().inputTextToFocusedEditText(ctx.getDeviceId(), selector, text)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            // 占位已点开、主定位不在树上：只打当前焦点框
            ok = ctx.getDriver().inputTextToFocusedEditText(ctx.getDeviceId(), null, text);
        }
        if (!ok) {
            throw ctx.fail("EditText输入", "输入失败: " + ctx.getElementKey(), used);
        }
        ctx.ok("EditText输入", ctx.getElementKey() + " ← " + InputHandler.mask(text));
    }
}
