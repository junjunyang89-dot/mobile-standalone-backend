package com.mobile.standalone.service.keyword;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 把 {@link ParamDef} 列表序列化成 {@code ui_keywords.param_schema}。
 * <p>
 * 字段名沿用历史 schema（name/label/type/required/default/options），前端可直接据此渲染表单。
 */
public final class ParamSchemaWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ParamSchemaWriter() {
    }

    public static String toJson(List<ParamDef> params) {
        ArrayNode arr = JSON.createArrayNode();
        if (params != null) {
            for (ParamDef def : params) {
                arr.add(toNode(def));
            }
        }
        return arr.toString();
    }

    private static ObjectNode toNode(ParamDef def) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", def.name());
        node.put("label", def.label());
        node.put("type", def.type().jsonValue());
        node.put("required", def.required());
        Object dv = def.defaultValue();
        if (dv instanceof Boolean b) {
            node.put("default", b);
        } else if (dv instanceof Integer || dv instanceof Long || dv instanceof Short) {
            node.put("default", ((Number) dv).longValue());
        } else if (dv instanceof Number n) {
            node.put("default", n.doubleValue());
        } else if (dv != null) {
            node.put("default", String.valueOf(dv));
        }
        if (def.options() != null && !def.options().isEmpty()) {
            ArrayNode opts = node.putArray("options");
            def.options().forEach(opts::add);
        }
        if (def.help() != null && !def.help().isBlank()) {
            node.put("help", def.help());
        }
        ParamDef.ShowWhen sw = def.showWhen();
        if (sw != null) {
            ObjectNode cond = node.putObject("showWhen");
            cond.put("param", sw.param());
            ArrayNode vals = cond.putArray("values");
            sw.values().forEach(vals::add);
        }
        return node;
    }
}
