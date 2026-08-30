package com.mobile.standalone.service.apitest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiTestCollectionNamingTest {

    @Test
    void testCollectionName_byRequirement() {
        assertEquals("APP-8999-接口", ApiTestCollectionService.buildTestCollectionName("APP-8999", null));
    }

    @Test
    void testCollectionName_byBranch() {
        assertEquals("backend-0816 · 分支对比",
                ApiTestCollectionService.buildTestCollectionName(null, "backend-0816"));
    }
}
