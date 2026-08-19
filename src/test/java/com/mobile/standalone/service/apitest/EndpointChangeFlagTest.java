package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把一堆字段差异压成一个词。压错了，清单上给的就是错的指引——
 * 而人是照着清单决定「这条要不要测」的。
 */
class EndpointChangeFlagTest {

    private EndpointChangeFlag.Input in(String type, int params, int fields, int body, int impls) {
        return new EndpointChangeFlag.Input(type, null, params, fields, body, impls);
    }

    @Test
    void 新增接口标NEW() {
        assertThat(EndpointChangeFlag.of(in("ADDED", 0, 0, 0, 0)))
                .isEqualTo(EndpointChangeFlag.NEW);
    }

    /**
     * 入参优先于返回字段。
     * <p>
     * 影响面就是这个顺序：新增必填入参会让老客户端直接 400，
     * 返回体多个字段多数是兼容的。两者都变时，清单要显示更严重的那个。
     */
    @Test
    void 入参变化优先于返回字段() {
        assertThat(EndpointChangeFlag.of(in("MODIFIED", 1, 5, 0, 0)))
                .isEqualTo(EndpointChangeFlag.PARAM_CHANGED);
    }

    @Test
    void 只有返回字段变时标FIELD_CHANGED() {
        assertThat(EndpointChangeFlag.of(in("MODIFIED", 0, 2, 0, 0)))
                .isEqualTo(EndpointChangeFlag.FIELD_CHANGED);
        // 请求体字段也算 FIELD_CHANGED
        assertThat(EndpointChangeFlag.of(in("MODIFIED", 0, 0, 3, 0)))
                .isEqualTo(EndpointChangeFlag.FIELD_CHANGED);
    }

    @Test
    void 签名没变只有实现改了标IMPL_CHANGED() {
        assertThat(EndpointChangeFlag.of(in("MODIFIED", 0, 0, 0, 2)))
                .isEqualTo(EndpointChangeFlag.IMPL_CHANGED);
    }

    /**
     * 判成 MODIFIED 但说不出改了什么时，仍然要标。
     * <p>
     * 返回 null 的话这条会混进「本次没变」里，人就不看了——
     * 而分析器判它变了就是变了，只是原因没归到类。
     */
    @Test
    void 说不清原因的变更也要标出来() {
        assertThat(EndpointChangeFlag.of(in("MODIFIED", 0, 0, 0, 0)))
                .isEqualTo(EndpointChangeFlag.IMPL_CHANGED);
    }

    @Test
    void 删除的接口不标变更而是走废弃() {
        assertThat(EndpointChangeFlag.isRemoval("REMOVED")).isTrue();
        assertThat(EndpointChangeFlag.isRemoval("ADDED")).isFalse();
        assertThat(EndpointChangeFlag.isRemoval(null)).isFalse();
    }

    @Test
    void 空输入不炸() {
        assertThat(EndpointChangeFlag.of(null)).isNull();
        assertThat(EndpointChangeFlag.note(null, null)).isNull();
    }

    @Test
    void 说明要写清改了几处() {
        String note = EndpointChangeFlag.note(in("MODIFIED", 1, 2, 0, 0), null);
        assertThat(note).contains("入参改了 1 处").contains("返回字段改了 2 处");
    }

    /** BREAKING 要单独点出来：这一档是「老客户端会直接坏」，不是「多了个字段」 */
    @Test
    void 破坏性变更要说清后果() {
        String note = EndpointChangeFlag.note(
                new EndpointChangeFlag.Input("MODIFIED", "BREAKING", 1, 0, 0, 0), "BREAKING");
        assertThat(note).contains("破坏性变更").contains("必须测");
    }

    @Test
    void 新增接口的说明单独一句() {
        assertThat(EndpointChangeFlag.note(in("ADDED", 0, 0, 0, 0), null)).contains("新增");
    }
}
