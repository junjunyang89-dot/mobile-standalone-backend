package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiEndpoint;
import com.mobile.standalone.mapper.ApiEndpointMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaptureCatalogServiceTest {

    private static ApiEndpoint ep(String project) {
        ApiEndpoint e = new ApiEndpoint();
        e.setProject(project);
        return e;
    }

    private static CaptureCatalogService service(ApiEndpointMapper mapper) {
        return new CaptureCatalogService(null, null, mapper, null, null, null, null);
    }

    @Test
    void pathPrefixWinsOverPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        assertEquals("app-global-data",
                service(mapper).resolveProject("app-community", "GET", "/gld/deadlock/list"));
    }

    @Test
    void uniqueOwnerInCatalogWinsOverPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/user/info")).thenReturn(List.of(ep("appuser")));
        assertEquals("appuser", service(mapper).resolveProject("app-community", "GET", "/user/info"));
    }

    @Test
    void ambiguousOrUnknownFallsBackToPickedProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/a")).thenReturn(List.of(ep("p1"), ep("p2")));
        when(mapper.findByMethodPath("GET", "/b")).thenReturn(List.of());
        assertEquals("app-community", service(mapper).resolveProject("app-community", "GET", "/a"));
        assertNull(service(mapper).resolveProject(null, "GET", "/b"));
    }

    @Test
    void inboxOwnerIsIgnoredWhenInferringProject() {
        ApiEndpointMapper mapper = mock(ApiEndpointMapper.class);
        when(mapper.findByMethodPath("GET", "/user/info"))
                .thenReturn(List.of(ep(CaptureCatalogService.INBOX), ep("appuser")));
        assertEquals("appuser", service(mapper).resolveProject(null, "GET", "/user/info"));
    }
}
