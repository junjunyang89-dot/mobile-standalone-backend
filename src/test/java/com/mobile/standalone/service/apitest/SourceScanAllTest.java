package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiProject;
import com.mobile.standalone.service.ApiProjectService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceScanAllTest {

    private static ApiProject project(String code, String kind) {
        ApiProject p = new ApiProject();
        p.setCode(code);
        p.setName(code);
        p.setKind(kind);
        return p;
    }

    @Test
    void 客户端项目不扫_找不到仓库的标跳过() throws Exception {
        ApiProjectService projects = mock(ApiProjectService.class);
        ControllerBackfillService backfill = mock(ControllerBackfillService.class);
        ApiProject backend = project("app-x", null);
        when(projects.list(true)).thenReturn(List.of(
                backend,
                project("android", ApiProject.KIND_CLIENT),
                project("app-gateway", ApiProject.KIND_BACKEND)));
        when(projects.get("app-x")).thenReturn(backend);
        when(backfill.repoOf(any())).thenReturn(null);

        var svc = new SourceScanService(projects, mock(ApiEndpointService.class), backfill,
                mock(GitSnapshotService.class), mock(ApiAuthService.class));
        var job = svc.startScanAll();
        for (int i = 0; i < 100 && !job.done(); i++) {
            Thread.sleep(20);
        }

        assertThat(job.done()).isTrue();
        assertThat(job.items).hasSize(1);
        assertThat(job.items.get(0).project).isEqualTo("app-x");
        assertThat(job.items.get(0).state).isEqualTo("SKIPPED");
        assertThat(svc.latestJob()).isSameAs(job);
    }
}
