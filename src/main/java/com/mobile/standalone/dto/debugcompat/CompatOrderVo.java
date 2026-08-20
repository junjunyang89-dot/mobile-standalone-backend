package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 下单返回（基线）。破坏版会删除 status 字段。
 */
@Data
public class CompatOrderVo {
    private String orderId;
    private Double amount;
    private String status;
}
