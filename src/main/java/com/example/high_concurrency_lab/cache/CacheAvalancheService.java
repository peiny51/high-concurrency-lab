package com.example.high_concurrency_lab.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CacheAvalancheService {

    private static final int BASE_TTL_SECONDS = 60;
    private static final int MAX_JITTER_SECONDS = 30;

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;
    private final CacheAvalancheOriginGuard originGuard;

    /** Lists fixture IDs and names for the UI; this setup query is outside rebuild admission. */
    public List<Map<String, Object>> getDemoProducts() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Product product : productRepository.findTop10ByNameStartingWithOrderByIdAsc("Avalanche Demo ")) {
            results.add(Map.of("productId", product.getId(), "name", product.getName()));
        }
        return results;
    }

    /** Queries IDs sequentially in request order; earlier cache writes remain if a later item fails. */
    public List<Map<String, Object>> queryProducts(List<Long> productIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long productId : productIds) {
            results.add(queryProduct(productId));
        }
        return results;
    }

    /** Serves cache hits directly and admits misses through the shared per-instance rebuild guard. */
    private Map<String, Object> queryProduct(Long productId) {
        String key = "high-concurrency-lab:avalanche:product:" + productId + ":name";
        Map<String, Object> cached = readCache(productId, key);
        if (cached != null) {
            return cached;
        }
        return originGuard.execute(() -> {
            // Another request may have filled the cache while this request waited.
            Map<String, Object> rechecked = readCache(productId, key);
            if (rechecked != null) {
                return rechecked;
            }
            return loadAndCache(productId, key);
        });
    }

    /** Returns cached data with a positive remaining TTL, or null if no usable expiring entry is observed. */
    private Map<String, Object> readCache(Long productId, String key) {
        String cachedName = redisTemplate.opsForValue().get(key);
        if (cachedName != null) {
            // GET and TTL are separate commands; the key may expire between them.
            Long remainingTtlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            if (remainingTtlMillis != null && remainingTtlMillis > 0) {
                return Map.of("productId", productId, "name", cachedName, "found", true,
                        "source", "redis", "remainingTtlMillis", remainingTtlMillis);
            }
        }
        return null;
    }

    /** Loads MySQL and caches existing names with jittered expiry; missing products are not cached here. */
    private Map<String, Object> loadAndCache(Long productId, String key) {
        originGuard.recordDatabaseLoad();
        Optional<Product> productResult = productRepository.findById(productId);
        if (productResult.isEmpty()) {
            return Map.of("productId", productId, "found", false, "source", "mysql",
                    "message", "Product not found. No cache entry was written.");
        }
        String name = productResult.get().getName();
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Product name is not configured");
        }

        // nextInt excludes its upper bound, so adding one includes all jitter values from 0 to 30.
        // Random TTL reduces synchronized expiration but cannot prevent every expiry collision.
        int jitterSeconds = ThreadLocalRandom.current().nextInt(MAX_JITTER_SECONDS + 1);
        int assignedTtlSeconds = BASE_TTL_SECONDS + jitterSeconds;
        redisTemplate.opsForValue().set(key, name, Duration.ofSeconds(assignedTtlSeconds));
        Long remainingTtlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        if (remainingTtlMillis == null || remainingTtlMillis <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cache expiry could not be confirmed. Try again shortly.");
        }
        return Map.of("productId", productId, "name", name, "found", true,
                "source", "mysql", "assignedTtlSeconds", assignedTtlSeconds,
                "remainingTtlMillis", remainingTtlMillis);
    }
}
