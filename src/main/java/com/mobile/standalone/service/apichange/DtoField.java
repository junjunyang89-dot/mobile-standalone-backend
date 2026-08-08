package com.mobile.standalone.service.apichange;

/**
 * 返回体/请求体里的一个字段。
 *
 * @param name     Java 字段名
 * @param type     Java 类型
 * @param comment  字段上方 javadoc 里的中文名——这几个项目都这么写，
 *                 「winRate（胜率）」比只给英文名有用得多
 * @param jsonName {@code @JsonProperty} 改过的名字；没改就等于 name。
 *                 抓包看到的是这个名字，不是 Java 字段名
 * @param ignored  {@code @JsonIgnore}：根本不会出现在 JSON 里。
 *                 列出来是误导——人会对着抓包找一个永远找不到的字段
 */
public record DtoField(String name, String type, String comment, String jsonName, boolean ignored) {

    public DtoField(String name, String type, String comment) {
        this(name, type, comment, name, false);
    }

    /** 抓包时看到的名字 */
    public String wireName() {
        return jsonName == null || jsonName.isBlank() ? name : jsonName;
    }

    public String display() {
        return comment == null || comment.isBlank() ? wireName() : wireName() + "（" + comment + "）";
    }
}
