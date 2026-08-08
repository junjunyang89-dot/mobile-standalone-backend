package com.mobile.standalone.service.keyword;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流程文本里的内置变量：${date}、${timestamp} 等，在用户参数替换之后展开。
 */
public final class BuiltinVariables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private BuiltinVariables() {
    }

    public static String expand(String text) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        LocalDateTime now = LocalDateTime.now();
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = valueOf(m.group(1), now);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String valueOf(String key, LocalDateTime now) {
        return switch (key) {
            case "date" -> LocalDate.from(now).format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "time" -> LocalTime.from(now).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            case "now", "datetime" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            case "timestamp" -> String.valueOf(System.currentTimeMillis());
            case "yyyyMMdd" -> now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            case "HHmmss" -> now.format(DateTimeFormatter.ofPattern("HHmmss"));
            default -> null;
        };
    }
}
