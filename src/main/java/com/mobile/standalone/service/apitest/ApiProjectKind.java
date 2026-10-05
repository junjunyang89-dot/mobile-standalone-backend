package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiProject;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 仓库类型：后端 / 前端 / 客户端。产品线（电竞、POGPOG）仍走 {@link ApiProjectCatalog}，
 * 树上是「产品线 → 类型 → 仓库」。空类型不占位。
 */
public final class ApiProjectKind {

    private ApiProjectKind() {
    }

    public static final String BACKEND = ApiProject.KIND_BACKEND;
    public static final String FRONTEND = ApiProject.KIND_FRONTEND;
    public static final String CLIENT = ApiProject.KIND_CLIENT;

    /** 树上的展示顺序 */
    public static final List<String> ORDER = List.of(BACKEND, FRONTEND, CLIENT);

    /** 网关算后端，但不扫接口、不做接口自动化 */
    private static final Set<String> SKIP_SCAN = Set.of("app-gateway", "gateway");

    /** 启动时把这些编码标成前端（H5 / Vue / Swift 站），避免混进接口扫描 */
    public static final Set<String> FRONTEND_CODES = Set.of(
            "app-xiaomai", "xiaomai",
            "app-swift", "app-swift-v3", "app-swift-static", "swift-sphere",
            "pogpog-h5");

    /**
     * 要写进项目管理的前端仓库。不扫接口，但调用方分析 / 代码对比要能找到仓库。
     */
    public record FrontendCatalog(String code, String name, String description, String gitUrl) {
    }

    public static final List<FrontendCatalog> FRONTEND_CATALOG = List.of(
            new FrontendCatalog(
                    "app-swift",
                    "app-swift（电竞 H5）",
                    "电竞 APP 前端；不扫接口，调用方分析和代码对比用",
                    "http://gitlab.shdev.net/SteamCN/app-swift.git"),
            new FrontendCatalog(
                    "app-swift-v3",
                    "app-swift-v3（电竞 H5 v3）",
                    "电竞 APP 前端 v3；不扫接口，调用方分析和代码对比用",
                    "http://gitlab.shdev.net/SteamCN/app-swift-v3.git"));

    public static String normalize(String kind) {
        if (kind == null || kind.isBlank()) {
            return BACKEND;
        }
        String k = kind.trim().toUpperCase(Locale.ROOT);
        if (FRONTEND.equals(k) || "H5".equals(k) || "WEB".equals(k)) {
            return FRONTEND;
        }
        if (CLIENT.equals(k)) {
            return CLIENT;
        }
        if (BACKEND.equals(k)) {
            return BACKEND;
        }
        throw new IllegalArgumentException("仓库类型只能是 后端 / 前端 / 客户端");
    }

    public static String of(ApiProject p) {
        return p == null ? BACKEND : normalize(p.getKind());
    }

    public static String label(String kind) {
        return switch (normalize(kind)) {
            case FRONTEND -> "前端";
            case CLIENT -> "客户端";
            default -> "后端";
        };
    }

    public static boolean isBackend(String kind) {
        return BACKEND.equals(normalize(kind));
    }

    public static boolean skipScan(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return false;
        }
        String raw = projectCode.trim().toLowerCase(Locale.ROOT);
        if (SKIP_SCAN.contains(raw)) {
            return true;
        }
        String canon = ApiAppCatalog.canonicalCode(raw).toLowerCase(Locale.ROOT);
        return SKIP_SCAN.contains(canon);
    }

    /** 只有业务后端才扫 Controller、生成接口用例。网关 / 前端 / 客户端都不扫。 */
    public static boolean scansEndpoints(ApiProject p) {
        return p != null && isBackend(p.getKind()) && !skipScan(p.getCode());
    }

    public static boolean scansEndpoints(String kind, String projectCode) {
        return isBackend(kind) && !skipScan(projectCode);
    }
}
