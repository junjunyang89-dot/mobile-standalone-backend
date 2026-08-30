package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一个契约的两条路径。
 * <p>
 * GLD 直连 {@code /gld/deadlock/friend/list}，DC 编排透传
 * {@code /appdatacenter/deadlock/data/friend/list}——文档只在其中一条下面放参数表，
 * 而人抓到的往往恰恰是没写的那条，结果那条接口一条边界用例都生成不出来。
 */
class SameTailPathTest {

    @Test
    void 取最后两段做标识() {
        assertThat(ApiEndpointService.tailOf("/gld/deadlock/friend/list")).isEqualTo("friend/list");
        assertThat(ApiEndpointService.tailOf("/appdatacenter/deadlock/data/friend/list"))
                .isEqualTo("friend/list");
    }

    @Test
    void 两条网关路径能对上() {
        assertThat(ApiEndpointService.tailOf("/gld/deadlock/user/match/cnt"))
                .isEqualTo(ApiEndpointService.tailOf("/appdatacenter/deadlock/data/user/match/cnt"));
    }

    @Test
    void 只比一段会串味所以要两段() {
        // /a/list 和 /b/list 不是同一个接口。只比最后一段的话它们会被认成一个，
        // 那种串味比不匹配糟得多——文档的约束会套到毫不相干的接口上
        assertThat(ApiEndpointService.tailOf("/order/list"))
                .isNotEqualTo(ApiEndpointService.tailOf("/user/list"));
    }

    @Test
    void 段数不够的不参与匹配() {
        // 只有一段的路径没法可靠地识别，宁可不匹配
        assertThat(ApiEndpointService.tailOf("/list")).isNull();
        assertThat(ApiEndpointService.tailOf("")).isNull();
        assertThat(ApiEndpointService.tailOf(null)).isNull();
    }

    @Test
    void 首尾斜杠不影响() {
        assertThat(ApiEndpointService.tailOf("gld/deadlock/friend/list/")).isEqualTo("friend/list");
    }
}
