package com.example.high_concurrency_lab.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.example.high_concurrency_lab.inventory.ProductRepository;

class CacheBreakdownServiceTests {

    private ProductRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private CacheBreakdownService service;

    /** Creates isolated repository and Redis mocks before each lock-rebuild test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ProductRepository.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new CacheBreakdownService(repository, redis);
    }

    /** Verifies the second cache check avoids a database load and still releases the acquired lock. */
    @Test
    void skipsDatabaseWhenAnotherRequestFilledCacheBeforeLockAcquisition() {
        String prefix = "high-concurrency-lab:product:1";
        when(values.get(prefix + ":name")).thenReturn(null, "Keyboard");
        when(values.setIfAbsent(eq(prefix + ":lock"), anyString(), any(Duration.class)))
                .thenReturn(true);

        Map<String, Object> result = service.getProductName(1L);

        assertEquals("redis", result.get("source"));
        assertEquals("Keyboard", result.get("name"));
        verify(repository, never()).findById(any());
        verify(redis).execute(any(RedisScript.class), eq(List.of(prefix + ":lock")), anyString());
    }

    /** Verifies database failure still attempts owner-checked lock release without writing cache data. */
    @Test
    void attemptsLockReleaseEvenWhenDatabaseQueryFails() {
        String lockKey = "high-concurrency-lab:product:1:lock";
        when(values.setIfAbsent(eq(lockKey), anyString(), any(Duration.class))).thenReturn(true);
        when(repository.findById(1L)).thenThrow(new IllegalStateException("Database failed"));

        assertThrows(IllegalStateException.class, () -> service.getProductName(1L));

        verify(redis).execute(any(RedisScript.class), eq(List.of(lockKey)), anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
