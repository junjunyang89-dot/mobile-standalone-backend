package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档说可空的字段，不生成断言。
 * <p>
 * 抓包这次看到 personaname 有值，会生成 equals 断言；而文档写明「查不到可空」。
 * 照抓包断言的话，换个数据这条用例就红——红的原因是断言写错了，不是接口坏了。
 * 这种假失败最伤：修几次之后，人就不再相信这套用例了。
 */
class NullableFieldAssertionTest {

    private ApiCaseSkeleton.Draft build(Set<String> nullable) {
        return ApiCaseSkeleton.build(new ApiCaseSkeleton.Input(
                "GET", "/appdatacenter/deadlock/data/friend/list", "好友列表",
                "$.code", "0",
                List.of("steamId", "personaname", "avatar"),
                Map.of("personaname", "yangjun", "steamId", "103561140"),
                nullable));
    }

    @Test
    void 可空字段不进断言() {
        var d = build(Set.of("personaname", "avatar"));

        assertThat(d.assertions()).contains("$.steamId");
        assertThat(d.assertions()).doesNotContain("personaname");
        assertThat(d.assertions()).doesNotContain("avatar");
    }

    @Test
    void 没有可空信息时照旧全断() {
        // 没导文档就退回原来的行为，不能因为加了这个特性把已有的用例变弱
        var d = build(Set.of());

        assertThat(d.assertions()).contains("personaname");
        assertThat(d.assertions()).contains("$.steamId");
    }

    @Test
    void 业务码断言不受影响() {
        var d = build(Set.of("personaname", "avatar", "steamId"));

        assertThat(d.assertions()).contains("$.code");
        assertThat(d.assertions()).contains("\"type\":\"status\"");
    }
}
