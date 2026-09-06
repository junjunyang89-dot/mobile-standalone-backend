package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 期望值来自独立写的一份 Python 参考实现，不是拿本实现自己的输出回填的——
 * 那样只能证明它没变过，证明不了它算得对。
 */
class PvpApiSignerTest {

    private static final String KEY = "TEST_APP_KEY";

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 文档里那个 HTTP GET 示例的结构 */
    @Test
    void matchesReferenceImplementationOnDocExample() {
        String s = PvpApiSigner.sign("99999", KEY, "123456", "1582430631",
                params("access_token", "NzY1NjExOTgzMTM0MDUxMDIxMjM0NTY="));

        assertThat(s).isEqualTo("59b0972394b1e3ac780c12b35332fa90e0f0343d");
    }

    /**
     * 抓包那条 PVP TASK 请求的参数结构。
     * <p>
     * appKey 用的是测试值，所以签名对不上线上那条——真实 appKey 填进环境变量之后，
     * 用同样的入参跑一遍，能算出 792a1577… 才说明实现是对的。
     */
    @Test
    void matchesReferenceImplementationOnCapturedRequestShape() {
        String s = PvpApiSigner.sign("30000", KEY, "123456", "1636353975",
                params("category", "task_1",
                        "showHidden", "true",
                        "loginSteamId", "76561198063826868",
                        "access_token", "f6935c1bcb202b68b616fee5d8f700231ce8b9da"));

        assertThat(s).isEqualTo("6f2123ab41b26908cc1b04ff1e015bc64e39d540");
    }

    // ---------- request 串的拼法：签不对基本都错在这一步 ----------

    /** 刻意避开 a/r/t/s：那四个是协议参数，拿它们当业务参数名测不出排序 */
    @Test
    void requestStringIsSortedByWholePair() {
        assertThat(PvpApiSigner.buildRequest(params("cc", "3", "aa", "1", "bb", "2")))
                .isEqualTo("aa=1&bb=2&cc=3");
    }

    /** a/r/t/s 是签名协议自身的参数，不参与被签名的串 */
    @Test
    void reservedParamsAreExcluded() {
        assertThat(PvpApiSigner.buildRequest(
                params("a", "30000", "r", "123456", "t", "1", "s", "x", "k", "v")))
                .isEqualTo("k=v");
    }

    /**
     * 拼进 md5 之前要 URL 解码。文档没写这一条，漏了就整条签不对——
     * 而服务端只会回「签名错误」，看不出差在哪。
     */
    @Test
    void requestStringIsUrlDecoded() {
        assertThat(PvpApiSigner.buildRequest(params("tt", "a%3Db", "uu", "x%26y")))
                .isEqualTo("tt=a=b&uu=x&y");
    }

    /** token 尾部的 = 是解码后的形态，不该再被编码回去 */
    @Test
    void base64TokenKeepsTrailingEquals() {
        assertThat(PvpApiSigner.buildRequest(params("access_token", "NzY1NjEx=")))
                .isEqualTo("access_token=NzY1NjEx=");
    }

    @Test
    void emptyAndNullValuesAreStable() {
        assertThat(PvpApiSigner.buildRequest(params("k", ""))).isEqualTo("k=");
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("k", null);
        assertThat(PvpApiSigner.buildRequest(withNull)).isEqualTo("k=");
        assertThat(PvpApiSigner.buildRequest(null)).isEmpty();
    }

    // ---------- 漏配密钥要当场说清楚 ----------

    /**
     * 没有 appKey 签出来一定是错的，而服务端只会回一句「签名错误」——
     * 到那时候根本看不出是漏配了密钥。
     */
    @Test
    void missingAppKeyFailsWithAnActionableMessage() {
        assertThatThrownBy(() -> PvpApiSigner.sign("30000", "", "1", "2", params("k", "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appKey")
                .hasMessageContaining("环境变量");

        assertThatThrownBy(() -> PvpApiSigner.sign("", KEY, "1", "2", params("k", "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
    }

    /** 同样的入参必须算出同样的签名，否则没法用它比对抓包结果 */
    @Test
    void signingIsDeterministicRegardlessOfInsertionOrder() {
        String a = PvpApiSigner.sign("1", KEY, "r", "t", params("x", "1", "y", "2"));
        String b = PvpApiSigner.sign("1", KEY, "r", "t", params("y", "2", "x", "1"));
        assertThat(a).isEqualTo(b);
    }
}
