package com.mobile.standalone.service;

import lombok.Builder;
import lombok.Data;

/**
 * 多方式安装请求（流程步骤 params / 任务 config 均可映射到此）。
 */
@Data
@Builder
public class InstallRequest {
    @Builder.Default
    private InstallMode installMode = InstallMode.CHANNEL_PAGE;

    /** 覆盖应用字典中的渠道页；优先于厂商猜 URI / channel_urls */
    private String channelUrl;

    /** 蒲公英 appKey；空则用配置默认 */
    private String pgyerAppKey;
    /** 蒲公英 buildKey（优先） */
    private String pgyerBuildKey;
    /** 蒲公英或任意 https 直接下载包地址 */
    private String pgyerUrl;

    /** 已上传应用记录 ID（applications.id），取其 installPath */
    private String apkArtifactId;
    /** 直接指定本机 APK/IPA 路径（调试用） */
    private String apkPath;

    /** 扫码安装页 URL（https），写入执行日志供前端生成二维码 */
    private String qrPayload;

    /** 安装前是否先卸同包（蒲公英/本地包） */
    @Builder.Default
    private boolean uninstallFirst = true;
}
