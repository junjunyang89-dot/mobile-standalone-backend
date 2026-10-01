package com.mobile.standalone.service.apitest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 接口该不该优先做自动化：按「谁在调它」分级。
 * <p>
 * 调用方是扫客户端仓库扫出来的，比拍脑袋靠谱——三端原生都在调的接口坏了就是全量用户可见；
 * 只有后台在调的坏了影响的是运营；哪都搜不到的多半已经下线。
 * 看不出业务轻重（登录、支付这类），所以允许人工改级别，人工的优先。
 */
public enum EndpointLevel {

    CORE("核心"),
    IMPORTANT("重要"),
    NORMAL("一般"),
    UNUSED("无人调用");

    private final String label;

    EndpointLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static EndpointLevel parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** level 为 null＝调用方还没扫过，判断不了 */
    public record Verdict(EndpointLevel level, String reason) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Verdict of(String callerStatus, String callersJson) {
        if (callerStatus == null || callerStatus.isBlank()) {
            return new Verdict(null, "还没扫过调用方");
        }
        if ("NONE".equals(callerStatus)) {
            return new Verdict(UNUSED, "客户端、H5、后台都搜不到调用");
        }
        Set<String> natives = new LinkedHashSet<>();
        Set<String> h5 = new LinkedHashSet<>();
        Set<String> admin = new LinkedHashSet<>();
        Set<String> services = new LinkedHashSet<>();
        for (String repo : usedRepos(callersJson)) {
            String r = repo.toLowerCase(Locale.ROOT);
            if (r.contains("android")) {
                natives.add("安卓");
            } else if (r.contains("ios")) {
                natives.add("iOS");
            } else if (r.contains("harmony")) {
                natives.add("鸿蒙");
            } else if (r.contains("swift")) {
                h5.add(repo);
            } else if (r.contains("admin")) {
                admin.add(repo);
            } else {
                services.add(repo);
            }
        }
        if (natives.size() >= 2) {
            return new Verdict(CORE, String.join("、", natives) + " 都在调");
        }
        if (natives.size() == 1) {
            return new Verdict(IMPORTANT, "只有" + natives.iterator().next() + "在调");
        }
        if (!h5.isEmpty()) {
            return new Verdict(IMPORTANT, "H5 在调（" + String.join("、", h5) + "）");
        }
        if (!services.isEmpty()) {
            return new Verdict(IMPORTANT, "其他服务在调（" + String.join("、", services) + "）");
        }
        if (!admin.isEmpty()) {
            return new Verdict(NORMAL, "只有管理后台在调");
        }
        return new Verdict(NORMAL, "只在接口封装里写了路径，没找到页面在用");
    }

    /** 标了 unused 的是「封装里写了、没人用」，不算调用方 */
    static List<String> usedRepos(String callersJson) {
        List<String> out = new ArrayList<>();
        if (callersJson == null || callersJson.isBlank()) {
            return out;
        }
        try {
            JsonNode arr = JSON.readTree(callersJson);
            if (arr == null || !arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                if (n.path("unused").asBoolean(false)) {
                    continue;
                }
                String repo = n.path("repo").asText("");
                if (!repo.isBlank() && !out.contains(repo)) {
                    out.add(repo);
                }
            }
        } catch (Exception ignore) {
            // 解析不了就当没有调用方
        }
        return out;
    }
}
