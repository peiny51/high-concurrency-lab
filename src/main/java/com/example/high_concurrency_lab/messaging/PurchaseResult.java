package com.example.high_concurrency_lab.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/** Stores a completed business outcome; database or messaging failures are not purchase rejections. */
@Entity
@Table(name = "purchase_results")
@Getter
public class PurchaseResult {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private boolean success;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /** Allows JPA to reconstruct a stored result; application code uses the validated constructor. */
    protected PurchaseResult() {
    }

    /**
     * Creates a completed outcome in memory and captures its processing time; does not save or deduct stock.
     * @param requestId the original purchase request ID, reused rather than generated again
     * @param productId the requested product ID, retained even when the product was not found
     * @param quantity the positive requested quantity
     * @param success true for a completed purchase, false for a completed business rejection
     * @throws NullPointerException if either ID is null
     * @throws IllegalArgumentException if the product ID or quantity is not positive
     */
    public PurchaseResult(UUID requestId, Long productId, int quantity, boolean success) {
        this.requestId = Objects.requireNonNull(requestId, "Request ID is required");
        this.productId = Objects.requireNonNull(productId, "Product ID is required");
        if (productId <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("Product ID and quantity must be positive");
        }
        this.quantity = quantity;
        this.success = success;
        this.processedAt = Instant.now();
    }
}
