package com.mobile.standalone.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫出流程里<b>写死的环境相关数据</b>。
 * <p>
 * 「流程共用、数据不共用」是跨环境跑同一条流程的最大障碍，而障碍的具体形态是：
 * 手机号、验证码、赛事 ID 直接敲在了步骤参数里。测试环境那个手机号在线上根本不存在，
 * 于是同一条流程换个环境跑就挂在登录第一步。
 * <p>
 * 这里只做一件事：<b>把它们找出来并指出改成哪个变量</b>。
 * 不自动替换——参数里那串数字到底是手机号还是订单号，只有人知道；
 * 猜错了会把一条本来能跑的流程改坏，而且改坏的方式很隐蔽（跑得通但验的东西不对）。
 */
public final class FlowDataScanner {

    private FlowDataScanner() {
    }

    @Data
    public static class Hit {
        /** 步骤序号，如 3 或 3.2 */
        private String stepNo;
        /** 参数名，如 text */
        private String paramName;
        /** 写死的那个值 */
        private String value;
        /** 猜它是什么：PHONE / CODE / ID / URL / UNKNOWN */
        private String kind;
        /** 建议替换成哪个变量 */
        private String suggestVariable;
        /** 一句话说清为什么要改 */
        private String reason;
    }

    /** 已经是变量的不用再报 */
    private static final Pattern ALREADY_VAR = Pattern.compile("\\$\\{[^}]+}|\\{\\{[^}]+}}");

    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    /** 8 位以上纯数字：赛事 ID、新闻 ID、用户 ID 都长这样 */
    private static final Pattern LONG_ID = Pattern.compile("\\b\\d{8,}\\b");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /**
     * 只扫这些参数。
     * <p>
     * 不扫全部参数是有意的：{@code timeoutMs}、{@code retryTimes} 里的数字天然就该是字面量，
     * 全扫会把它们一起报出来。误报几条之后这份清单就没人看了——
     * 而它的全部价值就在于「报出来的都值得改」。
     */
    private static final List<String> SCANNED_PARAMS = List.of(
            "text", "value", "keyword", "phone", "code", "url", "expected", "query");

    public static List<Hit> scan(String stepNo, String paramName, String rawValue) {
        List<Hit> out = new ArrayList<>();
        if (rawValue == null || rawValue.isBlank() || paramName == null) {
            return out;
        }
        if (!SCANNED_PARAMS.contains(paramName)) {
            return out;
        }
        // 整个值已经是变量引用就跳过——那正是我们希望的样子
        if (ALREADY_VAR.matcher(rawValue).find()) {
            return out;
        }

        Matcher phone = PHONE.matcher(rawValue);
        while (phone.find()) {
            out.add(hit(stepNo, paramName, phone.group(), "PHONE", "phone",
                    "手机号在两个环境是不同账号，写死之后换环境跑必然登录失败"));
        }
        if (out.isEmpty()) {
            Matcher url = URL.matcher(rawValue);
            while (url.find()) {
                out.add(hit(stepNo, paramName, url.group(), "URL", "baseUrl",
                        "地址带环境信息，写死会让流程永远只能跑一边"));
            }
        }
        if (out.isEmpty()) {
            Matcher id = LONG_ID.matcher(rawValue);
            while (id.find()) {
                out.add(hit(stepNo, paramName, id.group(), "ID", null,
                        "长数字多半是赛事/新闻/用户 ID，两个环境的数据集不同，这个 ID 在另一边可能不存在"));
            }
        }
        return out;
    }

    private static Hit hit(String stepNo, String param, String value, String kind,
                           String suggest, String reason) {
        Hit h = new Hit();
        h.setStepNo(stepNo);
        h.setParamName(param);
        h.setValue(value);
        h.setKind(kind);
        h.setSuggestVariable(suggest);
        h.setReason(reason);
        return h;
    }

    /**
     * 「点具体某一条数据」这类问题，变量解决不了。
     * <p>
     * 「浏览这条赛事」「评论这条新闻」——那条数据在另一个环境压根不存在，
     * 换个 ID 也没用。这类只能改流程写法：<b>按位置选，不按内容选</b>。
     * 「点列表第 1 条」两个环境都成立，「点标题为《XXX》的那条」只在一边成立。
     * <p>
     * 判断依据：定位方式是按文案匹配的，就提示改写。
     */
    public static String rewriteAdviceFor(String locatorType, String locatorValue) {
        if (locatorType == null) {
            return null;
        }
        String t = locatorType.trim().toUpperCase();
        boolean byText = "TEXT".equals(t)
                || ("XPATH".equals(t) && locatorValue != null
                        && (locatorValue.contains("@text=") || locatorValue.contains("@label=")
                                || locatorValue.contains("contains(")));
        if (!byText) {
            return null;
        }
        return "这一步按文案定位。如果那段文案是某条具体的新闻/赛事，两个环境的数据集不同，"
                + "换环境跑会找不到——改成按位置选（列表第 1 条）就两边都成立。"
                + "确实要验某条特定数据时，把它的 ID 配成环境变量，两边各配一份";
    }
}
