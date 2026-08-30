package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口文档解析，对着<b>真实的 Confluence 导出文档</b>跑。
 * <p>
 * 用真文档当样本而不是手搓一个：文档格式的坑全在真实数据里——
 * 表头是空的、入参表有 3 列也有 4 列、还混着「DB：无新表」这种散文表。
 * 手搓的样本只会证明代码符合我的想象。
 */
class InterfaceDocParserTest {

    private InterfaceDocParser.Result parse() {
        try (InputStream in = getClass().getResourceAsStream("/doc/app-8999.docx")) {
            assertThat(in).as("测试样本 /doc/app-8999.docx 不在").isNotNull();
            return InterfaceDocParser.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private InterfaceDocParser.Endpoint find(String path) {
        return parse().endpoints().stream()
                .filter(e -> e.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没解析出 " + path));
    }

    @Test
    void 认得出文档里的接口和分支() {
        var r = parse();

        assertThat(r.endpoints()).hasSizeGreaterThanOrEqualTo(8);
        // 分支名是把这份文档和接口对比分析对上的钥匙
        assertThat(r.branches()).contains("fch/20260821/APP-8999");
    }

    @Test
    void 入参带出必填默认值和原文说明() {
        var ep = find("/appdatacenter/deadlock/data/friend/list");

        var steamId = param(ep, "steamId");
        assertThat(steamId.required()).isTrue();
        assertThat(steamId.defaultValue()).isNull();

        var mode = param(ep, "mode");
        assertThat(mode.required()).isFalse();
        assertThat(mode.defaultValue()).isEqualTo("1");
        // 「其它回落 1」这种话归纳成结构化字段一定丢信息，原文必须留住
        assertThat(mode.note()).contains("回落");
    }

    @Test
    void 文档说可空的字段要标出来() {
        // 抓包只看到「这次返回了值」，会生成非空断言；文档说它可空——
        // 照抓包生成的话，正常情况下这条用例也会红
        var ep = find("/appdatacenter/deadlock/data/friend/list");

        assertThat(field(ep, "personaname").nullable()).isTrue();
        assertThat(field(ep, "avatar").nullable()).isTrue();
        assertThat(field(ep, "steamId").nullable()).isFalse();
    }

    @Test
    void 三列的入参表不会把说明当成默认值() {
        // 字段|必填|说明 这种表，第三列是说明。当成默认值的话，
        // 生成的用例会拿「非法 → PARAM_ERROR」当参数值去发请求
        var ep = find("/gld/deadlock/user/match/cnt");

        var steamId = param(ep, "steamId");
        assertThat(steamId.defaultValue()).isNull();
        assertThat(steamId.note()).contains("PARAM_ERROR");
    }

    @Test
    void 字段加类型加必填的表算入参不算出参() {
        // career/stats 的表是 字段|类型|必填，靠列数判断会把它当成出参表
        var ep = find("/gld/deadlock/user/career/stats");

        assertThat(ep.params()).extracting(InterfaceDocParser.Param::name)
                .contains("steamId", "mode", "scope");
        assertThat(ep.fields()).extracting(InterfaceDocParser.Field::name)
                .doesNotContain("steamId", "mode");
    }

    @Test
    void 散文表整张丢掉不硬解析() {
        // 文档里混着「DB：无新表/无迁移」这类说明表。
        // 硬当字段解析出来，生成的用例会去断言一个不存在的字段——
        // 错报比不报糟：人核过两三条假的，真的那条也会一起被忽略
        for (var ep : parse().endpoints()) {
            assertThat(ep.fields()).extracting(InterfaceDocParser.Field::name)
                    .as("%s 里混进了非字段行", ep.path())
                    .doesNotContain("DB");
        }
    }

    @Test
    void 占位符不当成默认值() {
        assertThat(InterfaceDocParser.blankIfPlaceholder("—")).isNull();
        assertThat(InterfaceDocParser.blankIfPlaceholder("无")).isNull();
        assertThat(InterfaceDocParser.blankIfPlaceholder(" ")).isNull();
        assertThat(InterfaceDocParser.blankIfPlaceholder("1")).isEqualTo("1");
    }

    @Test
    void 一整句话不是默认值() {
        assertThat(InterfaceDocParser.looksLikeSentence("非法 → PARAM_ERROR")).isTrue();
        assertThat(InterfaceDocParser.looksLikeSentence("玩家 SteamId")).isTrue();
        assertThat(InterfaceDocParser.looksLikeSentence("1")).isFalse();
        assertThat(InterfaceDocParser.looksLikeSentence("true")).isFalse();
    }

    private InterfaceDocParser.Param param(InterfaceDocParser.Endpoint ep, String name) {
        return ep.params().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("没有入参 " + name));
    }

    private InterfaceDocParser.Field field(InterfaceDocParser.Endpoint ep, String name) {
        return ep.fields().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("没有出参 " + name));
    }

    @Test
    void 坏文件不抛异常只返回空() {
        // 上传了张图片、或者文件传坏了，不该让整个上传接口 500
        var r = InterfaceDocParser.parse(new java.io.ByteArrayInputStream("not a docx".getBytes()));

        assertThat(r.endpoints()).isEmpty();
        assertThat(r.branches()).isEmpty();
    }

    @Test
    void 每个接口至少解析出点东西否则不该出现在结果里() {
        List<InterfaceDocParser.Endpoint> eps = parse().endpoints();

        assertThat(eps).allSatisfy(e ->
                assertThat(e.params().size() + e.fields().size())
                        .as("%s 什么都没解析出来，不该占一行", e.path())
                        .isGreaterThan(0));
    }
}
