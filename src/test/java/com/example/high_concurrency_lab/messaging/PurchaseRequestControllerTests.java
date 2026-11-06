package com.example.high_concurrency_lab.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PurchaseRequestControllerTests {

    private PurchaseRequestPublisher publisher;
    private MockMvc mvc;
    private final String requestId = "8ebcf626-74b0-490a-870e-571f535dc278";

    /** Creates HTTP handling with real input validation and a mocked broker-publishing boundary. */
    @BeforeEach
    void setUp() {
        publisher = mock(PurchaseRequestPublisher.class);
        mvc = MockMvcBuilders.standaloneSetup(new PurchaseRequestController(publisher)).build();
    }

    /** Verifies an acknowledged submission returns a receipt, not a completed-purchase result. */
    @Test
    void acknowledgedSubmissionReturns202() throws Exception {
        mvc.perform(post("/api/purchase-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
        verify(publisher).publish(new PurchaseRequest(UUID.fromString(requestId), 1L, 2));
    }

    /** Verifies missing fields, malformed IDs, and invalid quantities never reach Kafka publishing. */
    @Test
    void invalidRequestsReturn400WithoutPublishing() throws Exception {
        String[] bodies = {
                "{}",
                "{\"requestId\":\"not-a-uuid\",\"productId\":1,\"quantity\":2}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":0,\"quantity\":2}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":0}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":-1}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":1}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":2147483648}"
        };
        for (String body : bodies) {
            mvc.perform(post("/api/purchase-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(publisher);
    }

    /** Verifies an unconfirmed send returns the explicit error code instead of HTTP 202. */
    @Test
    void unconfirmedSubmissionReturns503() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The message may have been received.")).when(publisher).publish(any(PurchaseRequest.class));
        mvc.perform(post("/api/purchase-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PURCHASE_SUBMISSION_UNCONFIRMED"));
    }
}
