package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从代码里能<b>确证</b>的安全与字段设计问题。
 * <p>
 * 刻意只报三类高置信度信号，宁可漏也不错报：这份报告的价值全在可信度上，
 * 一旦掺进猜测，人核过两三条假的就不看了，真的那条也一起废掉。
 * <p>
 * <b>没做鉴权检查</b>：扫过这几个仓库，{@code @PreAuthorize} 之类的注解<b>零命中</b>——
 * 他们的鉴权不在注解上（多半在网关或拦截器里）。那就报不出「这个接口没鉴权」，
 * 硬报等于把每个接口都标成风险。
 */
public final class ApiRiskScanner {

    private ApiRiskScanner() {
    }

    /**
     * @param kind  SECURITY / DESIGN
     * @param level RISK 优先看 / INFO 知道就好
     */
    public record Finding(String kind, String level, String target, String title, String basis) {
    }

    // ---------- 安全 ----------

    /** 入参里带身份标识 = 水平越权的典型面：把别人的 ID 填进去就能看/改别人的数据 */
    private static final Set<String> IDENTITY_PARAMS = Set.of(
            "userid", "uid", "steamid", "steamid64", "memberid", "accountid", "customerid",
            "targetuserid", "targetsteamid", "targetsteamid64", "fromsteamid", "tosteamid");

    /** 返回体里出现这些名字，八成不该原样吐给前端 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwd", "pwd", "emailpassword", "token", "accesstoken", "refreshtoken",
            "secret", "apikey", "privatekey", "idcard", "idcardno", "realname", "mobile",
            "mobilephone", "phone", "phonenumber", "email", "bankcard", "salt");

    /**
     * MyBatis 里 {@code ${}} 是字符串直接拼接，会被注入；{@code #{}} 才是预编译占位。
     * <p>
     * 但不能见 {@code ${}} 就报：logback 配置里的 {@code ${LOG_PATH}}、
     * MyBatis 逆向生成器的 {@code ${criterion.condition}} 都是样板，
     * 全报出来是 83 个文件的噪音，真问题反而看不见。
     */
    private static final Pattern SQL_CONCAT = Pattern.compile("\\$\\{\\s*([a-zA-Z_][\\w.]*)\\s*}");

    private static final Set<String> SQL_CONCAT_WHITELIST = Set.of(
            "criterion.condition", "criterion.value", "criteria.condition",
            "log_path", "pid", "basedir", "global.encoding", "orderbyclause", "orderByClause");

    public static List<Finding> scanSecurity(ApiEndpoint endpoint, List<FieldTreeBuilder.FieldNode> responseFields) {
        List<Finding> out = new ArrayList<>();
        if (endpoint == null) {
            return out;
        }
        String where = endpoint.httpMethod() + " " + endpoint.path();

        Set<String> identity = new LinkedHashSet<>();
        for (ApiParam p : endpoint.params()) {
            if (IDENTITY_PARAMS.contains(norm(p.name()))) {
                identity.add(p.name());
            }
        }
        if (!identity.isEmpty()) {
            out.add(new Finding("SECURITY", "RISK", where,
                    "越权：入参里直接带了身份标识 " + String.join("、", identity)
                            + "。把它换成别人的 ID 再调一次——如果能拿到/改掉别人的数据，就是水平越权。"
                            + "正常应该从登录态取，而不是让调用方传",
                    "入参 " + String.join("、", identity)));
        }

        Set<String> sensitive = new LinkedHashSet<>();
        for (FieldTreeBuilder.FieldNode f : responseFields == null ? List.<FieldTreeBuilder.FieldNode>of() : responseFields) {
            if (SENSITIVE_FIELDS.contains(norm(f.getName()))) {
                sensitive.add(f.getPath());
            }
        }
        if (!sensitive.isEmpty()) {
            out.add(new Finding("SECURITY", "RISK", where,
                    "敏感字段外泄：返回体里有 " + String.join("、", sensitive)
                            + "。确认是不是真要返回给端上；要返回也该脱敏（手机号打码、token 不回传）",
                    "返回字段 " + String.join("、", sensitive)));
        }
        return out;
    }

