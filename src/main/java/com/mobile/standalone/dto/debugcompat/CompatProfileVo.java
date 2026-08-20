package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 返回体（破坏版）。
 * <p>
 * 相对基线：score String→Integer（TYPE_CHANGED）；删除 remark（REMOVED）。
 */
@Data
public class CompatProfileVo {
    private Long id;
    /** 展示名 */
    private String name;
    /** 破坏：由 String 改为 Integer */
    private Integer score;
}
