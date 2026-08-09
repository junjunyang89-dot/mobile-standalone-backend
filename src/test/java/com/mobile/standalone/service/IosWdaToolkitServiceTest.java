package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IosWdaToolkitServiceTest {

    @Test
    void parseIdentities_detectsRevokedAndValid() {
        String out = """
                  1) 6E2E8D8A2523DC1145BAEFF2AAB26F62411EB4A0 "Apple Development: trav_yang@sina.cn (9D5C23F4V9)" (CSSMERR_TP_CERT_REVOKED)
                  2) 1D5DDE99BDF4EBE30DDDFBB5B926F74CAC602EC1 "Apple Development: trav_yang@sina.cn (9D5C23F4V9)"
                     2 valid identities found
                """;
        List<IosWdaToolkitService.SigningIdentity> ids = IosWdaToolkitService.parseIdentities(out);
        assertEquals(2, ids.size());
        assertTrue(ids.get(0).revoked());
        assertFalse(ids.get(1).revoked());
        assertEquals(ids.get(0).name(), ids.get(1).name());
    }

    @Test
    void parseIdentities_empty() {
        assertTrue(IosWdaToolkitService.parseIdentities(null).isEmpty());
        assertTrue(IosWdaToolkitService.parseIdentities("").isEmpty());
    }
}
