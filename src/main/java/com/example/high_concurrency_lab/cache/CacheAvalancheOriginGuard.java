package com.example.high_concurrency_lab.cache;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounds simultaneous rebuild sections for this instance and avalanche query path, not the whole cluster.
 */
@Component
@EnableConfigurationProperties(CacheAvalancheProperties.class)
public class CacheAvalancheOriginGuard {

    private final CacheAvalancheProperties properties;
    private final Semaphore permits;
    private final AtomicInteger activeRebuilds = new AtomicInteger();
    private final AtomicInteger peakRebuilds = new AtomicInteger();
    private final AtomicLong admittedRebuilds = new AtomicLong();
    private final AtomicLong rejectedRebuilds = new AtomicLong();
    private final AtomicLong databaseLoads = new AtomicLong();

    /** Creates a fair semaphore with the configured number of rebuild slots. */
    public CacheAvalancheOriginGuard(CacheAvalancheProperties properties) {
        this.properties = properties;
        this.permits = new Semaphore(properties.maxConcurrentRebuilds(), true);
    }

    /**
     * Runs the supplied operation synchronously after bounded admission and always returns its slot.
     * @param operation rebuild work; it runs only after a slot is acquired
     * @param <T> the operation result type
     * @return the operation result
     * @throws CapacityExceededException if no slot is available within the wait budget
     */
    public <T> T execute(Supplier<T> operation) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(properties.maxWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            // Preserve the cancellation signal for code higher in the call stack.
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Waiting for cache rebuild capacity was interrupted", exception);
        }
        // Reject before entering the release block: no slot was acquired on this path.
        if (!acquired) {
            rejectedRebuilds.incrementAndGet();
            throw new CapacityExceededException();
        }

        int active = activeRebuilds.incrementAndGet();
        // Atomically retain the largest observed number of active rebuild sections.
        peakRebuilds.accumulateAndGet(active, Math::max);
        admittedRebuilds.incrementAndGet();
        try {
            return operation.get();
        } finally {
            // Return capacity even if the database or cache write throws an exception.
            activeRebuilds.decrementAndGet();
            permits.release();
        }
    }

    /** Counts a repository load attempt, including attempts that later fail. */
    public void recordDatabaseLoad() {
        databaseLoads.incrementAndGet();
    }

    /** Returns per-instance counters since startup; separately read fields are not one atomic snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(properties.maxConcurrentRebuilds(), properties.maxWaitMillis(),
                activeRebuilds.get(), peakRebuilds.get(), admittedRebuilds.get(),
                rejectedRebuilds.get(), databaseLoads.get());
    }

    /** Read-only metrics response; admitted sections may finish without loading the database. */
    public record Snapshot(int maxConcurrentRebuilds, long maxWaitMillis,
            int activeRebuilds, int peakRebuilds, long admittedRebuilds,
            long rejectedRebuilds, long databaseLoads) {
    }

    /** Signals bounded admission failure so the controller can return a recognizable retryable 503. */
    public static class CapacityExceededException extends RuntimeException {
        /** Creates the capacity-rejection signal without executing the protected operation. */
        public CapacityExceededException() {
            super("Cache rebuild capacity is busy. Try again shortly.");
        }
    }
}
