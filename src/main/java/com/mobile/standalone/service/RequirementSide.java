package com.mobile.standalone.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 这条用例落在哪一端。
 * <p>
 * 层级是按<b>「出问题找谁」</b>分的，不是按技术栈分的：
 * iOS 和安卓是两拨人，H5 是第三拨，后端还要细到具体服务。
 * 分不到人的标记，在甩锅时没有用——「这是客户端的问题」两边都能推。
 * <p>
 * 和用例上已有的 {@code platform}（{@code ANDROID} / {@code IOS}）是两回事：
 * 那个是<b>设备平台</b>，用来做「iOS 用例不能关联安卓流程」的校验。
 * 这里的 H5、后端、管理后台都不是设备平台。
 */
public final class RequirementSide {

    private RequirementSide() {
    }

    public static final String H5 = "H5";
    public static final String CLIENT_ANDROID = "CLIENT_ANDROID";
    public static final String CLIENT_IOS = "CLIENT_IOS";
    public static final String CLIENT_HARMONY = "CLIENT_HARMONY";
    public static final String SERVER = "SERVER";
    public static final String ADMIN = "ADMIN";

    /**
     * {@code {code, 显示名, 说明}}。
     * <p>
     * 显示名直接写成完整层级（「APP · 客户端 · 安卓」），不做成级联选择：
     * 级联要点三次才能选中一项，而这里绝大多数时候就是选一两个固定值。
     */
    private static final List<String[]> DEFS = List.of(
            new String[]{H5, "APP · H5", "APP 内嵌的 H5 页面、PWA"},
            new String[]{CLIENT_ANDROID, "APP · 客户端 · 安卓", "安卓原生改动"},
            new String[]{CLIENT_IOS, "APP · 客户端 · iOS", "iOS 原生改动"},
            new String[]{CLIENT_HARMONY, "APP · 客户端 · 鸿蒙", "纯血鸿蒙原生改动"},
            new String[]{SERVER, "APP · 后端", "服务端接口、数据、任务；具体哪个服务在右边填"},
            new String[]{ADMIN, "管理后台", "运营配置后台、内部系统"});

    public static List<String[]> all() {
        return DEFS;
    }

    public static String labelOf(String code) {
        for (String[] d : DEFS) {
            if (d[0].equals(code)) {
                return d[1];
            }
        }
        return code;
    }

    /**
     * 存库是逗号分隔的字符串。
     * <p>
     * 不用 JSON：这一列要能 {@code FIND_IN_SET} 精确筛，
     * 而 JSON 列在这件事上只是更麻烦，换不来什么。
     */
    public static String join(List<String> sides) {
        Set<String> ok = new LinkedHashSet<>();
        for (String[] d : DEFS) {
            if (sides != null && sides.stream().anyMatch(s -> d[0].equalsIgnoreCase(nz(s)))) {
                ok.add(d[0]);
            }
        }
        return ok.isEmpty() ? null : String.join(",", ok);
    }

    /** 认不出的值直接丢掉——脏值传到前端会变成一个点不动的空标签 */
    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String v = nz(part).toUpperCase(Locale.ROOT);
            for (String[] d : DEFS) {
                if (d[0].equals(v) && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 选了「后端」时，「具体哪个服务」才有意义 */
    public static boolean hasServer(String raw) {
        return split(raw).contains(SERVER);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
