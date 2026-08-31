package com.mobile.standalone.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户端 App 与项目的关联。
 * <p>
 * 补的是平台里一直缺的那一层：接口侧的资产按 {@code project} 归属，UI 自动化侧的资产
 * （流程／页面／元素）按 {@code appId} 归属，两套键之间原先<b>没有任何映射</b>，
 * 所以它们挂不到同一棵树上。这张表就是那座桥。
 * <p>
 * <b>注意别和设备侧的 app 混了</b>：{@code tasks} / {@code test_plans} / {@code test_reports}
 * 里的 {@code app_id} 指向 {@code apps} 表（设备上已安装的应用实例），不是 {@code ui_apps}。
 * 实测 216 条 task 里 0 条能匹配上 ui_apps。那是设备维度，与本关联无关。
 */
@Data
public class AppProjectLink {

    /** 客户端本体：这个项目就是该 App 的某一端 */
    public static final String RELATION_OWNS = "OWNS";
    /** 服务方：这个项目是为该 App 提供服务的后端 */
    public static final String RELATION_SERVES = "SERVES";

    /** {@code ui_apps.id}，如 DOMESTIC_ESPORTS */
    private String appId;

    /** {@code api_projects.code}，如 app-esports-android */
    private String projectCode;

    /**
     * 该关联针对哪个端：ANDROID / IOS / HARMONY；留空表示不分端。
     * <p>
     * 这一列是必要的：一个 ui_app 同时覆盖 Android 和 iOS，而项目侧按端拆开
     * （{@code app-esports-android} / {@code app-esports-ios}）。不分端的话，
     * 电竞国内那 1523 个元素会同时算进两个项目，看上去像翻了一倍。
     * 带上端之后是 Android 1433 / iOS 90，与实际相符。
     */
    private String platform;

    /** {@link #RELATION_OWNS} / {@link #RELATION_SERVES} */
    private String relation;

    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---- 关联查询回填，非表字段 ----

    /** App 中文名，列表直接展示，省得前端再查一次 */
    private transient String appName;
    /** 项目展示名 */
    private transient String projectName;
}
