package com.mobile.standalone.service.apitest;

import java.util.Locale;

/**
 * 涉及接口 → 后端项目：显式选择优先，否则按路径前缀推断。
 * <p>
 * 抓包挂上的接口经常缺 project，但路径本身已经说明了归属
 * （如 {@code /gld/deadlock/*} → global-data）。
 */
public final class RequirementEndpointProjectResolver {

    private RequirementEndpointProjectResolver() {
    }

    /** 用户选了项目就用；没选则按路径猜 */
    public static String resolve(String explicitProject, String path) {
        if (explicitProject != null && !explicitProject.isBlank()) {
            return ApiAppCatalog.treeProjectKey(explicitProject);
        }
        return inferFromPath(path);
    }

    /** 按 URL 路径推断 catalog 项目编码；认不出返回 null */
    public static String inferFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String p = path.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("/gld/") || p.contains("/deadlock/")) {
            return "app-global-data";
        }
        if (p.startsWith("/appdatacenter/")) {
            return "app-data-center";
        }
        return null;
    }
}
