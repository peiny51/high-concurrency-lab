package com.example.high_concurrency_lab.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Reads the shared name cache, then a not-found marker, and loads MySQL only on a cache miss.
     */
    public Map<String, Object> getProductName(Long productId) {
        String key = "high-concurrency-lab:product:" + productId + ":name";
        String cachedName = redisTemplate.opsForValue().get(key);
        if (cachedName != null) {
            return Map.of("productId", productId, "name", cachedName, "source", "redis", "found", true);
        }

        String missingKey = "high-concurrency-lab:product:" + productId + ":missing";
        if (Boolean.TRUE.equals(redisTemplate.hasKey(missingKey))) {
            return Map.of("productId", productId, "found", false, "source", "redis",
                    "message", "Product not found");
        }

        Optional<Product> productResult = productRepository.findById(productId);
        // Cache confirmed absence briefly so repeated invalid IDs do not repeatedly reach MySQL.
        if (productResult.isEmpty()) {
            redisTemplate.opsForValue().set(missingKey, "1", Duration.ofSeconds(10));
            return Map.of("productId", productId, "found", false, "source", "mysql",
                    "message", "Product not found");
        }
        String name = productResult.get().getName();
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Product name is not configured");
        }
        redisTemplate.opsForValue().set(key, name, Duration.ofSeconds(60));
        return Map.of("productId", productId, "name", name, "source", "mysql", "found", true);
    }

    /**
     * Saves a validated name, then invalidates all related name caches and the not-found marker.
     * Invalidation failure is reported after the database commit; concurrent stale refill remains possible.
     */
    public Map<String, Object> renameProduct(Long productId, String name) {
        String cleanName = name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must contain 1 to 255 characters");
        }

        int updatedRows = productRepository.updateProductName(productId, cleanName);
        if (updatedRows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }

        // The repository transaction has committed before cache invalidation.
        String key = "high-concurrency-lab:product:" + productId + ":name";
        String missingKey = "high-concurrency-lab:product:" + productId + ":missing";
        try {
            String avalancheNameKey = "high-concurrency-lab:avalanche:product:" + productId + ":name";
            redisTemplate.delete(List.of(key, missingKey, avalancheNameKey));
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Name saved, but cache invalidation failed. Cached queries may be stale.", exception);
        }
        return Map.of("productId", productId, "name", cleanName, "cacheInvalidated", true);
    }
}
