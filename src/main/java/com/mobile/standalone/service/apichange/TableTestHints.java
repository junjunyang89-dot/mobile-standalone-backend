package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从建表语句推导「这张表测试要注意什么」。
 * <p>
 * 只报「涉及了 deadlock_user_follow 表」对测试没有帮助——人还是得自己去翻 DDL、
 * 自己想边界。而建表语句里其实已经写明了大部分测试点：唯一索引意味着重复插入要被拦、
 * 可空字段意味着要试空值、bigint unsigned 意味着前端可能丢精度。这些是<b>机器能读出来</b>的。
 * <p>
 * 刻意只报<b>能从 DDL 直接读出来</b>的东西，不猜业务语义：猜错了会让人白测一轮，
 * 而漏掉的那些人本来就会想到。
 */
public final class TableTestHints {

    private TableTestHints() {
    }

    /**
     * @param level 档位：RISK（容易出事，优先测）/ INFO（知道就好）
     * @param title 一句话说清要注意什么
     * @param basis 依据，指到 DDL 里的哪一句——不写依据的建议没法验证，人也不会信
     */
    public record Hint(String level, String title, String basis) {
    }

    private static final Pattern COLUMN = Pattern.compile(
            "(?m)^\\s*`?([a-zA-Z_][\\w]*)`?\\s+([a-zA-Z]+)(\\([^)]*\\))?([^,\\n]*)");
    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "(?i)UNIQUE\\s+KEY\\s+`?[\\w]*`?\\s*\\(([^)]*)\\)");
    private static final Pattern PRIMARY_KEY = Pattern.compile("(?i)PRIMARY\\s+KEY\\s*\\(([^)]*)\\)");
    private static final Pattern AUTO_INC = Pattern.compile("(?i)AUTO_INCREMENT\\s*=\\s*(\\d+)");
    private static final Pattern TABLE_NAME = Pattern.compile(
            "(?i)CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?([\\w]+)`?");

    public static String tableNameOf(String ddl) {
        if (ddl == null) {
            return null;
        }
        Matcher m = TABLE_NAME.matcher(ddl);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    public static List<Hint> derive(String ddl) {
        List<Hint> out = new ArrayList<>();
        if (ddl == null || ddl.isBlank()) {
            return out;
        }
        String body = ddl;

        // ① 唯一索引：重复写入该被拦；并发下还要看会不会绕过
        Matcher uk = UNIQUE_KEY.matcher(body);
        while (uk.find()) {
            String cols = cleanCols(uk.group(1));
            out.add(new Hint("RISK",
                    "重复数据：(" + cols + ") 是唯一的，用同样的值再提交一次必须被拦住（不是报 500，是给出明确提示）"
                            + "；并发连点两次也不该产生两条",
                    "UNIQUE KEY (" + cols + ")"));
        }

        Set<String> nullableBiz = new LinkedHashSet<>();
        Set<String> bigUnsigned = new LinkedHashSet<>();
        List<String> longText = new ArrayList<>();
        boolean autoUpdateTime = false;

        Matcher cm = COLUMN.matcher(body);
        while (cm.find()) {
            String col = cm.group(1);
            String type = cm.group(2).toLowerCase(Locale.ROOT);
            String len = cm.group(3) == null ? "" : cm.group(3);
            String rest = cm.group(4) == null ? "" : cm.group(4);
            String restLower = rest.toLowerCase(Locale.ROOT);
            if (isKeyword(col)) {
                continue;
            }
            boolean nullable = !restLower.contains("not null");
            boolean isTime = type.startsWith("date") || type.startsWith("time");

            // 业务列可空：容易出「传了空也存进去」的问题；主键与时间列不算
            if (nullable && !isTime && !col.equalsIgnoreCase("id")) {
                nullableBiz.add(col);
            }
            if ((type.equals("bigint") || type.equals("int")) && restLower.contains("unsigned")) {
                bigUnsigned.add(col);
            }
            if ((type.equals("varchar") || type.equals("char")) && !len.isEmpty()) {
                longText.add(col + len);
            }
            if (restLower.contains("on update current_timestamp")) {
                autoUpdateTime = true;
            }
        }

        // ② 可空的业务列
        if (!nullableBiz.isEmpty()) {
            out.add(new Hint("RISK",
                    "空值：" + String.join("、", nullableBiz) + " 建表时允许为 NULL。"
                            + "传空 / 不传 / 传 0，看接口是拦住还是照样入库——库不拦就只能靠代码拦，很容易漏",
                    "DEFAULT NULL"));
        }

        // ③ bigint unsigned：前端精度是真踩过的坑
        if (!bigUnsigned.isEmpty()) {
            out.add(new Hint("RISK",
                    "数值精度：" + String.join("、", bigUnsigned) + " 是 unsigned 大整数（如 steamId64）。"
                            + "超过 2^53 时前端用 JS Number 解析会丢精度、末几位变 0 —— "
                            + "务必用真实的大 ID 测一遍，别用 1/2/3 这种小号",
                    "bigint unsigned"));
        }

        // ④ 变长字段的超长
        if (!longText.isEmpty()) {
            out.add(new Hint("INFO",
                    "超长输入：" + String.join("、", longText) + "，按定义长度试边界与超一位",
                    "varchar 长度"));
        }

        // ⑤ 自动更新时间
        if (autoUpdateTime) {
            out.add(new Hint("INFO",
                    "更新时间会自动刷新：任何一次更新都会改 gmt_modified，"
                            + "如果列表按更新时间排序，改一条会让它跳到最前面",
                    "ON UPDATE CURRENT_TIMESTAMP"));
        }

        // ⑥ 自增起点非 0 → 库里已有存量数据
        Matcher ai = AUTO_INC.matcher(body);
        if (ai.find() && !"1".equals(ai.group(1))) {
            out.add(new Hint("INFO",
                    "这张表已有存量数据（自增到 " + ai.group(1) + "）。"
                            + "新逻辑要能处理老数据——老数据里新加的列是空的",
                    "AUTO_INCREMENT=" + ai.group(1)));
        }

        // ⑦ 自关联：两列都指向同一类实体时，「自己关联自己」经常没人拦
        List<String> selfRef = selfReferencePair(body);
        if (selfRef != null) {
            out.add(new Hint("RISK",
                    "自关联：" + selfRef.get(0) + " 与 " + selfRef.get(1)
                            + " 指向同一类对象，试试把两者填成同一个值（比如自己关注自己）——"
                            + "库层面没有约束能拦它",
                    selfRef.get(0) + " / " + selfRef.get(1)));
        }

        if (out.isEmpty()) {
            out.add(new Hint("INFO", "从建表语句里没读出特别的风险点，按常规增删改查覆盖即可", "—"));
        }
        return out;
    }

    /** from_xxx / to_xxx、xxx_a / xxx_b 这类成对列，多半是自关联 */
    private static List<String> selfReferencePair(String ddl) {
        Matcher cm = COLUMN.matcher(ddl);
        List<String> cols = new ArrayList<>();
        while (cm.find()) {
            if (!isKeyword(cm.group(1))) {
                cols.add(cm.group(1).toLowerCase(Locale.ROOT));
            }
        }
        for (String a : cols) {
            if (!a.startsWith("from_")) {
                continue;
            }
            String tail = a.substring(5);
            for (String b : cols) {
                if (b.equals("to_" + tail)) {
                    return List.of(a, b);
                }
            }
        }
        return null;
    }

    /** 索引/约束行不是列定义 */
    private static boolean isKeyword(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("primary") || t.equals("unique") || t.equals("key") || t.equals("index")
                || t.equals("constraint") || t.equals("fulltext") || t.equals("create")
                || t.equals("engine") || t.equals("default") || t.equals("comment");
    }

    private static String cleanCols(String raw) {
        return raw.replace("`", "").replaceAll("\\s+", "").replace(",", ", ");
    }
}
