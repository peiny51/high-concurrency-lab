package com.example.high_concurrency_lab.cache;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CacheBreakdownController {

    private final CacheBreakdownService cacheBreakdownService;

    /** Runs the lock-protected lookup; maps missing products to 404 and dependency failures to 503. */
    @GetMapping("/api/cache-breakdown")
    public ResponseEntity<Map<String, Object>> getProductName(@RequestParam Long productId) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID must be positive");
        }
        try {
            Map<String, Object> result = cacheBreakdownService.getProductName(productId);
            if (Boolean.FALSE.equals(result.get("found"))) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            }
            return ResponseEntity.ok(result);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cache or database is unavailable. Try again shortly.", exception);
        }
    }
}
