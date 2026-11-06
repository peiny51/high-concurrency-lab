package com.example.high_concurrency_lab.cache;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ProductCacheController {

    private final ProductCacheService productCacheService;

    /** Validates the ID and maps a confirmed missing product to HTTP 404. */
    @GetMapping("/api/product-cache")
    public ResponseEntity<Map<String, Object>> getProductName(@RequestParam Long productId) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID must be positive");
        }
        Map<String, Object> result = productCacheService.getProductName(productId);
        if (Boolean.FALSE.equals(result.get("found"))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** Validates the ID and delegates the name update followed by cache invalidation. */
    @PostMapping("/api/product-cache/rename")
    public Map<String, Object> renameProduct(@RequestParam Long productId, @RequestParam String name) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product ID must be positive");
        }
        return productCacheService.renameProduct(productId, name);
    }
}
