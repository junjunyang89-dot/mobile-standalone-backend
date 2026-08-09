package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <code>{{变量}}</code> 替换。
 * <p>
 * 这组里最要紧的两条是 <b>取不到值要抛</b> 和 <b>运行时压过环境</b>：
 * 前者决定失败信息指不指向真正的原因，后者决定登录态能不能自动传下去。
 */
class VariableResolverTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void 环境变量替换进去() {
        assertEquals("https://a.com/user",
                VariableResolver.resolve("{{base}}/user", null, map("base", "https://a.com")));
    }

    @Test
    void 两侧留空格也认() {
        assertEquals("Bearer abc",
                VariableResolver.resolve("Bearer {{ token }}", null, map("token", "abc")));
    }

    /**
     * 本组核心之一。登录用例抽出来的新 token 必须压过环境里那个写死的旧值，
     * 否则 extract 白做——人配过一次 token 之后，往后每轮都还在用那个过期的。
     */
    @Test
    void 运行时的值压过环境变量() {
        assertEquals("new-token",
                VariableResolver.resolve("{{token}}", map("token", "new-token"), map("token", "old-token")));
    }

    /**
     * 本组核心之二。留着 {{token}} 原样发出去只会换来一个 401，看着像后端的锅，
     * 查半天才发现是变量没配。报错要直接说缺哪个、去哪儿配。
     */
    @Test
    void 取不到值时抛出并指出是哪个变量() {
        VariableResolver.UnresolvedException e = assertThrows(VariableResolver.UnresolvedException.class,
                () -> VariableResolver.resolve("{{base}}/u?t={{token}}", null, map("base", "https://a.com")));

        assertTrue(e.getMessage().contains("token"), e.getMessage());
        assertTrue(!e.getMessage().contains("base"), "已经取到的不该报出来：" + e.getMessage());
        assertTrue(e.getMessage().contains("extract"), "要告诉人可以让登录用例抽出来：" + e.getMessage());
    }

    @Test
    void 缺多个时一次报全() {
        VariableResolver.UnresolvedException e = assertThrows(VariableResolver.UnresolvedException.class,
                () -> VariableResolver.resolve("{{a}}{{b}}", null, null));

        assertTrue(e.getMessage().contains("a") && e.getMessage().contains("b"),
                "一次报全，别让人补一个跑一遍：" + e.getMessage());
    }

    @Test
    void 没有变量的文本原样返回() {
        assertEquals("/api/user", VariableResolver.resolve("/api/user", null, null));
        assertNull(VariableResolver.resolve(null, null, null));
    }

    // ==================== 内置动态变量 ====================

    /** 造数据时手机号/昵称不能重复，写死一个跑第二遍就唯一键冲突 */
    @Test
    void 内置变量每次求值都不同() {
        String a = VariableResolver.resolve("{{$guid}}", null, null);
        String b = VariableResolver.resolve("{{$guid}}", null, null);

        assertNotEquals(a, b);
        assertEquals(36, a.length());
    }

    @Test
    void 时间戳和随机数都是数字() {
        assertTrue(VariableResolver.resolve("{{$timestamp}}", null, null).matches("\\d{13}"));
        assertTrue(VariableResolver.resolve("{{$randomInt}}", null, null).matches("\\d+"));
        assertTrue(VariableResolver.resolve("{{$timestampSec}}", null, null).matches("\\d{10}"));
    }

    /** 自己配的同名变量优先——内置只是兜底，不能反过来盖掉人明确配的 */
    @Test
    void 环境里配了同名则用配的() {
        assertEquals("fixed", VariableResolver.resolve("{{$timestamp}}", null, map("$timestamp", "fixed")));
    }

    // ==================== 嵌套与死循环 ====================

    @Test
    void 变量值里还能再引用变量() {
        assertEquals("Bearer abc",
                VariableResolver.resolve("{{auth}}", null, map("auth", "Bearer {{token}}", "token", "abc")));
    }

    /**
     * {@code a = "{{b}}"}、{@code b = "{{a}}"} 这种配错一次就能把执行线程挂死。
     * 挂死比报错难查得多——界面上只是一直转圈，没有任何线索。
     */
    @Test
    void 自引用报循环引用而不是死循环或静默放行() {
        VariableResolver.UnresolvedException e = assertThrows(VariableResolver.UnresolvedException.class,
                () -> VariableResolver.resolve("{{a}}", null, map("a", "{{b}}", "b", "{{a}}")));

        assertTrue(e.getMessage().contains("循环引用"), e.getMessage());
    }

    // ==================== 宽松模式（只给人看） ====================

    @Test
    void 宽松模式取不到就原样留着() {
        assertEquals("https://a.com/u/{{uid}}",
                VariableResolver.resolveLenient("{{base}}/u/{{uid}}", null, map("base", "https://a.com")));
    }

    @Test
    void 能列出引用了哪些变量() {
        assertEquals(java.util.Set.of("base", "uid"),
                VariableResolver.referenced("{{base}}/u/{{uid}}"));
    }
}
