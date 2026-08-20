package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 下单入参（破坏版）。
 * <p>
 * 相对基线：新增必填 region（老客户端不传会 400 → BREAKING）。
 */
@Data
public class CompatOrderRequest {
    private String sku;
    private Integer qty;
    /** 破坏：新增必填区域 */
    private String region;
}
