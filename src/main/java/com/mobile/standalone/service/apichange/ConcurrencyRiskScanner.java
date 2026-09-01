package com.mobile.standalone.service.apichange;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 哪些接口需要并发测试，以及具体测什么。
 * <p>
 * AI 写的代码在单请求逻辑上很稳，但几乎不会主动处理竞态——「先查再插」「读出来加一再存回去」
 * 这类写法看着完全正常，单线程跑一万遍都对。所以并发面不能靠 review 发现，得从
 * <b>表结构和依赖</b>算出来。
 * <p>
 * 沿用 {@link ApiRiskScanner} 的取舍：<b>宁可漏也不错报</b>。并发问题的复现成本很高，
 * 报一条假的，人花半天验完发现没事，下次就不看了。所以这里只认几个能从 DDL 和依赖里
 * <b>确证</b>的信号，猜测性的一律不报。
 * <p>
 * 每条都带「怎么验证」。只报风险不说怎么测，等于制造焦虑——并发问题恰恰是最不知道
 * 从何下手的那一类。
 */
public final class ConcurrencyRiskScanner {

    private ConcurrencyRiskScanner() {
    }

    /**
     * @param level     RISK 该测 / INFO 知道就好
     * @param kind      DUPLICATE_SUBMIT 重复提交 / LOST_UPDATE 丢更新 / CACHE_RACE 缓存竞态
     * @param howToTest 怎么验证。并发问题最难的是不知道怎么复现，这一栏比风险描述本身更有用
     * @param basis     依据，指到具体的表和列，便于人自己判断这条准不准
     */
    public record Risk(String httpMethod, String path, String handler,
                       String level, String kind, String title,
                       String howToTest, String basis) {
    }

    /** 只有写操作才有并发正确性问题。GET 的并发是性能话题，不在这里 */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Pattern UNIQUE_KEY = Pattern.compile(
            "(?i)UNIQUE\\s+KEY\\s+`?[\\w]*`?\\s*\\(([^)]*)\\)");

    /**
     * 计数/金额类列名。这些列的典型写法是「读出来 +1 再写回去」，
     * 两个请求同时读到同一个值，就丢了一次更新。
     */
    private static final Pattern COUNTER_COLUMN = Pattern.compile(
            "(?i)^\\s*`?(\\w*(?:count|cnt|num|total|stock|qty|quantity|balance|amount|score|times|remain)\\w*)`?\\s+"
                    + "(int|bigint|smallint|mediumint|decimal|numeric|float|double)");

    /**
     * 扫一个接口。
     *
     * @param endpoint  接口本身
     * @param tables    这个接口经由 Service 读写的表（{@link EndpointDependencyResolver.Deps#getTables()}）
     * @param ddlByTable 表名（小写）→ 建表语句；取不到的表直接跳过，不猜
     * @param redisKeys 这个接口用到的缓存 key
     */
    public static List<Risk> scan(ApiEndpoint endpoint,
                                  List<String> tables,
                                  Map<String, String> ddlByTable,
                                  List<String> redisKeys) {
        List<Risk> out = new ArrayList<>();
        if (endpoint == null || !isWrite(endpoint.httpMethod())) {
            return out;
        }
        String m = endpoint.httpMethod();
        String p = endpoint.path();
        String h = endpoint.handler();

        if (tables != null) {
            for (String t : tables) {
                String ddl = ddlByTable == null ? null : ddlByTable.get(lower(t));
                if (ddl == null || ddl.isBlank()) {
                    // 没有建表语句就没有依据。跳过而不是按表名猜——
                    // 猜出来的并发风险验起来最费时间，还最容易是假的
                    continue;
                }
                out.addAll(uniqueKeyRisks(m, p, h, t, ddl));
                out.addAll(counterRisks(m, p, h, t, ddl));
            }
        }

        if (redisKeys != null && !redisKeys.isEmpty()) {
            Set<String> keys = new LinkedHashSet<>(redisKeys);
            out.add(new Risk(m, p, h, "INFO", "CACHE_RACE",
                    "写接口同时在操作缓存：" + String.join("、", keys)
                            + "。并发写时，缓存和库可能被更新成不同的顺序。",
                    "两个请求并发打这个接口，各带不同的值。跑完对一次：库里的值和缓存里的值是不是同一个。"
                            + "不一致就是「后写库的先写缓存」，之后所有人读到的都是旧值。",
                    "缓存 key: " + String.join(", ", keys)));
        }
        return out;
    }

    /**
     * 唯一索引 + 写接口 = 重复提交面。
     * <p>
     * 数据库最终会拦住重复，但拦的方式很要紧：如果代码是「先查再插」，两个请求可能都查到
     * 「不存在」然后都去插，一条成功一条抛唯一键异常，前端收到的是 500 而不是
     * 「你已经提交过了」。这是并发下最常见、也最容易被单线程测试漏掉的一类。
     */
    private static List<Risk> uniqueKeyRisks(String m, String p, String h, String table, String ddl) {
        List<Risk> out = new ArrayList<>();
        Matcher uk = UNIQUE_KEY.matcher(ddl);
        while (uk.find()) {
            String cols = uk.group(1).replace("`", "").trim();
            out.add(new Risk(m, p, h, "RISK", "DUPLICATE_SUBMIT",
                    "重复提交：" + table + " 上 (" + cols + ") 是唯一的。"
                            + "并发下若是「先查不存在再插入」，两个请求会都查到不存在。",
                    "同样的入参，10 个线程同时打这个接口。期望：1 条成功，其余返回明确的业务提示"
                            + "（如「已提交过」）。若出现 500 或唯一键异常透传到前端，就是没处理；"
                            + "若插进去两条，说明唯一索引也没兜住。",
                    table + " UNIQUE KEY (" + cols + ")"));
        }
        return out;
    }

    /**
     * 计数/金额类列 + 写接口 = 丢更新面。
     * <p>
     * 「读出来 +1 再写回去」是 AI 写这类逻辑的默认姿势，单线程完全正确。
     * 并发下两个请求读到同一个初值，各自加一写回，结果只加了一次。
     */
    private static List<Risk> counterRisks(String m, String p, String h, String table, String ddl) {
        List<Risk> out = new ArrayList<>();
        Set<String> hit = new LinkedHashSet<>();
        for (String line : ddl.split("\\r?\\n")) {
            Matcher c = COUNTER_COLUMN.matcher(line);
            if (c.find()) {
                hit.add(c.group(1));
            }
        }
        if (hit.isEmpty()) {
            return out;
        }
        out.add(new Risk(m, p, h, "RISK", "LOST_UPDATE",
                "丢更新：" + table + " 上有计数/金额类列（" + String.join("、", hit) + "）。"
                        + "若代码是「读出来算好再写回去」，并发时两个请求会基于同一个初值各写一次。",
                "先记下当前值，然后 20 个线程并发各加 1，跑完再读。期望恰好 +20；"
                        + "少了就是丢更新。修法通常是把它改成 SET x = x + 1 的原子写，或加行锁。",
                table + " 列: " + String.join(", ", hit)));
        return out;
    }

    private static boolean isWrite(String httpMethod) {
        return httpMethod != null && WRITE_METHODS.contains(httpMethod.toUpperCase(Locale.ROOT));
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
