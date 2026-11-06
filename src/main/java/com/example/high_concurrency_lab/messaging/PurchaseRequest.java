package com.example.high_concurrency_lab.messaging;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** A submitted purchase intent, not a completed purchase; requestId identifies the intent but does not deduplicate it. */
public record PurchaseRequest(
        @NotNull UUID requestId,
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {
}
