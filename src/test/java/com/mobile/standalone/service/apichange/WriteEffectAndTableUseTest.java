package com.mobile.standalone.service.apichange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读写表拆分 + 三态判定的单测。
 *
 * <p>这组测试守的是一句话:<b>这个判据的错误代价是在生产上写数据</b>。
 * 所以所有的边界都往「保守」那边倒——判不准时宁可说 UNKNOWN,
 * 也不能说 READ_ONLY。
 */
class WriteEffectAndTableUseTest {

    // ================= 读写拆分 =================

    @Test
    @DisplayName("SELECT ... FROM / JOIN 算读")
    void 查询算读() {
        var u = JavaApiSourceParser.parseTableUse(
                "SELECT a.* FROM user_info a JOIN user_score b ON a.id = b.uid");
        assertThat(u.read()).containsExactlyInAnyOrder("user_info", "user_score");
        assertThat(u.write()).isEmpty();
        assertThat(u.writesAnything()).isFalse();
    }

    @Test
    @DisplayName("INSERT INTO / UPDATE 算写")
    void 增改算写() {
        assertThat(JavaApiSourceParser.parseTableUse("INSERT INTO order_main (id) VALUES (1)").write())
                .containsExactly("order_main");
        assertThat(JavaApiSourceParser.parseTableUse("UPDATE user_info SET name = 'x' WHERE id = 1").write())
                .containsExactly("user_info");
        assertThat(JavaApiSourceParser.parseTableUse("REPLACE INTO user_tag VALUES (1,2)").write())
                .containsExactly("user_tag");
    }

    /** 这条是整个改动里最要命的一个边界 */
    @Test
    @DisplayName("DELETE FROM 必须算写 —— 它里面有个 from")
    void 删除不能被当成读() {
        var u = JavaApiSourceParser.parseTableUse("DELETE FROM user_follow WHERE uid = 1");
        assertThat(u.write())
                .as("正则分支左优先，from 排在 delete\\\\s+from 前面的话，"
                        + "一条删除语句会被判成只读，然后被放到生产上跑")
                .containsExactly("user_follow");
        assertThat(u.read()).isEmpty();
        assertThat(u.writesAnything()).isTrue();
    }

    @Test
    @DisplayName("delete from 的大小写和多空格都要认")
    void 删除的写法变体() {
        for (String sql : new String[]{
                "delete from user_follow where id=1",
                "DELETE  FROM  user_follow",
                "Delete\nFrom user_follow"}) {
            assertThat(JavaApiSourceParser.parseTableUse(sql).write())
                    .as("这种写法没被认出来: %s", sql)
                    .containsExactly("user_follow");
        }
    }

    @Test
    @DisplayName("一条语句里同时读和写,两边都要记")
    void 又读又写() {
        var u = JavaApiSourceParser.parseTableUse(
                "INSERT INTO stat_daily (uid, n) SELECT uid, COUNT(*) FROM user_log GROUP BY uid");
        assertThat(u.write()).contains("stat_daily");
        assertThat(u.read()).contains("user_log");
        assertThat(u.writesAnything()).isTrue();
    }

    @Test
    @DisplayName("UPDATE ... WHERE id IN (SELECT ...) —— 子查询是读,主表是写")
    void 子查询() {
        var u = JavaApiSourceParser.parseTableUse(
                "UPDATE user_info SET vip=1 WHERE id IN (SELECT uid FROM vip_order)");
        assertThat(u.write()).containsExactly("user_info");
        assertThat(u.read()).containsExactly("vip_order");
    }

    @Test
    @DisplayName("ON DUPLICATE KEY UPDATE 后面跟的是列,不是表")
    void upsert不产生假写表() {
        var u = JavaApiSourceParser.parseTableUse(
                "INSERT INTO user_tag (uid, tag) VALUES (1,2) ON DUPLICATE KEY UPDATE tag_count = tag_count + 1");
        assertThat(u.write())
                .as("不排掉的话每条 upsert 都会凭空多出一张写表")
                .containsExactly("user_tag");
    }

