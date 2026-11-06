package com.example.high_concurrency_lab.cache;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CacheAvalancheController {

    private final CacheAvalancheService cacheAvalancheService;
    private final CacheAvalancheOriginGuard originGuard;

    /** Exposes this instance's rebuild limits and cumulative counters without querying MySQL. */
    @GetMapping("/api/cache-avalanche/protection")
    public CacheAvalancheOriginGuard.Snapshot getProtection() {
        return originGuard.snapshot();
    }

    /** Maps admission rejection to a coded 503; Retry-After suggests a delay, not a success guarantee. */
    @ExceptionHandler(CacheAvalancheOriginGuard.CapacityExceededException.class)
    public ResponseEntity<ProblemDetail> handleCapacityExceeded() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Cache rebuild capacity is busy. Try again shortly. Earlier batch entries may already be cached.");
        problem.setTitle("Cache rebuild temporarily unavailable");
        problem.setProperty("code", "ORIGIN_CAPACITY_EXCEEDED");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1").body(problem);
    }

    /** Lists the demo fixtures and reports database unavailability as HTTP 503. */
    @GetMapping("/api/cache-avalanche/products")
    public List<Map<String, Object>> getDemoProducts() {
        try {
            return cacheAvalancheService.getDemoProducts();
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Database is unavailable. Try again shortly.", exception);
        }
    }

    /** Validates one to ten unique positive IDs, then queries them in order; partial cache writes can remain. */
    @GetMapping("/api/cache-avalanche")
    public List<Map<String, Object>> queryProducts(@RequestParam List<Long> productIds) {
        if (productIds.isEmpty() || productIds.size() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1 to 10 product IDs");
        }
        for (Long productId : productIds) {
            if (productId == null || productId <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product IDs must be positive");
            }
        }
        if (new HashSet<>(productIds).size() != productIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product IDs must not repeat");
        }
        try {
            return cacheAvalancheService.queryProducts(productIds);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cache or database is unavailable. Some entries may already have been cached.", exception);
        }
    }
}
