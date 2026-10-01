package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiTestRun {
    private String id;
    private String environment;
    /** 按集合执行时记下是哪个集合，报告里才认得出「XX 集合 · 第 N 次」 */
    private String collectionId;
    private Integer total;
    private Integer passed;
    private Integer failed;
    /** 没跑成的条数（连不上/超时/用例不存在）。不进通过率分母，单独看 */
    private Integer blocked;
    /** 线上执行时跳过的「只在测试跑」用例数 */
    private Integer skipped;
    private Long durationMs;
    private Boolean ok;
    private String message;
    /** MANUAL | SCHEDULE */
    private String triggerType;
    private String createdBy;
    private LocalDateTime createdAt;

    /** 以下只在报告详情里查出来，不落库 */
    private String collectionName;
    /** 同一集合的第几次执行；不是按集合跑的为 null */
    private Integer seqNo;
}