    @Test
    @DisplayName("空输入不炸")
    void 空输入() {
        assertThat(JavaApiSourceParser.parseTableUse(null).sawNothing()).isTrue();
        assertThat(JavaApiSourceParser.parseTableUse("").sawNothing()).isTrue();
        assertThat(JavaApiSourceParser.parseTableUse("   ").sawNothing()).isTrue();
    }

    @Test
    @DisplayName("原来的 parseTables 行为不变 —— 别人还在用它")
    void 旧接口不受影响() {
        assertThat(JavaApiSourceParser.parseTables(
                "SELECT * FROM user_info; DELETE FROM user_log"))
                .containsExactlyInAnyOrder("user_info", "user_log");
    }

    // ================= 三态判定 =================

    @Test
    @DisplayName("扫到写表 → WRITES")
    void 有写表就是写() {
        var e = WriteEffect.of(Set.of("a_tbl"), Set.of("b_tbl"), true);
        assertThat(e).isEqualTo(WriteEffect.WRITES);
        assertThat(e.safeForProduction()).isFalse();
    }

    @Test
    @DisplayName("解析出来了、有读、没有写 → READ_ONLY")
    void 只读() {
        var e = WriteEffect.of(Set.of("user_info"), Set.of(), true);
        assertThat(e).isEqualTo(WriteEffect.READ_ONLY);
        assertThat(e.safeForProduction()).isTrue();
    }

    @Test
    @DisplayName("依赖链没解析出来 → UNKNOWN,而不是 READ_ONLY")
    void 没解析出来不能当只读() {
        var e = WriteEffect.of(Set.of(), Set.of(), false);
        assertThat(e)
                .as("Lombok 注入认不出、走 MQ、走 RPC 的接口都长这样——"
                        + "当成只读就等于把它们放到生产上跑")
                .isEqualTo(WriteEffect.UNKNOWN);
        assertThat(e.safeForProduction()).isFalse();
    }

    @Test
    @DisplayName("解析出来了但一条 SQL 都没有 → 也是 UNKNOWN")
    void 没扫到SQL也是未知() {
        var e = WriteEffect.of(Set.of(), Set.of(), true);
        assertThat(e)
                .as("依赖解析到了却没有 SQL，说明它在调下游服务 / 发消息 / 只碰 Redis，"
                        + "那些同样可能改数据，只是改在别处")
                .isEqualTo(WriteEffect.UNKNOWN);
    }

    @Test
    @DisplayName("没解析出来时,即使传了表也按 UNKNOWN —— 空集代表「没查」不是「没有」")
    void 没解析出来时不看表() {
        assertThat(WriteEffect.of(Set.of("x_tbl"), Set.of(), false))
                .isEqualTo(WriteEffect.UNKNOWN);
    }

    @Test
    @DisplayName("null 集合不炸,并且倒向保守")
    void null输入保守处理() {
        assertThat(WriteEffect.of(null, null, true)).isEqualTo(WriteEffect.UNKNOWN);
        assertThat(WriteEffect.of(null, Set.of("t_write"), true)).isEqualTo(WriteEffect.WRITES);
    }

    @Test
    @DisplayName("只有 READ_ONLY 能上生产 —— 这是唯一的白名单")
    void 生产白名单只有一个() {
        assertThat(WriteEffect.READ_ONLY.safeForProduction()).isTrue();
        assertThat(WriteEffect.WRITES.safeForProduction()).isFalse();
        assertThat(WriteEffect.UNKNOWN.safeForProduction()).isFalse();
    }

    @Test
    @DisplayName("UNKNOWN 的文案要说清「按会改数据处理」")
    void 未知的文案要说清楚() {
        assertThat(WriteEffect.UNKNOWN.describe())
                .as("只说「未知」的话，人会以为是「大概没事」")
                .contains("按会改数据处理");
    }
}
