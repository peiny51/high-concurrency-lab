package com.example.high_concurrency_lab.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

class CacheAvalancheProtectionTests {

    private ProductRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CacheAvalancheOriginGuard guard;
    private CacheAvalancheService service;

    /** Creates isolated dependencies and a two-slot guard before each protection test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ProductRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(60000L);
        guard = new CacheAvalancheOriginGuard(new CacheAvalancheProperties(2, 30));
        service = new CacheAvalancheService(repository, redis, guard);
    }

    /** Holds two loads open to verify the cross-product limit, coded 503, and recovery after release. */
    @Test
    void limitsConcurrentDatabaseLoadsAcrossDifferentProductsAndReturnsRetryable503() throws Exception {
        // Hold two mock loads open so saturation is deterministic without slowing production code.
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeDatabaseCalls = new AtomicInteger();
        AtomicInteger peakDatabaseCalls = new AtomicInteger();
        when(repository.findById(any())).thenAnswer(invocation -> {
            int active = activeDatabaseCalls.incrementAndGet();
            peakDatabaseCalls.accumulateAndGet(active, Math::max);
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Optional.of(new Product("Keyboard", 0));
            } finally {
                activeDatabaseCalls.decrementAndGet();
            }
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.queryProducts(List.of(1L)));
            var second = pool.submit(() -> service.queryProducts(List.of(2L)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var mvc = MockMvcBuilders.standaloneSetup(new CacheAvalancheController(service, guard)).build();
            String body = mvc.perform(get("/api/cache-avalanche").param("productIds", "3"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("ORIGIN_CAPACITY_EXCEEDED"));
            assertEquals(2, peakDatabaseCalls.get());
            assertEquals(2, guard.snapshot().activeRebuilds());
            assertEquals(1, guard.snapshot().rejectedRebuilds());
            verify(repository, never()).findById(3L);
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            assertEquals(0, guard.snapshot().activeRebuilds());
            assertEquals(2, guard.snapshot().databaseLoads());
            assertEquals("mysql", service.queryProducts(List.of(3L)).getFirst().get("source"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /** Verifies a cache hit succeeds even when every rebuild slot is occupied. */
    @Test
    void cacheHitBypassesOccupiedCapacity() throws Exception {
        guard = new CacheAvalancheOriginGuard(new CacheAvalancheProperties(1, 30));
        service = new CacheAvalancheService(repository, redis, guard);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> guard.execute(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "done";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            when(values.get(anyString())).thenReturn("Keyboard");
            assertEquals("redis", service.queryProducts(List.of(1L)).getFirst().get("source"));
            verify(repository, never()).findById(any());
            assertEquals(0, guard.snapshot().rejectedRebuilds());
            release.countDown();
            holder.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /** Verifies data filled before the second cache check skips MySQL and returns the slot. */
    @Test
    void rechecksCacheAfterAdmissionAndSkipsDatabase() {
        // Simulate another request filling Redis between the first lookup and the admission recheck.
        when(values.get(anyString())).thenReturn(null, "Keyboard");
        assertEquals("redis", service.queryProducts(List.of(1L)).getFirst().get("source"));
        verify(repository, never()).findById(any());
        assertEquals(1, guard.snapshot().admittedRebuilds());
        assertEquals(0, guard.snapshot().databaseLoads());
        assertEquals(0, guard.snapshot().activeRebuilds());
    }

    /** Verifies a failed database load returns its slot so a later request can succeed. */
    @Test
    void returnsCapacityAfterDatabaseFailure() {
        when(repository.findById(1L)).thenThrow(new DataAccessResourceFailureException("Database failed"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.queryProducts(List.of(1L)));
        assertEquals(0, guard.snapshot().activeRebuilds());
        // doReturn replaces the throwing stub without invoking it during test setup.
        doReturn(Optional.of(new Product("Keyboard", 0))).when(repository).findById(1L);
        assertEquals("mysql", service.queryProducts(List.of(1L)).getFirst().get("source"));
        assertEquals(0, guard.snapshot().activeRebuilds());
    }

    /** Verifies a failed cache write returns its slot so a later request can succeed. */
    @Test
    void returnsCapacityAfterRedisWriteFailure() {
        when(repository.findById(1L)).thenReturn(Optional.of(new Product("Keyboard", 0)));
        doThrow(new DataAccessResourceFailureException("Redis failed"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        assertThrows(DataAccessResourceFailureException.class, () -> service.queryProducts(List.of(1L)));
        assertEquals(0, guard.snapshot().activeRebuilds());
        doNothing().when(values).set(anyString(), anyString(), any(Duration.class));
        assertEquals("mysql", service.queryProducts(List.of(1L)).getFirst().get("source"));
    }
}
