package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.dto.ExecutionLogEntryDto;
import com.mobile.standalone.service.AppPackageInstallService;
import com.mobile.standalone.service.InstallMode;
import com.mobile.standalone.service.InstallRequest;
import com.mobile.standalone.service.PgyerAppService;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多方式安装。各安装方式所需字段差别很大（蒲公英 / 渠道页 / 本地包 / 扫码），
 * 前端保留专用编辑器按方式切换表单，不走通用参数表渲染。
 */
@Component
public class InstallAppHandler implements KeywordHandler {

    /** 兼容旧关键字名：未显式指定安装方式时默认渠道页 */
    private static final String LEGACY_STORE_CODE = "INSTALL_FROM_STORE";

    @Override
    public String code() {
        return "INSTALL_APP";
    }

    @Override
    public String nameZh() {
        return "安装应用";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "多方式安装：PGYER / CHANNEL_PAGE / LOCAL_APK / QR_ASSIST（Android 先）";
    }

    @Override
    public int sortOrder() {
        return 7;
    }

    @Override
    public List<String> aliases() {
        return List.of(LEGACY_STORE_CODE);
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public boolean customUi() {
        return true;
    }

    @Override
    public String shortLabel() {
        return "安装应用";
    }

    /** 不重试：安装是重操作且有副作用，失败应由人判断 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.enumOf("installMode", "安装方式",
                        List.of("PGYER", "CHANNEL_PAGE", "LOCAL_APK", "QR_ASSIST"), "CHANNEL_PAGE"),
                ParamDef.string("appId", "应用字典ID"),
                ParamDef.string("channelUrl", "渠道页URL"),
                ParamDef.string("pgyerAppKey", "蒲公英appKey"),
                ParamDef.string("pgyerBuildKey", "蒲公英buildKey"),
                ParamDef.string("pgyerUrl", "蒲公英/下载URL"),
                ParamDef.string("apkArtifactId", "已上传应用ID"),
                ParamDef.string("apkPath", "本地包路径"),
                ParamDef.string("qrPayload", "扫码安装页URL"),
                ParamDef.bool("uninstallFirst", "安装前先卸载", true)
                        .withHelp("关掉＝覆盖安装（保留旧数据）。升级测试必须关，"
                                + "否则测的还是全新安装，验不到数据迁移"),
                ParamDef.enumOf("useVersion", "装哪个版本",
                                List.of("LATEST", "PREVIOUS"), "LATEST")
                        .withHelp("LATEST＝最新版；PREVIOUS＝<b>上一个版本</b>，自动从蒲公英取，"
                                + "用来搭升级测试的第一步。自动取而不是填死版本号——"
                                + "填死的话每发一版都要回来改流程，忘了就变成拿新版覆盖新版，测不到东西还照样是绿的")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String appId = KeywordContext.firstNonBlank(
                ctx.str("appId"), ctx.getUiAppId(),
                ctx.getExecParams() != null ? ctx.getExecParams().get("uiAppId") : null);
        if (appId == null || appId.isBlank()) {
            throw ctx.fail("安装应用", "安装需要 appId（步骤参数、流程绑定或任务 uiAppId）", null);
        }

        InstallRequest request = InstallRequest.builder()
                .installMode(InstallMode.from(ctx.strOrExec("installMode")))
                .channelUrl(ctx.strOrExec("channelUrl"))
                .pgyerAppKey(ctx.strOrExec("pgyerAppKey"))
                .pgyerBuildKey(resolveBuildKey(ctx))
                .pgyerUrl(ctx.strOrExec("pgyerUrl"))
                .apkArtifactId(ctx.strOrExec("apkArtifactId"))
                .apkPath(ctx.strOrExec("apkPath"))
                .qrPayload(ctx.strOrExec("qrPayload"))
                .uninstallFirst(resolveUninstallFirst(ctx))
                .build();

        // 旧关键字名未显式写 installMode 时保持渠道页语义
        if (LEGACY_STORE_CODE.equalsIgnoreCase(ctx.getRawCode()) && ctx.strOrExec("installMode") == null) {
            request.setInstallMode(InstallMode.CHANNEL_PAGE);
        }

        if (request.getInstallMode() == InstallMode.QR_ASSIST) {
            String qr = KeywordContext.firstNonBlank(
                    request.getQrPayload(), request.getPgyerUrl(), request.getChannelUrl());
            ExecutionLogEntryDto hint = ExecutionLogEntryDto.success(
                    ctx.sn("请扫码安装"), "请用手机扫描下方二维码完成安装，系统将轮询包名是否落地");
            hint.setQrPayload(qr);
            ctx.getLogs().add(hint);
        }

        AppPackageInstallService.OpResult r =
                ctx.getRuntime().appPackageInstallService().install(appId, ctx.getDevice(), request);
        String label = "安装应用(" + request.getInstallMode() + ")";

        if (!r.success) {
            ExecutionLogEntryDto failLog = ExecutionLogEntryDto.fail(ctx.sn(label), r.message, appId);
            if (r.qrPayload != null) {
                failLog.setQrPayload(r.qrPayload);
            }
            ctx.getLogs().add(failLog);
            throw new com.mobile.standalone.service.UiFlowExecutionException(
                    "安装失败: " + r.message, ctx.getLogs());
        }

        ExecutionLogEntryDto okLog = ExecutionLogEntryDto.success(ctx.sn(label), r.message);
        if (r.qrPayload != null) {
            okLog.setQrPayload(r.qrPayload);
        }
        ctx.getLogs().add(okLog);
        ctx.screenshot("安装后");
    }

    /** 默认先卸载；步骤参数与执行参数都可覆盖 */
    /**
     * 执行时选的版本优先于流程里存的。
     * <p>
     * 一般参数是「流程里配好的为主、执行参数兜底」，但版本不一样：人在执行对话框里挑了一个，
     * 那就是他这次的意图。若沿用通用规则，流程里哪天被填进一个 buildKey，选择器就会静默失效——
     * 界面上选了 A，实际装的是 B，而日志显示「安装成功」。
     */
    private String resolveBuildKey(KeywordContext ctx) throws Exception {
        String picked = ctx.getExecParams() == null ? null
                : ctx.getExecParams().get("pgyerBuildKeyOverride");
        if (picked != null && !picked.isBlank()) {
            return picked.trim();
        }
        String explicit = ctx.strOrExec("pgyerBuildKey");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        // 「装上一个版本」：升级测试的第一步，自动取，不用人每版回来改版本号
        if ("PREVIOUS".equalsIgnoreCase(ctx.strOrExec("useVersion"))) {
            return resolvePreviousBuildKey(ctx);
        }
        return null;
    }

    /**
     * @return 上一个版本的 buildKey；只有一个版本时抛出——
     *         那说明这条升级流程建在了一个还没有历史版本的应用上，
     *         静默退回装最新版的话，跑出来是绿的但什么都没验到
     */
    private String resolvePreviousBuildKey(KeywordContext ctx) throws Exception {
        PgyerAppService pgyer = ctx.getRuntime().pgyerAppService();
        if (pgyer == null) {
            throw ctx.fail("安装应用", "蒲公英服务不可用，取不到上一个版本", null,
                    "「装哪个版本＝上一版」需要蒲公英；改成 LATEST 或手填 buildKey");
        }
        String appKey = ctx.strOrExec("pgyerAppKey");
        PgyerAppService.PgyerBuildVo prev = pgyer.previousBuild(
                appKey, ctx.isIos() ? "IOS" : "ANDROID", null);
        if (prev == null) {
            throw ctx.fail("安装应用", "蒲公英上只有一个版本，没有「上一版」可装", null,
                    "升级测试要求至少有两个不同版本。等下次发版后再跑这条流程");
        }
        ctx.ok("选版本", "上一个版本：" + prev.getBuildVersion()
                + "(build " + prev.getBuildBuildVersion() + ")");
        return prev.getBuildKey();
    }

    private boolean resolveUninstallFirst(KeywordContext ctx) {
        Object raw = ctx.rawParam("uninstallFirst");
        if (raw == null && ctx.getExecParams() != null) {
            raw = ctx.getExecParams().get("uninstallFirst");
        }
        return raw == null || !"false".equalsIgnoreCase(String.valueOf(raw));
    }
}
