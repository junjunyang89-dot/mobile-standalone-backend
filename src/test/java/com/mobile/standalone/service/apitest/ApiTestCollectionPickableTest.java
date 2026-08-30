package com.mobile.standalone.service.apitest;

import com.mobile.standalone.entity.ApiTestCollection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTestCollectionPickableTest {

    @Test
    void pickable_testScope() {
        ApiTestCollection c = new ApiTestCollection();
        c.setProject("app-data-center");
        c.setScope(ApiTestCollection.SCOPE_TEST);
        c.setLifecycle(ApiTestCollection.LIFECYCLE_ACTIVE);
        assertTrue(ApiTestCollectionService.isPickableTestCollection(c));
    }

    @Test
    void pickable_legacyNullScopeWithRequirement() {
        ApiTestCollection c = new ApiTestCollection();
        c.setProject("data-center");
        c.setScope(null);
        c.setRequirementNo("APP-8999");
        assertTrue(ApiTestCollectionService.isPickableTestCollection(c));
    }

    @Test
    void notPickable_live() {
        ApiTestCollection c = new ApiTestCollection();
        c.setProject("app-seckill");
        c.setScope(ApiTestCollection.SCOPE_LIVE);
        assertFalse(ApiTestCollectionService.isPickableTestCollection(c));
    }
}
