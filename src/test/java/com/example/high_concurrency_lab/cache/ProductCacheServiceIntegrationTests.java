package com.example.high_concurrency_lab.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

@SpringBootTest
class ProductCacheServiceIntegrationTests {

    @Autowired
    private ProductCacheService cacheService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Verifies a MySQL cache miss fills real Redis with a TTL and the next read hits that cache. */
    @Test
    void fillsNameCacheFromMySqlAndServesTheNextReadFromRedis() {
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        Product product = productRepository.saveAndFlush(new Product("Cache Integration Keyboard", 10));
        Long productId = product.getId();
        String nameKey = "high-concurrency-lab:product:" + productId + ":name";
        String missingKey = "high-concurrency-lab:product:" + productId + ":missing";

        try {
            // Remove only this generated product's keys to establish a genuine cache miss.
            redisTemplate.delete(List.of(nameKey, missingKey));
            assertNull(redisTemplate.opsForValue().get(nameKey));
            assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(missingKey)));

            Map<String, Object> firstRead = cacheService.getProductName(productId);
            assertEquals("mysql", firstRead.get("source"));
            assertEquals(productId, firstRead.get("productId"));
            assertEquals(true, firstRead.get("found"));
            assertEquals(product.getName(), firstRead.get("name"));

            // Inspect the actual Redis value and expiry, rather than just the service response.
            assertEquals(product.getName(), redisTemplate.opsForValue().get(nameKey));
            Long ttlMillis = redisTemplate.getExpire(nameKey, TimeUnit.MILLISECONDS);
            assertTrue(ttlMillis != null && ttlMillis > 0 && ttlMillis <= 60_000,
                    "The real cache entry must expire within sixty seconds");

            Map<String, Object> secondRead = cacheService.getProductName(productId);
            assertEquals("redis", secondRead.get("source"));
            assertEquals(productId, secondRead.get("productId"));
            assertEquals(true, secondRead.get("found"));
            assertEquals(product.getName(), secondRead.get("name"));
        } finally {
            // Still remove the database fixture if Redis cleanup fails; never clear unrelated data.
            try {
                redisTemplate.delete(List.of(nameKey, missingKey));
            } finally {
                productRepository.deleteById(productId);
            }
        }

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(nameKey)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(missingKey)));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM products WHERE id = ?", Long.class, productId));
    }
}
