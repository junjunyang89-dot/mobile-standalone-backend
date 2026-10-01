package com.mobile.standalone.service;

import com.mobile.standalone.entity.ApiTestCase;
import com.mobile.standalone.mapper.ApiTestCaseMapper;
import com.mobile.standalone.mapper.ApiTestEnvMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PUT /cases/{id} 是整条覆盖。停用开关、老页面这类入口不知道 caseKind / sourceBranch，
 * 它们发来的 body 里没有这两个字段——这时必须沿用库里的值，不能按「有没有分支」重新推。
 */
class ApiCaseKindUpdateTest {

    private ApiTestCaseMapper mapper;
    private ApiTestCaseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ApiTestCaseMapper.class);
        service = new ApiTestCaseService(mapper, mock(ApiTestEnvMapper.class),
                mock(ApiProjectService.class), mock(JdbcTemplate.class));
    }

    private void stored(String kind, String branch) {
        ApiTestCase existing = new ApiTestCase();
        existing.setId("c1");
        existing.setCaseKind(kind);
        existing.setSourceBranch(branch);
        when(mapper.findById("c1")).thenReturn(existing);
    }

    private ApiTestCase put(String kind, String branch) {
        ApiTestCase body = new ApiTestCase();
        body.setName("n");
        body.setPath("/x");
        body.setEnabled(false);
        body.setCaseKind(kind);
        body.setSourceBranch(branch);
        service.updateCase("c1", body);
        ArgumentCaptor<ApiTestCase> saved = ArgumentCaptor.forClass(ApiTestCase.class);
        verify(mapper).update(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("没带 caseKind 的更新不能把未分类改成回归")
    void 未分类不被静默改掉() {
        stored("UNCLASSIFIED", null);
        assertThat(put(null, null).getCaseKind())
                .as("否则点一下「停用」，这条历史用例就从待确认里消失了")
                .isEqualTo("UNCLASSIFIED");
    }

    @Test
    @DisplayName("没带 sourceBranch 的更新不能抹掉来源分支")
    void 分支不被抹掉() {
        stored("BRANCH", "mike/20260901/APP-9083");
        ApiTestCase saved = put(null, null);
        assertThat(saved.getSourceBranch()).isEqualTo("mike/20260901/APP-9083");
        assertThat(saved.getCaseKind()).isEqualTo("BRANCH");
    }

    @Test
    @DisplayName("传空串＝明确清空分支；种类照传的走")
    void 空串清空分支() {
        stored("BRANCH", "mike/20260901/APP-9083");
        ApiTestCase saved = put("REGRESSION", "");
        assertThat(saved.getSourceBranch()).isNull();
        assertThat(saved.getCaseKind()).isEqualTo("REGRESSION");
    }

    @Test
    @DisplayName("显式传的种类照样规范化")
    void 显式值规范化() {
        stored("UNCLASSIFIED", null);
        assertThat(put("branch", "a/b").getCaseKind()).isEqualTo("BRANCH");
    }
}
