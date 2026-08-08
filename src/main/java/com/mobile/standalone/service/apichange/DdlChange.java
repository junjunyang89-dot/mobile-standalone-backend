package com.mobile.standalone.service.apichange;

/**
 * 一条表结构变更。
 * <p>
 * 只报「涉及了 t_xxx 表」没什么用，人还得自己去翻 SQL。加了什么列、什么类型才是能直接拿去
 * 造数据、对字段的信息。
 */
public record DdlChange(String table, String action, String column, String detail) {
}
