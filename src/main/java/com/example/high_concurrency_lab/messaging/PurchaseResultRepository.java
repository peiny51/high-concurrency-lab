package com.example.high_concurrency_lab.messaging;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Saves and queries completed outcomes by request ID.
 * Inherited save operations do not implement insert-only claiming or business-level deduplication.
 */
public interface PurchaseResultRepository extends JpaRepository<PurchaseResult, UUID> {
}
