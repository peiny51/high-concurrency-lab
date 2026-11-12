package com.example.high_concurrency_lab.messaging;

import java.time.Instant;
import java.util.UUID;

/** Public completed-result fields; keeps the HTTP response independent of JPA entity internals. */
public record PurchaseResultResponse(
        UUID requestId,
        Long productId,
        int quantity,
        boolean success,
        Instant processedAt) {

    /** Copies a stored outcome into the API response without changing or saving the entity. */
    public static PurchaseResultResponse from(PurchaseResult result) {
        return new PurchaseResultResponse(result.getRequestId(), result.getProductId(), result.getQuantity(),
                result.isSuccess(), result.getProcessedAt());
    }
}
