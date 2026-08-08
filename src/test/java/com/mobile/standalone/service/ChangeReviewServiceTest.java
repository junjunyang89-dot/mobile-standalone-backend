package com.mobile.standalone.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeReviewServiceTest {

    @Test
    void categorizeSqlAndUi() {
        assertEquals("SQL", ChangeReviewService.categorize("db/migration/V1__init.sql"));
        assertEquals("UI", ChangeReviewService.categorize("src/views/Home.vue"));
        assertEquals("API", ChangeReviewService.categorize("src/main/java/foo/UserController.java"));
        assertEquals("CONFIG", ChangeReviewService.categorize("src/main/resources/application.yml"));
    }

    @Test
    void moduleOfTakesTwoSegments() {
        assertEquals("src/views", ChangeReviewService.moduleOf("src/views/Home.vue"));
        assertEquals("README.md", ChangeReviewService.moduleOf("README.md"));
    }
}
