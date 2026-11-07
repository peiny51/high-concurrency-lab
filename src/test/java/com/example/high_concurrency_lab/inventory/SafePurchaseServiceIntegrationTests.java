package com.example.high_concurrency_lab.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class SafePurchaseServiceIntegrationTests {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SafePurchaseService purchaseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Verifies a committed purchase deducts three units in MySQL and removes its isolated fixture. */
    @Test
    void purchasesThreeUnitsFromStockOfTen() {
        // Require the disposable CI database before creating or deleting test data.
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        Product product = productRepository.saveAndFlush(new Product("Inventory Integration Test", 10));
        Long productId = product.getId();

        try {
            // No enclosing test transaction: the service must commit its own transaction.
            assertTrue(purchaseService.purchase(productId, 3));

            // Read MySQL directly so an entity already held by JPA cannot hide the stored result.
            Integer remainingStock = jdbcTemplate.queryForObject(
                    "SELECT stock FROM products WHERE id = ?", Integer.class, productId);
            assertEquals(7, remainingStock);
        } finally {
            // Delete only this test's generated ID, including when an assertion fails.
            productRepository.deleteById(productId);
        }

        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE id = ?", Long.class, productId));
    }
}
