package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestCase;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成的用例「还差什么」。
 * <p>
 * 误报「还差」最多让人多看一眼；误报「已就绪」会让一条什么都没验的用例
 * 混进通过率——所以判定一律往严里走。
 */
class CaseReadinessTest {

    private ApiTestCase c(String method, String path, String headers, String body, String assertions) {
        ApiTestCase x = new ApiTestCase();
        x.setMethod(method);
        x.setPath(path);
        x.setHeaders(headers);
        x.setBody(body);
        x.setAssertions(assertions);
        return x;
    }

    @Test
    void 骨架刚生成时四项都差() {
        var r = CaseReadiness.check(
                c("POST", "/api/follow", "{\"Accept\":\"application/json\"}", "", "[{\"type\":\"status\",\"equals\":200}]"),
                Set.of());

        assertThat(r.ready()).isFalse();
        assertThat(r.todos()).hasSize(3);   // GET 才免 body，这里 POST 差 body、登录态、业务断言
    }

    @Test
    void 补齐之后就就绪() {
        var r = CaseReadiness.check(
                c("POST", "/api/follow", "{\"Authorization\":\"{{token}}\"}", "{\"uid\":7}",
                        "[{\"type\":\"status\",\"equals\":200},{\"type\":\"jsonPath\",\"path\":\"$.code\",\"equals\":0}]"),
                Set.of("token"));

        assertThat(r.ready()).isTrue();
        assertThat(r.todos()).isEmpty();
    }

    @Test
    void GET不该被要求补请求体() {
        // 否则清单上一半接口会永远挂着一条补不掉的待办，久了整个提示就没人看了
        assertThat(CaseReadiness.hasMeaningfulBody(c("GET", "/x", null, "", "[]"))).isTrue();
    }

    @Test
    void 空对象是占位不算填了() {
        assertThat(CaseReadiness.hasMeaningfulBody(c("POST", "/x", null, "{}", "[]"))).isFalse();
        assertThat(CaseReadiness.hasMeaningfulBody(c("POST", "/x", null, "{\"a\":1}", "[]"))).isTrue();
    }

    @Test
    void 只断言状态码不算验过() {
        // 接口返回 200 但 data 是空的，这条照样绿——那种绿比红更糟
        assertThat(CaseReadiness.hasBusinessAssertion(
                c("GET", "/x", null, null, "[{\"type\":\"status\",\"equals\":200}]"))).isFalse();
        assertThat(CaseReadiness.hasBusinessAssertion(
                c("GET", "/x", null, null, "[{\"type\":\"db\",\"sql\":\"SELECT 1\"}]"))).isTrue();
    }

    @Test
    void 路径变量没定义要报出来() {
        assertThat(CaseReadiness.missingPathVars("/api/user/{{uid}}/order/{orderId}", Set.of("uid")))
                .containsExactly("orderId");
        assertThat(CaseReadiness.missingPathVars("/api/user/list", Set.of())).isEmpty();
    }

    @Test
    void token写成变量也算带了登录态() {
        // 跑的时候会被替换成真值，这是推荐写法，不能反而判成没带
        assertThat(CaseReadiness.hasAuthReference(
                c("GET", "/x", "{\"Authorization\":\"{{token}}\"}", null, "[]"))).isTrue();
        assertThat(CaseReadiness.hasAuthReference(
                c("GET", "/x", "{\"Authorization\":\"\"}", null, "[]"))).isFalse();
    }
}
