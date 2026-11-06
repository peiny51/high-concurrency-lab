package com.example.high_concurrency_lab.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SafePurchaseService {

    private final ProductRepository productRepository;

    /** Attempts an all-or-nothing quantity deduction; returns false for insufficient stock or a missing ID. */
    @Transactional
    public boolean purchase(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        // Keep the availability check and subtraction in one SQL statement to avoid overselling.
        int updatedRows = productRepository.decreaseStockIfAvailable(productId, quantity);
        return updatedRows == 1;
    }
}
