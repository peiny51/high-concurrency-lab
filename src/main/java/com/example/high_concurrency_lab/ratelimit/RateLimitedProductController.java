package com.example.high_concurrency_lab.ratelimit;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.high_concurrency_lab.cache.ProductCacheService;

import lombok.RequiredArgsConstructor;

/** Exposes a separate rate-limited lookup; existing scenario endpoints retain their original behavior. */
@RestController
@RequiredArgsConstructor
public class RateLimitedProductController {

    private final ProductQueryRateLimiter rateLimiter;
    private final ProductCacheService productCacheService;

    /**
     * Validates the ID before admission and reuses the ordinary cache-aside query only when allowed.
     * Returns 429 for quota exhaustion, 404 for a confirmed missing product, or 503 for dependency failures.
     */
    @GetMapping("/api/rate-limit/product")
    public ResponseEntity<Object> getProductName(@RequestParam Long productId) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID must be positive");
        }
        try {
            long retryAfterMillis = rateLimiter.tryAcquire();
            if (retryAfterMillis > 0) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "The shared product-query quota is exhausted. Try again after this window.");
                problem.setTitle("Product query rate limit exceeded");
                problem.setProperty("code", "PRODUCT_QUERY_RATE_LIMITED");
                problem.setProperty("retryAfterMillis", retryAfterMillis);
                // Round up to seconds so Retry-After does not advise retrying before the remaining TTL.
                long retryAfterSeconds = (retryAfterMillis + 999) / 1000;
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", Long.toString(retryAfterSeconds)).body(problem);
            }
            Map<String, Object> result = productCacheService.getProductName(productId);
            HttpStatus status = Boolean.FALSE.equals(result.get("found"))
                    ? HttpStatus.NOT_FOUND : HttpStatus.OK;
            return ResponseEntity.status(status).body(result);
        } catch (DataAccessException exception) {
            // Fail closed: a Redis decision failure must not fall through to the product query.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Rate limit, cache, or database is unavailable. Try again shortly.", exception);
        }
    }
}
