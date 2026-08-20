package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 返回体（基线）。后续故意删字段 / 改类型，供接口对比检出 BREAKING。
 */
@Data
public class CompatProfileVo {
    private Long id;
    /** 展示名 */
    private String name;
    /** 基线是 String；破坏版会改成 Integer → TYPE_CHANGED */
    private String score;
    /** 基线有此字段；破坏版会删除 → REMOVED */
    private String remark;
}
