package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseReviewServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock TableImpactService tableImpactService;
    @InjectMocks CaseReviewService service;

    // ---- 字段引用匹配：全部走纯方法，不碰库 ----

    /** 断言 path 里引用了变更字段 → 命中 */
    @Test
    void 断言引用变更字段命中() {
        String assertions = "[{\"type\":\"jsonPath\",\"path\":\"$.result.token\",\"op\":\"exists\"}]";
        assertThat(service.fieldsReferencedIn(assertions, null, List.of("token")))
                .containsExactly("token");
    }

    /** 提取 extractJson 里引用了变更字段 → 命中 */
    @Test
    void 提取引用变更字段命中() {
        String extract = "{\"uid\":\"$.data.userId\"}";
        assertThat(service.fieldsReferencedIn(null, extract, List.of("userId")))
                .containsExactly("userId");
    }

    /**
     * 按路径分段精确比，不是子串包含。
     * 变更字段 id 不该命中 $.orderId —— 否则全是误报。
     */
    @Test
    void 按路径分段而非子串避免误报() {
        String assertions = "[{\"type\":\"jsonPath\",\"path\":\"$.orderId\"}]";
        assertThat(service.fieldsReferencedIn(assertions, null, List.of("id"))).isEmpty();
        // 但真的取 $.order.id 时，id 段应命中
        String real = "[{\"type\":\"jsonPath\",\"path\":\"$.order.id\"}]";
        assertThat(service.fieldsReferencedIn(real, null, List.of("id"))).containsExactly("id");
    }

    /** 数组下标不当字段：$.list[0].name 里的 0 不是字段 */
    @Test
    void 数组下标不算字段() {
        String assertions = "[{\"type\":\"jsonPath\",\"path\":\"$.list[0].name\"}]";
        assertThat(service.fieldsReferencedIn(assertions, null, List.of("name")))
                .containsExactly("name");
        assertThat(service.fieldsReferencedIn(assertions, null, List.of("0"))).isEmpty();
    }

    @Test
    void 大小写不敏感() {
        String assertions = "[{\"type\":\"jsonPath\",\"path\":\"$.Token\"}]";
        assertThat(service.fieldsReferencedIn(assertions, null, List.of("token")))
                .containsExactly("token");
    }

    @Test
    void 没有断言时不命中() {
        assertThat(service.fieldsReferencedIn(null, null, List.of("token"))).isEmpty();
        assertThat(service.fieldsReferencedIn("", "", List.of("token"))).isEmpty();
    }

    @Test
    void 提示语区分有无影响() {
        assertThat(CaseReviewService.buildHint(0, 1, 0)).contains("没有用例受");
        assertThat(CaseReviewService.buildHint(3, 2, 1)).contains("3 条用例").contains("复查");
    }
}
