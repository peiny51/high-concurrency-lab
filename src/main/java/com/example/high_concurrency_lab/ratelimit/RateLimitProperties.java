package com.example.high_concurrency_lab.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/** Configures a shared product-query quota; the window starts with its first admitted request. */
@Validated
@ConfigurationProperties(prefix = "lab.rate-limit")
public record RateLimitProperties(@Min(1) int maxRequests, @Min(1) int windowSeconds) {
}
