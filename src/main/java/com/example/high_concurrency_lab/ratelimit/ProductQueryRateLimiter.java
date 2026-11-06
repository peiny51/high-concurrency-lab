package com.example.high_concurrency_lab.ratelimit;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shares one endpoint-wide quota across clients and application instances using the same Redis key. */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class ProductQueryRateLimiter {

    private static final String KEY = "high-concurrency-lab:rate-limit:product-query";

    // Check, count, and set the first expiry without allowing another request to interleave.
    // Rejected requests neither increase the count nor extend the current window.
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local count = redis.call('GET', KEYS[1])
            if not count then
                redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
                return 0
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                return -1
            end
            if tonumber(count) >= tonumber(ARGV[1]) then
                return math.max(ttl, 1)
            end
            redis.call('INCR', KEYS[1])
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    /** Receives Redis access and validated quota settings through constructor injection. */
    public ProductQueryRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Attempts admission without waiting or retrying; an admitted request consumes quota even if its query fails.
     * @return zero when admitted, or positive milliseconds until the current window ends when rejected
     * @throws ResponseStatusException if Redis returns an unusable decision; no query is admitted in that case
     */
    public long tryAcquire() {
        Long retryAfterMillis = redisTemplate.execute(ACQUIRE, List.of(KEY),
                Integer.toString(properties.maxRequests()),
                Long.toString(properties.windowSeconds() * 1000L));
        if (retryAfterMillis == null || retryAfterMillis < 0) {
            // An invalid counter expiry must not silently bypass the protection.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Rate limit decision is unavailable. Try again shortly.");
        }
        return retryAfterMillis;
    }
}
