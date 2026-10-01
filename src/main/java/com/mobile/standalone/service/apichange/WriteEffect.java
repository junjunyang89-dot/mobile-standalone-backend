package com.mobile.standalone.service.apichange;

import java.util.Set;

/**
 * 这个接口会不会改数据。决定它<b>能不能拿到生产上跑</b>。
 *
 * <p><b>为什么必须是三态,不能是两态</b>
 *
 * <p>直觉上这是个是非题:写就是写,不写就是只读。但静态分析一定有看不透的地方——
 * 走消息队列的、走 RPC 调别的服务的、动态拼 SQL 的、依赖注入没认出来的。
 * 这些接口的共同表现是「<b>没扫到写表</b>」。
 *
 * <p>两态设计会把它们全部归进「只读」,然后放到生产上跑。
 * 而它们里面混着真正会写数据的接口——**这正是这个判据要防的事**,
 * 结果反而成了它制造的事故。
 *
 * <p>所以第三态 {@link #UNKNOWN} 不是「懒得判」,是判据的一部分:
 * <b>不知道要当成会写来处理。</b>
 *
 * <p><b>为什么「解析成功但一条 SQL 都没有」也算 UNKNOWN</b>
 *
 * <p>一个接口的依赖链解析出来了、却一条 SQL 都没扫到,说明它多半在做别的事:
 * 调下游服务、发消息、只读 Redis。这些都可能改数据,只是改在别处。
 * 把它算成「只读」和把解析失败算成「只读」是同一个错误。
 */
public enum WriteEffect {

    /** 扫到了写操作(insert / update / delete / replace) */
    WRITES,

    /** 依赖链解析出来了,扫到了读,而且没有任何写 */
    READ_ONLY,

    /** 判不了。<b>按会写处理。</b> */
    UNKNOWN;

    /** 能不能放到生产上跑 */
    public boolean safeForProduction() {
        return this == READ_ONLY;
    }

    /**
     * 判定。
     *
     * @param readTables         扫到的读表
     * @param writeTables        扫到的写表
     * @param dependencyResolved 依赖链有没有解析出来。<b>false 时后两个参数不看</b>——
     *                           没解析出来的话,空集合代表的是「没查」不是「没有」
     */
    public static WriteEffect of(Set<String> readTables, Set<String> writeTables,
                                 boolean dependencyResolved) {
        if (!dependencyResolved) {
            return UNKNOWN;
        }
        if (writeTables != null && !writeTables.isEmpty()) {
            return WRITES;
        }
        if (readTables == null || readTables.isEmpty()) {
            // 解析到了依赖,却一条 SQL 都没有:可能在调下游服务、发消息、只碰 Redis
            return UNKNOWN;
        }
        return READ_ONLY;
    }

    /** 给人看的一句话,直接进报告和接口清单 */
    public String describe() {
        return switch (this) {
            case WRITES -> "会改数据,不要在生产上跑";
            case READ_ONLY -> "只读,可以做生产巡检";
            case UNKNOWN -> "判不出是否会改数据(依赖链没解析出来,或者没扫到 SQL)——"
                    + "按会改数据处理,先别放进生产巡检";
        };
    }

    /** 标签,给界面用 */
    public String label() {
        return switch (this) {
            case WRITES -> "写";
            case READ_ONLY -> "只读";
            case UNKNOWN -> "未知";
        };
    }
}
