package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口测试用例：path 相对环境 baseUrl（或后续 URL 管理模块下拉）；执行时再选 TEST/PROD。
 */
@Data
public class ApiTestCase {
    private String id;
    /** 项目编码，如 data-center；用于筛选与后续 URL 下拉归属 */
    private String project;
    /**
     * 适用环境：TEST / PROD / ANY（双环境通用）。
     * 列表与执行按环境筛选时，ANY 会同时出现在测试与线上。
     */
    private String environment;
    private String name;
    private String description;
    private String requirementRef;
    /** GET/POST/PUT/PATCH/DELETE */
    private String method;
    /** 相对路径，如 /appdatacenter/deadlock/...；也可写绝对 URL（则忽略 URL/环境 baseUrl） */
    private String path;
    /**
     * 绑定的 URL 管理条目 id（资产管理 · URL）；执行时用其 baseUrl。
     * 为空则回退到运行所选环境的 api_test_envs.baseUrl。
     */
    private String urlId;
    /** JSON object */
    private String query;
    /** JSON object，覆盖/合并环境默认头 */
    private String headers;
    private String body;
    /**
     * JSON 数组断言，例如：
     * [{"type":"status","equals":200},{"type":"jsonPath","path":"$.code","equals":0},{"type":"bodyIncludes","value":"data"}]
     */
    private String assertions;
    /**
     * 执行后从响应里抽变量，如 {@code {"token":"$.result.token"}}。
     * 抽到的值写进本轮上下文，后面的用例引用 <code>{{token}}</code> 即可——
     * 靠它才能把登录态自动传下去，而不是把 token 写死在环境里等它过期。
     */
    private String extractJson;
    /** 归属集合。集合是执行单元，集合内按 sortOrder 顺序跑并共享一份运行时变量 */
    private String collectionId;
    /**
     * 归属接口（{@code api_endpoints.id}）。
     * <p>
     * 由服务层按 项目+方法+路径 自动挂，不需要调用方填——靠调用方记着填，
     * 迟早有一条漏了，而漏了的表现是清单上那个接口显示「0 条用例」，
     * 于是有人去补一条已经存在的用例。
     */
    private String endpointId;
    /**
     * 字段说明 JSON，例如 {"winRate":"胜率"}，方便后人认字段（不做断言）。
     */
    private String fieldNotes;
    private Boolean enabled;
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
