package com.mobile.standalone.service.apichange;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 顺着 {@code Controller → Service → (Mapper | RedisConstants)} 把接口连到<b>表</b>和<b>缓存 key</b>。
 * <p>
 * 解决两个具体问题：
 * <ul>
 *   <li><b>「这数据不对，是不是缓存？」</b> —— 测出来跟预期不符时，第一件事是判断到底是逻辑错了
 *       还是缓存没刷。知道这个接口用了哪几个 key，直接去清就行，不用猜。</li>
 *   <li><b>「表加了唯一索引，那我该测哪个接口？」</b> —— 表的测试注意事项要能落到具体接口上，
 *       否则还是得人自己去翻代码。</li>
 * </ul>
 * <p>
 * 只连两跳（接口→Service→依赖），不做全图传递闭包：跳数一多，几乎所有接口都会连上几乎所有表，
 * 那种「什么都相关」的结论等于没有结论。
 */
public final class EndpointDependencyResolver {

    private EndpointDependencyResolver() {
    }

    /** Redis 常量定义：public static final String XXX = "yyy"; */
    private static final Pattern CONST_DEF = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"]*)\"");

    /** 代码里的引用：RedisConstants.XXX */
    private static final Pattern CONST_REF = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*Constants?)\\.([A-Z][A-Z0-9_]*)\\b");

    @Data
    public static class RedisKeyRef {
        /** 常量名，如 USER_SYNOPSIS */
        private String constant;
        /** 常量的字面值，也就是真实 key 前缀 */
        private String value;
        /** 定义它的类，如 RedisConstants */
        private String owner;
        /** 本次这个常量的值是否变了——key 变了等于老缓存全失效，会一次性全量回源 */
        private boolean valueChanged;
        private String oldValue;
        /** 是否与本次变更直接相关（定义它的类或用它的实现本次改了） */
        private boolean related;
    }

    @Data
    public static class Deps {
        /** 这个接口（经由 Service）读写的表；与本次变更相关的排在前面 */
        private List<String> tables = new ArrayList<>();
        /** 这个接口用到的缓存 key */
        private List<RedisKeyRef> redisKeys = new ArrayList<>();
        /** 前多少个是与本次变更直接相关的，后面的是这个接口本来就在用的 */
        private int relatedTableCount;
    }

    /**
     * 这个常量看着像不像缓存 key。
     * <p>
     * 常量类里混着不少哨兵值（{@code REDIS_NOT_HAS_VALUE = "0"} 表示「查过但没有」），
     * 那不是 key，报出来只会让人去 redis 里找一个不存在的东西。
     */
    static boolean looksLikeKey(String value) {
        if (value == null || value.length() < 3) {
            return false;
        }
        return !value.chars().allMatch(Character::isDigit);
    }

    /**
     * @param services   接口用到的 Service 类型名（可能是接口名，实现类是 XxxImpl）
     * @param headSource 按简单类名取待测侧源码
     * @param baseSource 按简单类名取基线侧源码（用来判断常量值有没有变）
     * @param tablesOf   按 Mapper 简单类名取它操作的表
     */
    public static Deps resolve(Set<String> services,
                               Function<String, String> headSource,
                               Function<String, String> baseSource,
                               Function<String, Set<String>> tablesOf) {
        return resolve(services, headSource, baseSource, tablesOf, Set.of(), Set.of());
    }

    /**
     * @param changedTables 本次变更涉及的表
     * @param changedTypes  本次变更的类名——用来判断哪些缓存 key 跟这次改动直接相关
     */
    public static Deps resolve(Set<String> services,
                               Function<String, String> headSource,
                               Function<String, String> baseSource,
                               Function<String, Set<String>> tablesOf,
                               Set<String> changedTables,
                               Set<String> changedTypes) {
        Deps out = new Deps();
        if (services == null || services.isEmpty()) {
            return out;
        }
        Set<String> tables = new LinkedHashSet<>();
        Map<String, RedisKeyRef> keys = new LinkedHashMap<>();

        for (String svc : services) {
            // 注入的是接口，SQL 和缓存都写在实现类里，两个都要看
            for (String candidate : List.of(svc, svc + "Impl")) {
                String src = headSource.apply(candidate);
                if (src == null) {
                    continue;
                }
                for (Map.Entry<String, String> f : JavaApiSourceParser.injectedFields(src).entrySet()) {
                    String type = f.getValue();
                    if (type.endsWith("Mapper") || type.endsWith("Dao")) {
                        tables.addAll(tablesOf.apply(type));
                    }
                }
                collectRedisKeys(src, headSource, baseSource, keys);
            }
        }
        // 与本次变更相关的排前面：一个接口连着十几张表时，人要的是「这次动了哪几张」，
        // 而不是「它历史上碰过哪些」——后者等于没有结论
        List<String> related = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String t : tables) {
            (changedTables.contains(t) ? related : rest).add(t);
        }
        List<String> ordered = new ArrayList<>(related);
        ordered.addAll(rest);
        out.setTables(ordered);
        out.setRelatedTableCount(related.size());

        List<RedisKeyRef> refs = new ArrayList<>(keys.values());
        for (RedisKeyRef r : refs) {
            r.setRelated(r.isValueChanged() || changedTypes.contains(r.getOwner()));
        }
        refs.sort((x, y) -> Boolean.compare(y.isRelated(), x.isRelated()));
        out.setRedisKeys(refs);
        return out;
    }

    private static void collectRedisKeys(String source,
                                         Function<String, String> headSource,
                                         Function<String, String> baseSource,
                                         Map<String, RedisKeyRef> out) {
        Matcher m = CONST_REF.matcher(JavaApiSourceParser.stripComments(source));
        while (m.find()) {
            String owner = m.group(1);
            String name = m.group(2);
            // 只认缓存相关的常量类，不然会把所有业务枚举都当成缓存 key
            if (!owner.toLowerCase(Locale.ROOT).contains("redis")
                    && !owner.toLowerCase(Locale.ROOT).contains("cache")) {
                continue;
            }
            String key = owner + "." + name;
            if (out.containsKey(key)) {
                continue;
            }
            String headConst = headSource.apply(owner);
            String value = constantValue(headConst, name);
            if (value == null || !looksLikeKey(value)) {
                continue;
            }
            RedisKeyRef ref = new RedisKeyRef();
            ref.setConstant(name);
            ref.setValue(value);
            ref.setOwner(owner);
            String old = constantValue(baseSource == null ? null : baseSource.apply(owner), name);
            // key 的字面值变了：老缓存整批失效，第一次访问会全量回源；
            // 反过来，key 没变但返回结构变了，则老缓存里躺着旧结构——那种更阴
            if (old != null && !old.equals(value)) {
                ref.setValueChanged(true);
                ref.setOldValue(old);
            }
            out.put(key, ref);
        }
    }

    static String constantValue(String source, String constName) {
        if (source == null || constName == null) {
            return null;
        }
        Matcher m = CONST_DEF.matcher(source);
        while (m.find()) {
            if (constName.equals(m.group(1))) {
                return m.group(2);
            }
        }
        return null;
    }
}
