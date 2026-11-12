package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

import jakarta.persistence.PersistenceException;

@SpringBootTest
class PurchaseProcessingServiceIntegrationTests {

    @Autowired
    private PurchaseProcessingService service;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PurchaseResultRepository resultRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> requestIds = new ArrayList<>();
    private Product product;
    private String failureConstraint;

    /** Requires the isolated CI database and commits a fresh ten-unit product for each case. */
    @BeforeEach
    void setUp() {
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        product = productRepository.saveAndFlush(new Product("Purchase Processing Test", 10));
    }

    /** Removes only this test's constraint, request outcomes, and generated product, even after failures. */
    @AfterEach
    void tearDown() {
        try {
            if (failureConstraint != null) {
                jdbcTemplate.execute("ALTER TABLE purchase_results DROP CHECK " + failureConstraint);
            }
        } finally {
            try {
                for (UUID requestId : requestIds) {
                    resultRepository.deleteById(requestId);
                }
            } finally {
                if (product != null) {
                    productRepository.deleteById(product.getId());
                }
            }
        }
    }

    /** Verifies successful processing commits both the stock deduction and the success record. */
    @Test
    void commitsStockAndSuccessResultTogether() {
        PurchaseRequest request = requestFor(3);

        assertTrue(service.process(request).isSuccess());

        assertEquals(7, storedStock());
        PurchaseResult stored = resultRepository.findById(request.requestId()).orElseThrow();
        assertTrue(stored.isSuccess());
        assertEquals(product.getId(), stored.getProductId());
        assertEquals(3, stored.getQuantity());
    }

    /** Verifies insufficient stock is a persisted business rejection without any deduction. */
    @Test
    void recordsInsufficientStockWithoutDeducting() {
        PurchaseRequest request = requestFor(11);

        assertFalse(service.process(request).isSuccess());

        assertEquals(10, storedStock());
        assertFalse(resultRepository.findById(request.requestId()).orElseThrow().isSuccess());
    }

    /** Verifies a nonexistent product still produces a completed rejection for that original ID. */
    @Test
    void recordsMissingProductAsBusinessRejection() {
        PurchaseRequest original = requestFor(3);
        PurchaseRequest request = new PurchaseRequest(original.requestId(), Long.MAX_VALUE, 3);

        assertFalse(service.process(request).isSuccess());

        assertEquals(Long.MAX_VALUE,
                resultRepository.findById(request.requestId()).orElseThrow().getProductId());
        assertEquals(10, storedStock());
    }

    /** Verifies sequential delivery of the same intent returns its result without another deduction. */
    @Test
    void reusesCompletedRequestWithoutDeductingAgain() {
        PurchaseRequest request = requestFor(3);

        PurchaseResult first = service.process(request);
        PurchaseResult repeated = service.process(request);

        assertEquals(first.getRequestId(), repeated.getRequestId());
        assertTrue(repeated.isSuccess());
        assertEquals(7, storedStock());
    }

    /** Verifies reuse of an ID for a different quantity is rejected without changing its original result. */
    @Test
    void rejectsConflictingRequestIdReuse() {
        PurchaseRequest request = requestFor(3);
        service.process(request);
        PurchaseRequest conflicting = new PurchaseRequest(request.requestId(), product.getId(), 4);

        assertThrows(IllegalArgumentException.class, () -> service.process(conflicting));

        assertEquals(7, storedStock());
        assertEquals(3, resultRepository.findById(request.requestId()).orElseThrow().getQuantity());
    }

    /** Forces a real MySQL insert failure after deduction and verifies that the whole transaction rolls back. */
    @Test
    void rollsBackStockWhenResultInsertFails() {
        PurchaseRequest request = requestFor(3);
        String constraintName = "test_result_failure_" + UUID.randomUUID().toString().replace("-", "");
        // The generated identifier and numeric ID are test-owned, never user-supplied SQL fragments.
        // Only this product's inserts fail; unrelated result writes are unaffected.
        jdbcTemplate.execute("ALTER TABLE purchase_results ADD CONSTRAINT " + constraintName
                + " CHECK (product_id <> " + product.getId() + ")");
        failureConstraint = constraintName;

        PersistenceException failure = assertThrows(PersistenceException.class, () -> service.process(request));

        assertTrue(hasSqlErrorCode(failure, 3819), "MySQL must reject the insert because of the test check constraint");
        assertEquals(10, storedStock());
        assertFalse(resultRepository.existsById(request.requestId()));
    }

    /** Creates an isolated intent and tracks its ID so teardown can remove any committed result. */
    private PurchaseRequest requestFor(int quantity) {
        UUID requestId = UUID.randomUUID();
        requestIds.add(requestId);
        return new PurchaseRequest(requestId, product.getId(), quantity);
    }

    /** Reads stock directly from MySQL to verify committed state outside the service transaction. */
    private int storedStock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product.getId());
    }

    /** Finds the underlying SQL error so an unrelated persistence failure cannot satisfy the rollback test. */
    private boolean hasSqlErrorCode(Throwable failure, int expectedCode) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && expectedCode == sqlException.getErrorCode()) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
