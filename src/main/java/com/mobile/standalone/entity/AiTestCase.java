package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生成的测试用例文档（表 ai_test_cases），与自动化 test_cases 分离。
 */
@Data
public class AiTestCase {
    private Long id;
    private String caseId;
    private String pageName;
    /** 用例库分类：performance / domestic-api / wmpvp-ios ... */
    private String category;
    /** 功能模块（一级） */
    private String module;
    /** 子功能（二级） */
    private String subModule;
    /** 测试项（三级） */
    private String testItem;
    /** 测试点（四级，比测试项更细） */
    private String testPoint;
    private String title;
    private String priority;
    private String type;
    private String precondition;
    /** JSON 字符串：[{step,action,target,expected}, ...] */
    private String stepsJson;
    /** 整条用例的期望结果，区别于步内的 expected */
    private String expectedResult;
    /**
     * 关联的 {@code ui_flows.id}，为空＝尚未自动化。
     * <p>
     * 「是否已实现自动化」由它推导，不做成人手填的列——人手填的会立刻过期：
     * 流程停用或删了，没人会回来改那一列。
     */
    private String flowId;
    /**
     * 关联流程的时刻。
     * <p>
     * 和 {@link #contentUpdatedAt} 一起判断「流程是否已经跟不上用例」——
     * 用例的期望结果从「四项区号」改成六项，流程还在断言四项，
     * 覆盖标记照样是绿的，但它覆盖的是旧需求。这比没做自动化更糟：没做你知道要人测。
     */
    private LocalDateTime linkedAt;
    /**
     * 用例<b>实质内容</b>最后一次改动的时刻。
     * <p>
     * 不用 {@code updatedAt}：那个改备注、改优先级也会动，
     * 拿它判过期会天天误报，而误报几次之后这个标记就没人看了。
     */
    private LocalDateTime contentUpdatedAt;
    /**
     * 自动化建议（值不值得做 UI 自动化），与 flowId 落地状态正交：
     * RECOMMENDED / PARTIAL / NOT_RECOMMENDED；空＝尚未评估。
     */
    private String automationAdvice;
    /**
     * 发布轨（与复核 status 正交）：
     * TESTING＝测试待测；ONLINE＝线上稳定。
     */
    private String releaseStage;
    private String remark;
    private String screenshotUrl;
    private String platform;
    private String appId;
    private String source;
    private String status;
    private String batchId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
