package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 下单入参（基线）。破坏版会新增必填字段 region。
 */
@Data
public class CompatOrderRequest {
    private String sku;
    private Integer qty;
}
