package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路径规约是这套里除结构 diff 外的另一处关键：签名参数每次都变，
 * 不剥掉的话同一个接口在基线里出现无数份，新旧两场永远对不上号。
 */
class ResponseSnapshotServiceTest {

    private static String norm(String path, String query) {
        return ResponseSnapshotService.normalizePath(path, query);
    }

    /** a/r/t/s 每次都不一样，必须剥掉——这是能对上号的前提 */
    @Test
    void 剥掉签名参数() {
        assertThat(norm("/api/userTask/listByCategory",
                "a=30000&r=123456&t=1636353975&s=792a1577&category=task_1&showHidden=true"))
                .isEqualTo("/api/userTask/listByCategory?category=task_1&showHidden=true");
    }

    /** 业务参数按 key 排序，保证抓两次顺序不同也归到同一个 key。
     *  刻意避开 a/r/t/s：那几个是签名参数，会被剥掉 */
    @Test
    void 业务参数按key排序() {
        assertThat(norm("/x", "bb=2&aa=1&cc=3")).isEqualTo("/x?aa=1&bb=2&cc=3");
        assertThat(norm("/x", "cc=3&aa=1&bb=2")).isEqualTo("/x?aa=1&bb=2&cc=3");
    }

    /** 全是签名参数时，规约回纯路径 */
    @Test
    void 只有签名参数则回到纯路径() {
        assertThat(norm("/x", "a=1&r=2&t=3&s=4")).isEqualTo("/x");
    }

    @Test
    void 无query原样返回() {
        assertThat(norm("/x", "")).isEqualTo("/x");
        assertThat(norm("/x", null)).isEqualTo("/x");
    }

    /** _、timestamp、nonce 这类防缓存/防重放参数也算易变，一并剥掉 */
    @Test
    void 剥掉常见的防缓存参数() {
        assertThat(norm("/x", "_=1699&nonce=abc&id=5")).isEqualTo("/x?id=5");
    }

    // ---- 提示语 ----

    @Test
    void 无差异时提示没有回归() {
        assertThat(ResponseSnapshotService.buildHint(0, 0)).contains("没有回归");
    }

    @Test
    void 有漂移和缺失时都说清楚() {
        String h = ResponseSnapshotService.buildHint(2, 1);
        assertThat(h).contains("2 个").contains("结构变了");
        assertThat(h).contains("1 个").contains("没抓到");
    }
}
