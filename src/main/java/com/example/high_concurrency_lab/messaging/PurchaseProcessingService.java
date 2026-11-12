package com.example.high_concurrency_lab.messaging;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.high_concurrency_lab.inventory.SafePurchaseService;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** Processes a purchase and its durable outcome in one MySQL transaction; does not consume Kafka messages. */
@Service
@RequiredArgsConstructor
public class PurchaseProcessingService {

    private final PurchaseResultRepository resultRepository;
    private final SafePurchaseService purchaseService;
    private final EntityManager entityManager;

    /**
     * Reuses an identical completed request, or atomically deducts stock and inserts a new result.
     * A racing duplicate may fail its insert; the entire transaction must roll back before any retry.
     * @param request the purchase intent; validation also applies to callers outside an HTTP controller
     * @return the existing or newly completed business outcome, not a submission receipt
     * @throws NullPointerException if the request or request ID is null
     * @throws IllegalArgumentException for invalid product/quantity or conflicting reuse of a request ID
     * @throws jakarta.persistence.PersistenceException if persistence fails, including a racing duplicate
     */
    @Transactional
    public PurchaseResult process(PurchaseRequest request) {
        Objects.requireNonNull(request, "Purchase request is required");
        UUID requestId = Objects.requireNonNull(request.requestId(), "Request ID is required");
        if (request.productId() == null || request.productId() <= 0
                || request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalArgumentException("Product ID and quantity must be positive");
        }

        PurchaseResult existing = resultRepository.findById(requestId).orElse(null);
        if (existing != null) {
            // A retry must describe the same intent, not a new purchase disguised by an old ID.
            if (!existing.getProductId().equals(request.productId())
                    || existing.getQuantity() != request.quantity()) {
                throw new IllegalArgumentException("Request ID already belongs to a different purchase");
            }
            return existing;
        }

        // The existing purchase service joins this transaction; it does not commit stock separately.
        boolean success = purchaseService.purchase(request.productId(), request.quantity());
        PurchaseResult result = new PurchaseResult(requestId, request.productId(), request.quantity(), success);

        // Persist requests an INSERT, never a merge that could silently accept an existing request ID.
        // Flush executes pending SQL now, but only the transaction interceptor commits after this method returns.
        entityManager.persist(result);
        entityManager.flush();
        return result;
    }
}
