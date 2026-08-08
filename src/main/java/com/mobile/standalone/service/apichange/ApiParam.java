package com.mobile.standalone.service.apichange;

/**
 * 接口入参。
 * <p>
 * <b>比返回体更该盯</b>：返回体多个字段一般向后兼容，而<b>新增一个必填入参会让老客户端直接 400</b>，
 * 是线上事故的常客。
 *
 * @param source   来源：QUERY(@RequestParam) / PATH(@PathVariable) / BODY(@RequestBody) / OTHER
 * @param name     参数名
 * @param type     Java 类型
 * @param required 是否必填；@RequestParam 默认 required=true
 */
public record ApiParam(String source, String name, String type, boolean required) {

    public String key() {
        return source + ":" + name;
    }

    public String display() {
        return name + " (" + type + (required ? ", 必填" : "") + ")";
    }
}
