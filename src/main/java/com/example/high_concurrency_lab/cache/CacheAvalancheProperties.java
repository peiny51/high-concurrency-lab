package com.example.high_concurrency_lab.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/** Validated per-instance rebuild limits; a zero wait budget means immediate admission or rejection. */
@Validated
@ConfigurationProperties(prefix = "lab.cache-avalanche")
public record CacheAvalancheProperties(
        @Min(1) int maxConcurrentRebuilds,
        @Min(0) long maxWaitMillis) {
}
