package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PurchaseResultRepositoryIntegrationTests {

    @Autowired
    private PurchaseResultRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Verifies a completed rejection survives a MySQL round trip and cleans up only its generated ID. */
    @Test
    void savesAndReadsCompletedBusinessResultByRequestId() {
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        UUID requestId = UUID.randomUUID();
        // A rejected request may reference a nonexistent product; no product row is required.
        PurchaseResult result = new PurchaseResult(requestId, Long.MAX_VALUE, 3, false);

        try {
            repository.saveAndFlush(result);
            PurchaseResult stored = repository.findById(requestId).orElseThrow();

            assertEquals(requestId, stored.getRequestId());
            assertEquals(Long.MAX_VALUE, stored.getProductId());
            assertEquals(3, stored.getQuantity());
            assertFalse(stored.isSuccess());
            assertNotNull(stored.getProcessedAt());
            // MySQL may round timestamps to its column precision; compare at whole-second precision.
            assertTrue(Math.abs(stored.getProcessedAt().getEpochSecond()
                    - result.getProcessedAt().getEpochSecond()) <= 1);
        } finally {
            repository.deleteById(requestId);
        }

        assertFalse(repository.existsById(requestId));
    }
}
