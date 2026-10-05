package com.mobile.standalone.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PacketCaptureTokenQueryTest {

    @Test
    void tokenIsReadFromQueryWhenGatewayPassesItInUrl() {
        assertEquals("1e143eaf0ed6ec1c", PacketCaptureController.tokenFromQuery("a=1&token=1e143eaf0ed6ec1c"));
        assertEquals("x y", PacketCaptureController.tokenFromQuery("accessToken=x%20y"));
    }

    @Test
    void blankOrAbsentTokenIsNull() {
        assertNull(PacketCaptureController.tokenFromQuery("token=&a=1"));
        assertNull(PacketCaptureController.tokenFromQuery("tokenType=1"));
        assertNull(PacketCaptureController.tokenFromQuery(null));
    }
}
