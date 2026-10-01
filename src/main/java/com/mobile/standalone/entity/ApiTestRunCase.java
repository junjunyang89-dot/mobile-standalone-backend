package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiTestRunCase {
    private String id;
    private String runId;
    private String caseId;
    private String name;
    private String method;
    private String path;
    private String url;
    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private String error;
    /**
     * 运行时这条用例是什么种类（BRANCH / REGRESSION / UNCLASSIFIED），可空。
     *
     * <p><b>为什么要在这儿快照，而不是查询时 join api_test_cases</b>：
     * 一条分支用例在提测结束后会被分诊——退役，或者升级成回归用例。
     * 如果分桶靠 join 现在的 case_kind，那次升级会<b>把上周那次运行的分桶一起改掉</b>：
     * 当时它确实是作为分支用例跑的，报表却会显示它在回归桶里。
     * 历史数据被静默改写，而且没有任何地方会报错。
     *
     * <p>这张表本来就是这个模式——name / method / path / url 都是快照，
     * 正是因为用例可能被改、被删。
     *
     * <p><b>空值的含义是「没记录」，不是「未分类」</b>：加这个字段之前的历史运行，
     * 以及用例查不到的那些 BLOCKED，都是空。折叠分桶时要单独成一组，
     * 不能走 {@code ApiCaseKind.of(null)}——那会返回 UNCLASSIFIED，
     * 于是「没记录」被悄悄算进回归桶的分母。
     */
    private String caseKind;
    /**
     * 每条断言的结果、以及实际发出去/收到的内容。
     * <p>
     * 不存的话，报告页只能给一句「失败」——人还得自己猜发了什么、收到了什么，
     * 于是拿 Postman 再手工重放一遍。那一步正是这套东西本该省掉的。
     */
    private String assertionResults;
    private String bodyPreview;
    private String reqHeaders;
    private String reqBody;
    private String respHeaders;
    private LocalDateTime createdAt;
}
