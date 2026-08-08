package com.mobile.standalone.service.keyword;

import java.util.List;

/**
 * 关键字参数定义。
 * <p>
 * 这是参数的唯一权威来源：既用于执行前校验，也序列化进 {@code ui_keywords.param_schema} 供前端渲染表单。
 * 以前 paramSchema 是手写 JSON 字符串、且前端根本没消费（表单靠 v-if 硬编码），两边必然漂移。
 */
public record ParamDef(
        String name,
        String label,
        Type type,
        boolean required,
        Object defaultValue,
        List<String> options,
        String help,
        ShowWhen showWhen
) {

    /**
     * 显示条件：只有当同一步骤里 {@code param} 的值落在 {@code values} 里时才渲染这个参数。
     * <p>
     * 用来消掉「一个关键字十个开关，用的时候不知道哪个跟哪个有关」。条件写在这里而不是前端，
     * 是为了保住「后端是参数唯一来源、新增关键字前端零改动」这条线。
     * <p>
     * 前端在参数被隐藏时会清掉它的值——否则会出现「界面上看不见、执行时还生效」的暗坑。
     */
    public record ShowWhen(String param, List<String> values) {
    }

    /** 与前端表单控件对应；序列化为小写字符串，保持与历史 param_schema 一致 */
    public enum Type {
        STRING,
        TEXTAREA,
        NUMBER,
        BOOLEAN,
        ENUM,
        /** 元素库选择器，前端渲染成元素下拉 */
        ELEMENT,
        /** 嵌套步骤列表，前端用专用编辑器，不做通用渲染 */
        STEPS;

        public String jsonValue() {
            return name().toLowerCase();
        }
    }

    public static ParamDef string(String name, String label) {
        return new ParamDef(name, label, Type.STRING, false, null, null, null, null);
    }

    public static ParamDef requiredString(String name, String label) {
        return new ParamDef(name, label, Type.STRING, true, null, null, null, null);
    }

    public static ParamDef text(String name, String label) {
        return new ParamDef(name, label, Type.TEXTAREA, false, null, null, null, null);
    }

    public static ParamDef number(String name, String label, Number defaultValue) {
        return new ParamDef(name, label, Type.NUMBER, false, defaultValue, null, null, null);
    }

    public static ParamDef bool(String name, String label, Boolean defaultValue) {
        return new ParamDef(name, label, Type.BOOLEAN, false, defaultValue, null, null, null);
    }

    public static ParamDef enumOf(String name, String label, List<String> options, String defaultValue) {
        return new ParamDef(name, label, Type.ENUM, false, defaultValue, List.copyOf(options), null, null);
    }

    public static ParamDef element(String name, String label) {
        return new ParamDef(name, label, Type.ELEMENT, true, null, null, null, null);
    }

    public static ParamDef steps(String name, String label) {
        return new ParamDef(name, label, Type.STEPS, false, null, null, null, null);
    }

    public ParamDef withHelp(String helpText) {
        return new ParamDef(name, label, type, required, defaultValue, options, helpText, showWhen);
    }

    public ParamDef optional() {
        return new ParamDef(name, label, type, false, defaultValue, options, help, showWhen);
    }

    /** 仅当同一步骤里 {@code param} 取到这些值之一时才显示本参数 */
    public ParamDef showWhen(String param, Object... values) {
        List<String> vs = java.util.Arrays.stream(values).map(String::valueOf).toList();
        return new ParamDef(name, label, type, required, defaultValue, options, help,
                new ShowWhen(param, vs));
    }
}
