package com.mobile.standalone.service.h5;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.remote.SupportsContextSwitching;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 系统 WebView：能切 context 时直接 executeScript，不依赖 CDP 端口。
 * X5 上切失败就当这条路不存在，由调用方换下一条策略。
 */
@Slf4j
public final class H5WebViewJs {

    private H5WebViewJs() {
    }

    public static H5DomClick.Hit run(AppiumDriver driver, String text, String css, String typeValue) {
        if (!(driver instanceof SupportsContextSwitching ctxDriver)) {
            return new H5DomClick.Hit(false, 0, 0, 0, 0, "", "驱动不支持 WebView context");
        }
        String original = null;
        try {
            original = ctxDriver.getContext();
            Set<String> handles = ctxDriver.getContextHandles();
            List<String> webs = new ArrayList<>();
            if (handles != null) {
                for (String c : handles) {
                    if (c != null && (c.startsWith("WEBVIEW_") || c.startsWith("CHROMIUM"))) {
                        webs.add(c);
                    }
                }
            }
            if (webs.isEmpty()) {
                return new H5DomClick.Hit(false, 0, 0, 0, 0, "", "没有 WEBVIEW context");
            }
            String lastErr = "";
            for (String ctx : webs) {
                try {
                    ctxDriver.context(ctx);
                    Object raw = driver.executeScript(
                            "return " + H5DomClick.expression(text, css, typeValue) + ";");
                    H5DomClick.Hit hit = H5DomClick.fromRaw(raw);
                    if (hit.ok()) {
                        log.info("H5 走 WebView JS: context={}", ctx);
                        return hit;
                    }
                    lastErr = hit.message();
                } catch (Exception e) {
                    lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.info("切 {} 跑 H5 JS 失败（可忽略）: {}", ctx, lastErr);
                }
            }
            return new H5DomClick.Hit(false, 0, 0, 0, 0, "", lastErr);
        } catch (Exception e) {
            return new H5DomClick.Hit(false, 0, 0, 0, 0, "", e.getMessage());
        } finally {
            if (original != null) {
                try {
                    ctxDriver.context(original);
                } catch (Exception ignored) {
                    // 切不回去由后续命令自己重建
                }
            }
        }
    }
}
