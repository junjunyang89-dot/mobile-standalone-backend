package com.mobile.standalone.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SequenceValuesTest {

    @Test
    void 数字区间从大到小倒着走() {
        assertEquals(List.of("2025", "2024", "2023"), SequenceValues.parse("2025-2023"));
    }

    @Test
    void 带年字的区间每项带年() {
        assertEquals(List.of("2025年", "2024年", "2023年"), SequenceValues.parse("2025年-2023年"));
        assertEquals(List.of("2025年", "2024年"), SequenceValues.parse("2025年到2024年"));
    }

    @Test
    void 从小到大顺着走() {
        assertEquals(List.of("1", "2", "3"), SequenceValues.parse("1-3"));
    }

    @Test
    void 逗号列表保持填写顺序() {
        assertEquals(List.of("2025年", "2022年", "没毛病"), SequenceValues.parse("2025年,2022年,没毛病"));
    }

    @Test
    void 空值报错() {
        assertThrows(IllegalArgumentException.class, () -> SequenceValues.parse("  "));
    }
}