    /** 扫 mapper XML 里的字符串拼接 */
    public static List<Finding> scanSqlInjection(String file, String xml) {
        List<Finding> out = new ArrayList<>();
        if (xml == null || !xml.contains("${")) {
            return out;
        }
        Set<String> hits = new LinkedHashSet<>();
        Matcher m = SQL_CONCAT.matcher(xml);
        while (m.find()) {
            String expr = m.group(1);
            if (SQL_CONCAT_WHITELIST.contains(expr.toLowerCase(Locale.ROOT))) {
                continue;
            }
            hits.add("${" + expr + "}");
        }
        if (!hits.isEmpty()) {
            out.add(new Finding("SECURITY", "RISK", file,
                    "SQL 拼接：" + String.join("、", hits)
                            + " 用的是 ${}，值会被直接拼进 SQL。如果这个值来自接口入参，"
                            + "传 ' or 1=1 -- 之类就能注入。预编译占位应该用 #{}",
                    "mapper XML 里的 ${}"));
        }
        return out;
    }

    // ---------- 字段设计 ----------

    private static final Set<String> MONEY_HINTS = Set.of(
            "amount", "price", "money", "fee", "cost", "balance", "total", "pay", "income");

    /**
     * 返回体字段设计上的坑。
     * <p>
     * 只挑三类<b>会真的让端上出问题</b>的，不做代码风格评判——
     * 「字段名不够语义化」这种建议对测试没有任何可执行价值。
     */
    public static List<Finding> scanFieldDesign(String where, List<FieldTreeBuilder.FieldNode> fields) {
        List<Finding> out = new ArrayList<>();
        if (fields == null || fields.isEmpty()) {
            return out;
        }
        Set<String> bigInts = new LinkedHashSet<>();
        Set<String> floatMoney = new LinkedHashSet<>();
        Set<String> timeTypes = new LinkedHashSet<>();
        Set<String> timeFields = new LinkedHashSet<>();

        for (FieldTreeBuilder.FieldNode f : fields) {
            String type = f.getType() == null ? "" : f.getType();
            String name = norm(f.getName());

            if (type.equals("Long") || type.equals("long") || type.equals("BigInteger")) {
                bigInts.add(f.getPath());
            }
            if ((type.equals("Double") || type.equals("double") || type.equals("Float")
                    || type.equals("float")) && MONEY_HINTS.stream().anyMatch(name::contains)) {
                floatMoney.add(f.getPath() + "(" + type + ")");
            }
            if (name.contains("time") || name.contains("date") || name.endsWith("at")) {
                timeFields.add(f.getPath());
                timeTypes.add(baseType(type));
            }
        }

        if (!bigInts.isEmpty()) {
            out.add(new Finding("DESIGN", "RISK", where,
                    "大整数精度：" + join(bigInts, 6)
                            + " 是 Long。超过 2^53（约 9007199254740992）时前端用 JS Number 解析会丢精度、"
                            + "末几位变 0。拿真实的大 ID 测一遍，别用小号；理想做法是后端转成字符串返回",
                    "Long 类型字段"));
        }
        if (!floatMoney.isEmpty()) {
            out.add(new Finding("DESIGN", "RISK", where,
                    "金额用了浮点：" + String.join("、", floatMoney)
                            + "。浮点做加减会出 0.1+0.2=0.30000000000000004 这类误差，"
                            + "对账场景必错。试试带小数的金额看展示对不对",
                    "Double/Float + 金额语义"));
        }
        if (timeTypes.size() > 1 && !timeFields.isEmpty()) {
            out.add(new Finding("DESIGN", "INFO", where,
                    "时间字段类型不统一（同一返回体里出现了 " + String.join(" / ", timeTypes)
                            + "）。前端得按字段分别处理，容易漏一个导致显示成 1970 或 NaN。"
                            + "涉及字段：" + join(timeFields, 6),
                    "时间字段类型混用"));
        }
        return out;
    }

    private static String baseType(String type) {
        String t = type == null ? "" : type;
        int lt = t.indexOf('<');
        return lt > 0 ? t.substring(0, lt) : t;
    }

    private static String join(Set<String> items, int max) {
        List<String> list = new ArrayList<>(items);
        if (list.size() <= max) {
            return String.join("、", list);
        }
        return String.join("、", list.subList(0, max)) + " 等 " + list.size() + " 个";
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("_", "");
    }
}
