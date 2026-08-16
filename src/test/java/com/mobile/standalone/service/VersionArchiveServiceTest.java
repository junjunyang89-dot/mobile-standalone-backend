package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class VersionArchiveServiceTest {

    @Test
    void parseReleaseDateFromLabel() {
        LocalDate d = VersionArchiveService.parseReleaseDate("8.6 国内客户端");
        assertNotNull(d);
        assertEquals(8, d.getMonthValue());
        assertEquals(6, d.getDayOfMonth());
        assertEquals(LocalDate.now().getYear(), d.getYear());
    }

    @Test
    void inferProductLine() {
        assertEquals("国内客户端", VersionArchiveService.inferProductLine("8.6 国内客户端"));
        assertEquals("海外客户端", VersionArchiveService.inferProductLine("7.20 海外H5"));
    }

    @Test
    void closedStatus() {
        assertTrue(VersionArchiveService.isClosedStatus("已关闭"));
        assertTrue(VersionArchiveService.isClosedStatus("Closed"));
        assertFalse(VersionArchiveService.isClosedStatus("TESTING"));
        assertFalse(VersionArchiveService.isClosedStatus("已解决"));
    }
}
