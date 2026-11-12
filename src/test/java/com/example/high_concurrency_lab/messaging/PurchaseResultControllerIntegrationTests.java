package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

@SpringBootTest
class PurchaseResultControllerIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PurchaseProcessingService processingService;

    @Autowired
    private PurchaseResultRepository resultRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mvc;
    private Product product;
    private UUID requestId;

    /** Uses actual Spring MVC routing and a real isolated MySQL product; no repository or service mocks. */
    @BeforeEach
    void setUp() {
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        product = productRepository.saveAndFlush(new Product("Purchase Result Query Test", 10));
        requestId = UUID.randomUUID();
    }

    /** Removes only this test's outcome and generated product after HTTP assertions finish. */
    @AfterEach
    void tearDown() {
        try {
            if (requestId != null) {
                resultRepository.deleteById(requestId);
            }
        } finally {
            if (product != null) {
                productRepository.deleteById(product.getId());
            }
        }
    }

    /** Verifies a committed success is returned repeatedly without performing another stock deduction. */
    @Test
    void returnsSuccessResultWithoutChangingStockOnRepeatedQueries() throws Exception {
        processingService.process(new PurchaseRequest(requestId, product.getId(), 3));

        for (int query = 0; query < 2; query++) {
            mvc.perform(get("/api/purchase-results/{requestId}", requestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                    .andExpect(jsonPath("$.productId").value(product.getId()))
                    .andExpect(jsonPath("$.quantity").value(3))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.processedAt").isNotEmpty());
        }

        assertEquals(7, storedStock());
    }

    /** Verifies a business rejection is a successful result lookup with success=false, not an HTTP error. */
    @Test
    void returnsBusinessRejectionAsHttp200() throws Exception {
        processingService.process(new PurchaseRequest(requestId, product.getId(), 11));

        mvc.perform(get("/api/purchase-results/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.quantity").value(11))
                .andExpect(jsonPath("$.success").value(false));

        assertEquals(10, storedStock());
    }

    /** Verifies a missing outcome is explicitly unavailable, without creating a result or claiming rejection. */
    @Test
    void returns404WhenNoCompletedResultExists() throws Exception {
        mvc.perform(get("/api/purchase-results/{requestId}", requestId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_RESULT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.title").value("Purchase result not available"))
                .andExpect(jsonPath("$.detail").value(
                        "No completed result is available for this request ID. It may be unprocessed or unknown."));

        assertFalse(resultRepository.existsById(requestId));
        assertEquals(10, storedStock());
    }

    /** Verifies malformed UUID input is rejected by Spring's path conversion before querying a result. */
    @Test
    void returns400ForMalformedRequestId() throws Exception {
        mvc.perform(get("/api/purchase-results/not-a-uuid"))
                .andExpect(status().isBadRequest());

        assertFalse(resultRepository.existsById(requestId));
        assertEquals(10, storedStock());
    }

    /** Reads committed stock directly from MySQL to check that HTTP lookups remain read-only. */
    private int storedStock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product.getId());
    }
}
