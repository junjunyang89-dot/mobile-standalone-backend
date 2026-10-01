package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiEndpointRetireTest {

    @Test
    void retiredWinsOverEveryComputedStatus() {
        ApiEndpoint e = new ApiEndpoint();
        e.setStatus("ACTIVE");
        e.setChangeFlag("NEW");
        e.setCaseCount(0);
        e.setRetiredAt(LocalDateTime.now());
        var svc = new ApiEndpointService(mock(ApiEndpointMapper.class), null);
        assertThat(svc.statusOf(e)).isEqualTo(ApiEndpointStatus.RETIRED);

        e.setRetiredAt(null);
        assertThat(svc.statusOf(e)).isEqualTo(ApiEndpointStatus.NEW_UNCOVERED);
    }

    @Test
    void retireRecordsOperatorAndUnretireClearsBoth() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.updateRetired(any(), any(), any())).thenReturn(1);
        when(mapper.updateRetired(any(), isNull(), isNull())).thenReturn(1);
        var svc = new ApiEndpointService(mapper, null);

        assertThat(svc.setRetired(List.of("a", " ", "b"), true, "杨俊俊")).isEqualTo(2);
        verify(mapper).updateRetired(eq("a"), any(LocalDateTime.class), eq("杨俊俊"));

        assertThat(svc.setRetired(List.of("a"), false, "杨俊俊")).isEqualTo(1);
        verify(mapper).updateRetired(eq("a"), isNull(), isNull());
    }
}
