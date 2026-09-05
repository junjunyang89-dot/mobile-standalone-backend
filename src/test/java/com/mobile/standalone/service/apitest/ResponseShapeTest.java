package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构指纹与 diff。盯的核心是：<b>值变了不该报，结构变了必须报</b>——
 * 这条界线划错，快照要么像素级全红没法用，要么漏掉真正的回归。
 */
class ResponseShapeTest {

    // ---------- 只比结构，不比值 ----------

    /** 时间戳、token 这类值每次都变，结构一样就不该报差异，否则快照全红没法用 */
    @Test
    void 值变结构不变不算差异() {
        String a = "{\"code\":0,\"ts\":1636353975,\"token\":\"aaa\"}";
        String b = "{\"code\":0,\"ts\":1725000000,\"token\":\"zzz\"}";
        assertThat(ResponseShape.diff(a, b)).isEmpty();
    }

    /** 字段顺序不同不算差异 */
    @Test
    void 字段顺序不影响结构() {
        assertThat(ResponseShape.of("{\"a\":1,\"b\":2}"))
                .isEqualTo(ResponseShape.of("{\"b\":2,\"a\":1}"));
    }

    // ---------- 结构变了必须抓到 ----------

    /** 后端少返一个字段——回归最典型的一类 */
    @Test
    void 少了字段要报REMOVED并指到路径() {
        String base = "{\"result\":{\"name\":\"x\",\"count\":3}}";
        String now = "{\"result\":{\"name\":\"x\"}}";

        assertThat(ResponseShape.diff(base, now)).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo("REMOVED");
            assertThat(d.path()).isEqualTo("$.result.count");
        });
    }

    @Test
    void 新增字段要报ADDED() {
        var diffs = ResponseShape.diff("{\"a\":1}", "{\"a\":1,\"b\":2}");
        assertThat(diffs).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo("ADDED");
            assertThat(d.path()).isEqualTo("$.b");
        });
    }

    /** 字段从对象变成 null / 从数字变成字符串——静默的类型漂移 */
    @Test
    void 类型变了要报TYPE_CHANGED() {
        var diffs = ResponseShape.diff("{\"user\":{\"id\":1}}", "{\"user\":null}");
        assertThat(diffs).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("TYPE_CHANGED");
            assertThat(d.path()).isEqualTo("$.user");
        });
    }

    /**
     * 奖品从 8 个变 7 个——这是活动页快照最想抓的那一个，
     * 而它恰好是「值都对、就是少了一项」，逐字段比值反而看不出来。
     */
    @Test
    void 数组长度变了要报ARRAY_SIZE() {
        String base = "{\"prizes\":[{\"n\":\"a\"},{\"n\":\"b\"},{\"n\":\"c\"}]}";
        String now = "{\"prizes\":[{\"n\":\"a\"},{\"n\":\"b\"}]}";

        assertThat(ResponseShape.diff(base, now)).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("ARRAY_SIZE");
            assertThat(d.path()).isEqualTo("$.prizes");
            assertThat(d.baseline()).isEqualTo("3");
            assertThat(d.current()).isEqualTo("2");
        });
    }

    /** 数组里某个元素的结构变了，也要能定位到是第几个 */
    @Test
    void 数组元素结构变化定位到下标() {
        String base = "{\"list\":[{\"id\":1,\"tag\":\"x\"},{\"id\":2,\"tag\":\"y\"}]}";
        String now = "{\"list\":[{\"id\":1,\"tag\":\"x\"},{\"id\":2}]}";

        assertThat(ResponseShape.diff(base, now)).anySatisfy(d ->
                assertThat(d.path()).isEqualTo("$.list[1].tag"));
    }

    // ---------- 非 JSON 的兜底 ----------

    /**
     * 接口本该返 JSON，这次返了 HTML 错误页——这是最该抓到的回归之一，
     * 不能因为解析失败就静默放过。
     */
    @Test
    void 从JSON变成非JSON要报出来() {
        var diffs = ResponseShape.diff("{\"code\":0}", "<html><body>502 Bad Gateway</body></html>");
        assertThat(diffs).isNotEmpty();
        assertThat(diffs.get(0).kind()).isEqualTo("TYPE_CHANGED");
    }

    @Test
    void 空响应有稳定指纹() {
        assertThat(ResponseShape.of("")).isEqualTo("<empty>");
        assertThat(ResponseShape.of(null)).isEqualTo("<empty>");
        assertThat(ResponseShape.diff("", "")).isEmpty();
    }

    /** 顶层是数组也要能比 */
    @Test
    void 顶层数组() {
        assertThat(ResponseShape.diff("[1,2,3]", "[1,2]")).anySatisfy(d ->
                assertThat(d.kind()).isEqualTo("ARRAY_SIZE"));
    }

    /**
     * 深层嵌套里的字段增减也要逐层走到。
     * 真实响应常是 {code, message, result:{...}} 包好几层。
     */
    @Test
    void 深层嵌套字段增减() {
        String base = "{\"result\":{\"tasks\":[{\"rewards\":[{\"name\":\"a\",\"qty\":1}]}]}}";
        String now = "{\"result\":{\"tasks\":[{\"rewards\":[{\"name\":\"a\"}]}]}}";

        assertThat(ResponseShape.diff(base, now)).anySatisfy(d -> {
            assertThat(d.kind()).isEqualTo("REMOVED");
            assertThat(d.path()).isEqualTo("$.result.tasks[0].rewards[0].qty");
        });
    }
}
