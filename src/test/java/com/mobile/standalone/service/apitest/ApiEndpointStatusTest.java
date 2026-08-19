package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清单上那一列状态。
 * <p>
 * 它决定人看到这个页面之后会不会去干活，所以错一档的代价不是显示不好看，
 * 是「这批接口已经验过了」这个判断本身是错的。
 */
class ApiEndpointStatusTest {

    @Test
    void 有用例且验了字段才算已覆盖() {
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 3, 2)).isEqualTo(ApiEndpointStatus.COVERED);
    }

    /**
     * 有用例但一条都没验返回字段——这是那 258 条的真实状态。
     * <p>
     * 归进「已覆盖」的话，整份清单会变成一句谎话：屏幕上全绿，实际一个字段都没验过。
     */
    @Test
    void 只验状态码的算断言太弱() {
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 1, 0))
                .isEqualTo(ApiEndpointStatus.WEAK_COVERED);
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 10, 0))
                .isEqualTo(ApiEndpointStatus.WEAK_COVERED);
    }

    /** 弱覆盖要高亮：它比「未覆盖」更危险，未覆盖你知道自己没验 */
    @Test
    void 弱覆盖要高亮而已覆盖不用() {
        assertThat(ApiEndpointStatus.WEAK_COVERED.actionable()).isTrue();
        assertThat(ApiEndpointStatus.COVERED.actionable()).isFalse();
        assertThat(ApiEndpointStatus.UNCOVERED.actionable()).isFalse();
    }

    @Test
    void 没用例就是未覆盖() {
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 0, 0)).isEqualTo(ApiEndpointStatus.UNCOVERED);
    }

    /** 接口有变更时，「断言弱」让位给「要复核」——后者更急 */
    @Test
    void 有变更时优先报复核() {
        assertThat(ApiEndpointStatus.of("ACTIVE", "FIELD_CHANGED", 1, 0))
                .isEqualTo(ApiEndpointStatus.CHANGED_NEEDS_REVIEW);
        assertThat(ApiEndpointStatus.of("ACTIVE", "FIELD_CHANGED", 0, 0))
                .isEqualTo(ApiEndpointStatus.CHANGED_UNCOVERED);
    }

    /** 新增接口即使写了用例也不算已覆盖：它还没被任何一轮回归验证过 */
    @Test
    void 新增接口单独一档() {
        assertThat(ApiEndpointStatus.of("ACTIVE", "NEW", 0, 0))
                .isEqualTo(ApiEndpointStatus.NEW_UNCOVERED);
        assertThat(ApiEndpointStatus.of("ACTIVE", "NEW", 2, 2))
                .isEqualTo(ApiEndpointStatus.CHANGED_NEEDS_REVIEW);
    }

    /** 废弃压过一切：接口都没了，用例强不强不重要 */
    @Test
    void 废弃优先级最高() {
        assertThat(ApiEndpointStatus.of("DEPRECATED", "NEW", 5, 5))
                .isEqualTo(ApiEndpointStatus.DEPRECATED);
    }

    /** 老签名（不传 strongCaseCount）默认按「用例都算强」——别让旧调用方误报成弱覆盖 */
    @Test
    void 三参数重载不误报弱覆盖() {
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 2)).isEqualTo(ApiEndpointStatus.COVERED);
    }

    /**
     * 项目配了业务成功码时，只验 200 的用例在执行时会自动带上 {@code code == 0}——
     * 那是真验，不能再算「没在验」。
     * <p>
     * 这条是实测出来的：只看存库的断言文本，会得出「配了业务码页面也没变化」的结论，
     * 而页面上恰好又建议人去配业务码。照做了却看不到变化，比不给建议更糟。
     */
    @Test
    void 配了业务码就不算没在验() {
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 1, 0, true))
                .isEqualTo(ApiEndpointStatus.COVERED);
        // 没配的话还是「有用例但没在验」
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 1, 0, false))
                .isEqualTo(ApiEndpointStatus.WEAK_COVERED);
    }

    /**
     * 停用的用例什么都不验。
     * <p>
     * 算它覆盖的话，把一条不稳定的用例停掉就能让清单变绿——
     * 而那个接口从此没有任何人在验。
     */
    @Test
    void 用例全停用时不算已覆盖() {
        // 2 条用例、都有 jsonPath，但一条都没启用
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 2, 0, 0, false))
                .isEqualTo(ApiEndpointStatus.WEAK_COVERED);
        // 配了业务码也救不了：没有启用的用例，业务码断言也没机会跑
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 2, 0, 0, true))
                .isEqualTo(ApiEndpointStatus.WEAK_COVERED);
        // 有一条启用且验字段就算覆盖
        assertThat(ApiEndpointStatus.of("ACTIVE", null, 2, 1, 1, false))
                .isEqualTo(ApiEndpointStatus.COVERED);
    }

    /** 每一档都要有能直接给人看的说明——清单上那个 tooltip 就是它 */
    @Test
    void 每个状态都有可读说明() {
        for (ApiEndpointStatus s : ApiEndpointStatus.values()) {
            assertThat(s.label()).as(s.name()).isNotBlank();
            assertThat(s.hint()).as(s.name()).isNotBlank();
        }
        assertThat(ApiEndpointStatus.CHANGED_NEEDS_REVIEW.hint())
                .as("要说清楚危害：断言过期了跑起来还是绿的")
                .contains("绿");
        assertThat(ApiEndpointStatus.WEAK_COVERED.hint())
                .as("要说清楚危害：只验 200，接口返回错误码照样是绿的")
                .contains("200");
    }

    @Test
    void 空值按未变处理() {
        assertThat(ApiEndpointStatus.of(null, null, 0)).isEqualTo(ApiEndpointStatus.UNCOVERED);
        assertThat(ApiEndpointStatus.of(null, "  ", 1)).isEqualTo(ApiEndpointStatus.COVERED);
    }
}
