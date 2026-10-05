package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一键生成最危险的失败方式不是报错，是<b>安静地生成一批只验 200 的用例并启用它们</b>——
 * 那正是清单要标出来的问题，一键复制两百多遍，而且每条都在报告里显示成绿的。
 */
class ApiCaseSkeletonTest {

    private ApiCaseSkeleton.Input in(String codePath, String codeValue, List<String> fields) {
        return new ApiCaseSkeleton.Input("GET", "/orders/list", "订单列表",
                codePath, codeValue, fields);
    }

    /** 没配业务码、又不知道返回字段：只能出 status=200，那等于没验，必须停用 */
    @Test
    void 凑不出有意义断言时生成为停用() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(in(null, null, List.of()));

        assertThat(d.enabled()).isFalse();
        assertThat(d.name()).contains("待补断言");
        assertThat(d.assertions()).contains("status").doesNotContain("jsonPath");
        // 说明里要给出路：光说「不行」不解决问题
        assertThat(d.note()).contains("抓包");
    }

    /** 只有按项目约定写的业务码：没在这个接口上验过，带上断言但停用 */
    @Test
    void 只有约定业务码时停用并标成未验证() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(in("$.code", "0", List.of()));

        assertThat(d.enabled()).isFalse();
        assertThat(d.assertions()).contains("\"path\":\"$.code\"").contains("\"equals\":0")
                .contains("\"source\":\"convention\"");
        assertThat(d.name()).contains("待验证");
    }

    /** 抓包响应里业务码就是成功值：这条业务码断言算验过 */
    @Test
    void 抓包里业务码对得上时算验证过() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                "GET", "/demo", null, "$.code", "0", List.of(), Map.of("code", 0)));

        assertThat(d.enabled()).isTrue();
        assertThat(d.assertions()).contains("\"equals\":0,\"source\":\"capture\"");
        assertThat(d.assertions()).doesNotContain("convention");
    }

    /** 非数字的业务码要当字符串比，否则 JSON 是坏的 */
    @Test
    void 非数字业务码按字符串比() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(in("$.status", "SUCCESS", List.of()));

        assertThat(d.assertions()).contains("\"equals\":\"SUCCESS\"");
    }

    /** 知道返回字段时出存在性断言——这就够让它不算「仅探活」了 */
    @Test
    void 有返回字段时出存在性断言并启用() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(
                in(null, null, List.of("orderId", "amount", "status")));

        assertThat(d.enabled()).isTrue();
        assertThat(d.assertions())
                .contains("\"path\":\"$.orderId\"")
                .contains("\"exists\":true")
                .contains("\"source\":\"fields\"");
    }

    /**
     * 只断言「字段在」，不猜值。
     * 猜一个填进去，第一次跑就是红的，而红得没有道理最消耗信任——
     * 几次之后人就开始默认红色是自动化的问题。
     */
    @Test
    void 不猜字段的值() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(in(null, null, List.of("orderId")));

        assertThat(d.assertions()).contains("\"exists\":true");
        // 除了业务码那条，不该出现任何 equals
        assertThat(d.assertions().replace("\"equals\":200", "")).doesNotContain("equals");
    }

    /** 字段太多时截断：全断上去用例会变成一份字段清单，改起来比重写还慢 */
    @Test
    void 字段过多时只取前几个() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(in(null, null,
                List.of("a", "b", "c", "d", "e", "f", "g", "h")));

        long n = d.assertions().split("exists", -1).length - 1;
        assertThat(n).isLessThanOrEqualTo(5);
    }

    /** 业务码字段不要再出一条 exists——同一个路径断两遍是噪音 */
    @Test
    void 业务码字段不重复断言() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(
                in("$.code", "0", List.of("$.code", "data")));

        assertThat(d.assertions().split("\\$\\.code", -1).length - 1).isEqualTo(1);
    }

    @Test
    void 状态码断言永远都在() {
        assertThat(ApiCaseSkeleton.build(in(null, null, List.of())).assertions())
                .contains("\"type\":\"status\"");
        assertThat(ApiCaseSkeleton.build(in("$.code", "0", List.of("a"))).assertions())
                .contains("\"type\":\"status\"");
    }

    /** 生成的断言必须是合法 JSON 数组，否则保存时就炸了 */
    @Test
    void 生成的断言是合法JSON() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ApiCaseSkeleton.Draft d : List.of(
                ApiCaseSkeleton.build(in(null, null, List.of())),
                ApiCaseSkeleton.build(in("$.code", "0", List.of("a", "b"))),
                ApiCaseSkeleton.build(in("$.st", "OK", List.of("x"))))) {
            assertThat(mapper.readTree(d.assertions()).isArray()).isTrue();
        }
    }

    /** 空字段名混进来时跳过，别生成 $. 这种取不到值的路径 */
    @Test
    void 空字段名跳过() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(
                in(null, null, java.util.Arrays.asList("", "  ", null, "ok")));

        assertThat(d.assertions()).contains("$.ok").doesNotContain("\"$.\"");
    }

    @Test
    void 抓包样本生成equals断言() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                "GET", "/demo", null, null, null, List.of("code", "msg"),
                Map.of("code", 0, "msg", "ok")));

        assertThat(d.enabled()).isTrue();
        assertThat(d.assertions()).contains("\"path\":\"$.code\"").contains("\"equals\":0");
        assertThat(d.assertions()).contains("\"path\":\"$.msg\"").contains("\"equals\":\"ok\"");
    }

    @Test
    void 抓包host不作为用例名() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                "GET", "/gld/deadlock/friend/list", "gateway-qa.arca42.com",
                null, null, List.of()));

        assertThat(d.name()).isEqualTo("GET friend/list（待补断言）");
        assertThat(d.name()).doesNotContain("gateway");
    }

    @Test
    void 中文接口说明仍可用作用例名() {
        ApiCaseSkeleton.Draft d = ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                "GET", "/orders/list", "订单列表", null, null, List.of("orderId")));

        assertThat(d.name()).isEqualTo("订单列表");
    }
}
