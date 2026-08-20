package com.mobile.standalone.service;

import com.mobile.standalone.entity.DebugApiTest;
import com.mobile.standalone.mapper.DebugApiTestMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DebugApiTestChainTest {

    @Mock
    private DebugApiTestMapper debugApiTestMapper;

    @InjectMocks
    private DebugApiTestService debugApiTestService;

    private static DebugApiTest activeRow() {
        DebugApiTest row = new DebugApiTest();
        row.setId(5L);
        row.setName("样例-登录");
        row.setStatus("ACTIVE");
        row.setScore(80);
        return row;
    }

    @Test
    void snapshot_returnsSnapshotIdAndNameMatch() {
        when(debugApiTestMapper.findById(5L)).thenReturn(activeRow());

        Map<String, Object> vo = debugApiTestService.snapshot(5L, "样例-登录");

        assertEquals("snap-5", vo.get("snapshotId"));
        assertEquals(true, vo.get("nameMatched"));
        assertEquals(80, vo.get("score"));
        assertEquals(true, vo.get("enabled"));
    }

    @Test
    void setScore_updatesAndReturnsNewScore() {
        DebugApiTest row = activeRow();
        when(debugApiTestMapper.findById(5L)).thenReturn(row, row);

        Map<String, Object> vo = debugApiTestService.setScore(5L, 85);

        assertEquals(80, vo.get("previousScore"));
        assertEquals(85, vo.get("newScore"));
        verify(debugApiTestMapper).update(any(DebugApiTest.class));
    }

    @Test
    void chainEcho_verifiesChainedRefs() {
        DebugApiTest row = activeRow();
        row.setScore(85);
        when(debugApiTestMapper.findById(5L)).thenReturn(row);

        DebugApiTestService.ChainEchoInput in = new DebugApiTestService.ChainEchoInput();
        in.setRefId(5L);
        in.setRefName("样例-登录");
        in.setRefSnapshotId("snap-5");
        in.setRefScore(85);
        in.setRefEnabled(true);

        Map<String, Object> vo = debugApiTestService.chainEcho(5L, "snap-5", in);

        assertEquals(true, vo.get("chainVerified"));
        assertEquals("chain-5-snap-5", vo.get("chainToken"));
    }

    @Test
    void chainReceipt_passesWhenQueryMatchesRow() {
        DebugApiTest row = activeRow();
        row.setScore(85);
        when(debugApiTestMapper.findById(5L)).thenReturn(row);

        Map<String, Object> vo = debugApiTestService.chainReceipt(5L, "snap-5", 85, "样例-登录");

        assertEquals(true, vo.get("allPassed"));
        assertEquals("rcpt-5", vo.get("receiptId"));
        assertEquals(5, vo.get("layersChecked"));
    }
}
