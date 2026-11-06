package com.example.high_concurrency_lab.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheBreakdownService {

    private static final Duration NAME_TTL = Duration.ofSeconds(60);
    private static final Duration MISSING_TTL = Duration.ofSeconds(10);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(2);
    private static final long RETRY_DELAY_MILLIS = 40;

    // Compare and delete atomically: an expired holder must never delete a newer owner's lock.
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Reads a name or not-found marker; coordinates cache refill with a per-product Redis lease.
     * Contenders retry within a bounded wait; the lease does not cancel a slow database call.
     */
    public Map<String, Object> getProductName(Long productId) {
        String prefix = "high-concurrency-lab:product:" + productId;
        String nameKey = prefix + ":name";
        String missingKey = prefix + ":missing";
        String lockKey = prefix + ":lock";
        String token = UUID.randomUUID().toString();
        // A monotonic clock bounds retries without depending on wall-clock adjustments.
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        boolean waited = false;

        while (true) {
            Map<String, Object> cached = readCache(productId, nameKey, missingKey, waited);
            if (cached != null) {
                return cached;
            }
            if (System.nanoTime() >= deadline) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cache rebuild is busy. Try again shortly.");
            }

            // Acquire only if absent, setting the owner token and expiry in the same Redis command.
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    // A previous holder may have populated the cache before we acquired the lock.
                    cached = readCache(productId, nameKey, missingKey, waited);
                    if (cached != null) {
                        return cached;
                    }

                    Optional<Product> productResult = productRepository.findById(productId);
                    if (productResult.isEmpty()) {
                        redisTemplate.opsForValue().set(missingKey, "1", MISSING_TTL);
                        return Map.of("productId", productId, "found", false,
                                "source", "mysql", "waited", waited, "message", "Product not found");
                    }
                    String name = productResult.get().getName();
                    if (name == null) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Product name is not configured");
                    }
                    redisTemplate.opsForValue().set(nameKey, name, NAME_TTL);
                    return Map.of("productId", productId, "name", name, "found", true,
                            "source", "mysql", "waited", waited);
                } finally {
                    // Release the lock. If Redis is unavailable, the lock will expire automatically
                    releaseLock(lockKey, token);
                }
            }

            waited = true;
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cache rebuild wait was interrupted", exception);
            }
        }
    }

    /** Returns a cached name or confirmed absence, or null when a database refill is still needed. */
    private Map<String, Object> readCache(Long productId, String nameKey, String missingKey, boolean waited) {
        String name = redisTemplate.opsForValue().get(nameKey);
        if (name != null) {
            return Map.of("productId", productId, "name", name, "found", true,
                    "source", "redis", "waited", waited);
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(missingKey))) {
            return Map.of("productId", productId, "found", false,
                    "source", "redis", "waited", waited, "message", "Product not found");
        }
        return null;
    }

    /** Deletes the lease only when its owner token matches; logs Redis failures and relies on lease expiry. */
    private void releaseLock(String lockKey, String token) {
        try {
            redisTemplate.execute(RELEASE_LOCK, List.of(lockKey), token);
        } catch (DataAccessException exception) {
            // The lease still expires if Redis cannot accept the release command.
            log.warn("Could not release cache rebuild lock {}. It will expire automatically.",
                    lockKey, exception);
        }
    }
}
