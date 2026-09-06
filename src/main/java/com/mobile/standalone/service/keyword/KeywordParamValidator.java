package com.mobile.standalone.service.keyword;

import com.mobile.standalone.service.UiFlowExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行前按 {@link KeywordHandler#params()} 校验步骤参数。
 * <p>
 * 以前 param_schema 里的 required 从没人验过，缺参数要跑到关键字内部才炸、报错还看不出是哪一步缺什么。
 * 这里只校验关键字本来就会失败的必填项，不会让原本能跑的存量流程变红。
 */
public final class KeywordParamValidator {

    private KeywordParamValidator() {
    }

    public static void validate(KeywordHandler handler, KeywordContext ctx) throws UiFlowExecutionException {
        List<String> problems = new ArrayList<>();

        if (handler.needsElement() && isBlank(ctx.getElementKey())) {
            problems.add("缺少元素（elementKey）");
        }

        for (ParamDef def : handler.params()) {
            if (def.type() == ParamDef.Type.ELEMENT
                    || def.type() == ParamDef.Type.ELEMENTS
                    || def.type() == ParamDef.Type.STEPS) {
                // 元素由 elementKey 承载，元素组与嵌套步骤是结构化的值，
                // 在这里按字符串校验只会得到 "[a, b]" 这种没意义的判断，交给关键字自己解析
                continue;
            }
            Object raw = ctx.rawParam(def.name());
            boolean present = raw != null && !String.valueOf(raw).isBlank();

            if (def.required() && !present) {
                problems.add("缺少必填参数「" + def.label() + "」(" + def.name() + ")");
                continue;
            }
            if (!present) {
                continue;
            }
            String value = String.valueOf(raw).trim();
            switch (def.type()) {
                case NUMBER -> {
                    if (!isNumeric(value)) {
                        problems.add("参数「" + def.label() + "」需要数字，收到：" + value);
                    }
                }
                case ENUM -> {
                    if (def.options() != null && !def.options().isEmpty()
                            && def.options().stream().noneMatch(o -> o.equalsIgnoreCase(value))) {
                        problems.add("参数「" + def.label() + "」取值非法：" + value
                                + "（可选：" + String.join("/", def.options()) + "）");
                    }
                }
                case BOOLEAN -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (!lower.equals("true") && !lower.equals("false")) {
                        problems.add("参数「" + def.label() + "」需要 true/false，收到：" + value);
                    }
                }
                default -> {
                    // 字符串类无需额外校验
                }
            }
        }

        if (!problems.isEmpty()) {
            String detail = handler.nameZh() + " 参数有误：" + String.join("；", problems);
            throw ctx.fail(handler.shortLabel(), detail, ctx.getElementKey());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isNumeric(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
