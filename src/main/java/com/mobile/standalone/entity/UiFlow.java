package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拖拽编排的流程/组件。steps 只引用 keywordCode + elementKey，不写死 xpath。
 */
@Data
public class UiFlow {
    private String id;
    private String name;
    private String description;
    private String appId;
    private String platform;
    /** 轨道：AUTOMATION / ONLINE / SHARED / PERFORMANCE。只说明「哪个入口用」，不表达环境 */
    private String catalog;
    /** 环境：PROD / TEST。跟 catalog 正交——两个环境跑的是两批不同的流程，测的点也不一样 */
    private String environment;
    /**
     * 这条流程是否<b>绑定环境</b>。
     * <p>
     * 装包类流程必须绑：测试环境装蒲公英 debug 包、线上装渠道包，两套完全不同的路径。
     * 但<b>纯功能验证</b>类不该绑——验的是功能本身，跟连哪个后端无关。
     * <p>
     * 不区分的话会卡在这个真实场景上：测试环境回归通过后，同一个 debug 包切到生产环境
     * 再跑一遍「发版前线上回归」。这一轮要跑的流程和测试环境回归<b>完全一样</b>
     * （存量 + 本版新增），差别只在后端连哪边——而后端是包里配的，不是流程的属性。
     * 按环境隔离的话，那些刚在测试环境写好的新流程在线上这边看不见，只能重写一遍。
     * <p>
     * 存量流程默认 true，行为一个字不变。
     */
    private Boolean envBound;
    /**
     * 这条流程验的功能，<b>线上包里有没有</b>。TESTING＝本版新功能；ONLINE＝已上线、可常驻。
     * <p>
     * 决定一条流程能不能跑的从来不是「哪个环境」，是「这个包里有没有这个功能」——
     * 「发版前线上回归」和「APP 线上回归」都是生产环境，但前者跑的是 debug 包（有新功能）、
     * 后者跑的是上个版本的线上包（没有）。用 environment 表达不了这个区别。
     * <p>
     * 存量流程默认 ONLINE：它们本来就在线上回归里跑着，一条都不能掉出去。
     */
    private String releaseStage;
    private Boolean isComponent;
    /** JSON 数组字符串 */
    private String steps;
    /** 前置流程 ID 的 JSON 数组。批量执行时按依赖拓扑排序，被依赖的流程一批里只跑一次 */
    private String dependsOn;
    private Boolean enabled;
    /** 列表/线上回归排序，越小越靠前 */
    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
