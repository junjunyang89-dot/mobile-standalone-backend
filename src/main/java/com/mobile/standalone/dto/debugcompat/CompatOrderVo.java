package com.mobile.standalone.dto.debugcompat;

import lombok.Data;

/**
 * debug-compat 下单返回（破坏版）。
 * <p>
 * 相对基线：删除 status（REMOVED）。
 */
@Data
public class CompatOrderVo {
    private String orderId;
    private Double amount;
}
