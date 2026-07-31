package com.mobile.standalone.service;

import com.mobile.standalone.config.ReleaseDocProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseDocStatusFilterTest {

    @Test
    void onlyResolvedAndTestingAreIncluded() {
        ReleaseDocProperties props = new ReleaseDocProperties();
        props.setIncludeStatuses(List.of("已解决", "TESTING"));
        ReleaseDocService svc = new ReleaseDocService(props);

        assertTrue(svc.isIncludedStatus("已解决"));
        assertTrue(svc.isIncludedStatus("TESTING"));
        assertTrue(svc.isIncludedStatus("testing"));
        assertFalse(svc.isIncludedStatus("已关闭"));
        assertFalse(svc.isIncludedStatus("开放"));
        assertFalse(svc.isIncludedStatus("Open"));
        assertFalse(svc.isIncludedStatus("Closed"));
        assertFalse(svc.isIncludedStatus(""));
        assertFalse(svc.isIncludedStatus(null));
    }
}
