package com.example.high_concurrency_lab.redis;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/redis-demo")
@RequiredArgsConstructor
public class RedisDemoController {

    private final StringRedisTemplate redisTemplate;

    /** Writes a short-lived diagnostic value and reads it back to verify Redis connectivity. */
    @PostMapping("/check")
    public Map<String, String> check() {
        String key = "high-concurrency-lab:demo:connection";
        redisTemplate.opsForValue().set(key, "connected", Duration.ofSeconds(60));

        String value = redisTemplate.opsForValue().get(key);
        if (!"connected".equals(value)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Redis read-back verification failed");
        }

        return Map.of("value", value);
    }
}
