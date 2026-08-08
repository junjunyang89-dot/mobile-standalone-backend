package com.mobile.standalone.service.keyword;

import java.util.List;

/**
 * 一个可拖拽关键字的完整定义：目录元数据 + 参数契约 + 截图策略 + 执行逻辑，全部收在一个类里。
 * <p>
 * 以前这些分散在四处——{@code UiKeywordService} 的 seed 字符串、{@code UiFlowExecutor} 的 switch、
 * 以及三个平行的截图策略 switch——加一个关键字要改五个地方，且互不校验。
 * 实现类标注 {@code @Component} 即自动注册，无需在任何名单里登记。
 */
public interface KeywordHandler {

    /** 主 code，流程 steps 里引用的值 */
    String code();

    String nameZh();

    /** ACTION / WAIT / ASSERT / DEVICE / FLOW */
    String category();

    String description();

    /** 关键字面板排序，越小越靠前 */
    int sortOrder();

    /** 是否必须绑定元素 */
    default boolean needsElement() {
        return false;
    }

    /**
     * 兼容用的旧 code。
     * <p>
     * 平台成对关键字合并后，历史流程里的 {@code SWIPE_IOS}、{@code COLD_START_IOS} 等仍能执行，
     * 不需要为了升级去改存量流程数据。
     */
    default List<String> aliases() {
        return List.of();
    }

    default ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.SINGLE;
    }

    /** 截图与日志用的短标签，默认取中文名 */
    default String shortLabel() {
        return nameZh();
    }

    /**
     * 本关键字失败后能否直接重试。
     * <p>
     * UI 自动化的失败很大一部分是偶发的（元素刚好还没渲染完、点击落空、会话抖动），
     * 重试一次就能过。但带嵌套步骤或有副作用的关键字不能这么干——重试会把
     * 分支里已经执行过的步骤再跑一遍（重复点击、重复发评论），所以这些一律关掉。
     */
    default boolean retryable() {
        return true;
    }

    /** 参数契约：执行前据此校验，同时序列化给前端渲染表单 */
    List<ParamDef> params();

    /**
     * 前端是否用专用编辑器渲染。
     * <p>
     * 嵌套分支、多模式安装这类交互不是参数表能描述的，通用渲染反而难用；
     * 其余关键字前端一律按 {@link #params()} 通用渲染，后端加关键字前端零改动。
     */
    default boolean customUi() {
        return false;
    }

    void execute(KeywordContext ctx) throws Exception;
}
