
package com.example.high_concurrency_lab.inventory;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import java.util.Map;

@RestController // controller that returns JSON responses
@RequestMapping("/api/overselling") // prefix all endpoints with /api/overselling
@RequiredArgsConstructor
public class OversellingController {

    private final ProductRepository productRepository;
    private final SafePurchaseService safePurchaseService;

    /** Resets the shared demo product stock; this is a setup endpoint, not a production stock adjustment flow. */
    @PostMapping("/reset")
    public Product reset(@RequestParam(defaultValue = "10") int stock) {
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative");
        }

        // Reuse product 1 so every test starts with the same product.
        Product product = productRepository.findById(1L)
                .orElseGet(() -> new Product("Test Product", stock));

        product.setStock(stock);
        return productRepository.save(product);
    }

    /** Validates the purchase quantity and reports whether the atomic stock deduction succeeded. */
    @PostMapping("/purchase-safe")
    public Map<String, Object> purchaseSafe(@RequestParam Long productId,
                                             @RequestParam int quantity) {
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }
        boolean success = safePurchaseService.purchase(productId, quantity);
        return Map.of(
                "success", success,
                "productId", productId,
                "quantity", quantity
        );
    }

    /** Reads the current product and stock directly from MySQL. */
    @GetMapping("/stock")
    public Product getStock(@RequestParam Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found"));
    }
}
