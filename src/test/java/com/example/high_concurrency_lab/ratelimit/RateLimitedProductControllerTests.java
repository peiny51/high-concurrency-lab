package com.example.high_concurrency_lab.ratelimit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.high_concurrency_lab.cache.ProductCacheService;

class RateLimitedProductControllerTests {

    private ProductQueryRateLimiter limiter;
    private ProductCacheService service;
    private MockMvc mvc;

    /** Creates isolated HTTP handling with mocked admission and product queries before each test. */
    @BeforeEach
    void setUp() {
        limiter = mock(ProductQueryRateLimiter.class);
        service = mock(ProductCacheService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RateLimitedProductController(limiter, service)).build();
    }

    /** Verifies an admitted request returns the existing cache-aside result. */
    @Test
    void admittedQueryReturnsProduct() throws Exception {
        when(limiter.tryAcquire()).thenReturn(0L);
        when(service.getProductName(1L)).thenReturn(Map.of("productId", 1L, "found", true,
                "name", "Keyboard", "source", "redis"));
        mvc.perform(get("/api/rate-limit/product").param("productId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("redis"));
        verify(service).getProductName(1L);
    }

    /** Verifies rejection skips the product query and rounds the retry delay up to whole seconds. */
    @Test
    void rejectedQueryReturns429WithoutQueryingProduct() throws Exception {
        when(limiter.tryAcquire()).thenReturn(1001L);
        mvc.perform(get("/api/rate-limit/product").param("productId", "1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("PRODUCT_QUERY_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterMillis").value(1001));
        verifyNoInteractions(service);
    }

    /** Verifies invalid IDs consume no quota and never reach the query service. */
    @Test
    void invalidInputReturns400BeforeAdmission() throws Exception {
        for (String id : new String[] { "0", "-1", "abc" }) {
            mvc.perform(get("/api/rate-limit/product").param("productId", id))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(limiter, service);
    }

    /** Verifies Redis admission failure returns 503 instead of silently allowing a product query. */
    @Test
    void decisionFailureDoesNotBypassAdmission() throws Exception {
        when(limiter.tryAcquire()).thenThrow(new DataAccessResourceFailureException("Redis unavailable"));
        mvc.perform(get("/api/rate-limit/product").param("productId", "1"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }

    /** Verifies confirmed absence still consumes admission and preserves the existing 404 contract. */
    @Test
    void admittedMissingProductReturns404() throws Exception {
        when(service.getProductName(99L)).thenReturn(Map.of("productId", 99L, "found", false));
        mvc.perform(get("/api/rate-limit/product").param("productId", "99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.found").value(false));
        verify(limiter).tryAcquire();
    }

    /** Verifies a failed admitted query is reported as unavailable without automatic admission retry. */
    @Test
    void queryFailureDoesNotRetry() throws Exception {
        when(service.getProductName(1L)).thenThrow(new DataAccessResourceFailureException("Database unavailable"));
        mvc.perform(get("/api/rate-limit/product").param("productId", "1"))
                .andExpect(status().isServiceUnavailable());
        verify(limiter).tryAcquire();
    }
}
