package com.mobile.standalone.service.keyword.handlers;

import com.mobile.standalone.service.AppPackageInstallService;
import com.mobile.standalone.service.keyword.KeywordContext;
import com.mobile.standalone.service.keyword.KeywordHandler;
import com.mobile.standalone.service.keyword.ParamDef;
import com.mobile.standalone.service.keyword.ScreenshotPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UninstallAppHandler implements KeywordHandler {

    @Override
    public String code() {
        return "UNINSTALL_APP";
    }

    @Override
    public String nameZh() {
        return "卸载应用";
    }

    @Override
    public String category() {
        return "DEVICE";
    }

    @Override
    public String description() {
        return "卸载目标应用；未安装则跳过。appId 或 packageName 二选一，缺省用流程绑定应用";
    }

    @Override
    public int sortOrder() {
        return 6;
    }

    @Override
    public ScreenshotPolicy screenshotPolicy() {
        return ScreenshotPolicy.NONE;
    }

    @Override
    public String shortLabel() {
        return "卸载";
    }

    /** 不重试：卸载有副作用，不做自动重试 */
    @Override
    public boolean retryable() {
        return false;
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
                ParamDef.string("appId", "应用字典ID"),
                ParamDef.string("packageName", "包名 / BundleId")
        );
    }

    @Override
    public void execute(KeywordContext ctx) throws Exception {
        String appId = KeywordContext.firstNonBlank(
                ctx.str("appId"), ctx.getUiAppId(),
                ctx.getExecParams() != null ? ctx.getExecParams().get("uiAppId") : null);
        String pkgOverride = KeywordContext.firstNonBlank(
                ctx.str("packageName"),
                ctx.getExecParams() != null ? ctx.getExecParams().get("packageName") : null);

        if ((appId == null || appId.isBlank()) && (pkgOverride == null || pkgOverride.isBlank())) {
            throw ctx.fail("卸载应用", "卸载需要 appId 或 packageName", null);
        }

        AppPackageInstallService.OpResult r =
                ctx.getRuntime().appPackageInstallService().uninstallApp(appId, pkgOverride, ctx.getDevice());
        if (!r.success) {
            throw ctx.fail("卸载应用", r.message, pkgOverride, "卸载失败: " + r.message);
        }
        ctx.ok("卸载应用", r.message);
        // 回归报告需要证据图：卸完拍一张桌面（不做连拍）
        ctx.screenshot("卸载后");
    }
}
