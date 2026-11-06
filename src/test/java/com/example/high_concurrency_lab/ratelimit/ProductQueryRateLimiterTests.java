package com.example.high_concurrency_lab.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

class ProductQueryRateLimiterTests {

    /** Verifies the Redis script receives one shared key and the configured quota and millisecond window. */
    @Test
    @SuppressWarnings("unchecked")
    void forwardsSettingsAndAdmissionDecision() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class),
                eq(List.of("high-concurrency-lab:rate-limit:product-query")), eq("5"), eq("10000")))
                .thenReturn(0L, 1500L);
        ProductQueryRateLimiter limiter = new ProductQueryRateLimiter(redis, new RateLimitProperties(5, 10));
        assertEquals(0, limiter.tryAcquire());
        assertEquals(1500, limiter.tryAcquire());
    }

    /** Verifies missing or invalid script decisions fail closed instead of granting quota. */
    @Test
    @SuppressWarnings("unchecked")
    void unusableDecisionReturns503() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class),
                eq(List.of("high-concurrency-lab:rate-limit:product-query")), eq("5"), eq("10000")))
                .thenReturn(null, -1L);
        ProductQueryRateLimiter limiter = new ProductQueryRateLimiter(redis, new RateLimitProperties(5, 10));
        assertEquals(503, assertThrows(ResponseStatusException.class, limiter::tryAcquire).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, limiter::tryAcquire).getStatusCode().value());
    }
}
