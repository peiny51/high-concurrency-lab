package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.server.ResponseStatusException;

class PurchaseRequestPublisherTests {

    private KafkaTemplate<String, PurchaseRequest> template;
    private PurchaseRequestPublisher publisher;
    private final PurchaseRequest request = new PurchaseRequest(UUID.randomUUID(), 1L, 2);

    /** Creates an isolated producer mock without connecting to Kafka or modifying inventory. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        publisher = new PurchaseRequestPublisher(template);
    }

    /** Verifies the publisher waits for acknowledgement and uses product ID as the Kafka key. */
    @Test
    void waitsForBrokerAcknowledgement() throws Exception {
        CompletableFuture<SendResult<String, PurchaseRequest>> acknowledgement = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        // Hold the acknowledgement open to verify that send() alone does not finish submission.
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request)).thenAnswer(invocation -> {
            sent.countDown();
            return acknowledgement;
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var task = pool.submit(() -> publisher.publish(request));
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            acknowledgement.complete(mock(SendResult.class));
            task.get(2, TimeUnit.SECONDS);
            verify(template).send(PurchaseMessagingConfiguration.TOPIC, "1", request);
        } finally {
            // Release the worker even if an assertion fails, then close its executor.
            acknowledgement.completeExceptionally(new IllegalStateException("Test cleanup"));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /** Verifies asynchronous delivery failure becomes 503 and is not retried by application code. */
    @Test
    void failedAcknowledgementReturns503() {
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> publisher.publish(request)).getStatusCode().value());
        verify(template).send(PurchaseMessagingConfiguration.TOPIC, "1", request);
    }

    /** Verifies immediate Kafka send failure is handled before a future is available. */
    @Test
    void immediateSendFailureReturns503() {
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request))
                .thenThrow(new KafkaException("No broker metadata"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> publisher.publish(request)).getStatusCode().value());
    }

    /** Verifies a native Kafka serialization failure cannot escape as an apparent successful submission. */
    @Test
    void nativeProducerFailureReturns503() {
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request))
                .thenThrow(new org.apache.kafka.common.errors.SerializationException("Serialization failed"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> publisher.publish(request)).getStatusCode().value());
    }

    /** Verifies the bounded acknowledgement wait reports uncertainty without a five-second test delay. */
    @Test
    @SuppressWarnings("unchecked")
    void confirmationTimeoutReturns503() throws Exception {
        CompletableFuture<SendResult<String, PurchaseRequest>> future = mock(CompletableFuture.class);
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request)).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException("Confirmation timeout"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> publisher.publish(request));
        assertEquals(503, error.getStatusCode().value());
        assertTrue(error.getReason().contains("may have been received"));
    }

    /** Verifies interrupted waiting preserves the thread interrupt signal and does not claim rejection. */
    @Test
    @SuppressWarnings("unchecked")
    void interruptedWaitRestoresInterruptFlag() throws Exception {
        CompletableFuture<SendResult<String, PurchaseRequest>> future = mock(CompletableFuture.class);
        when(template.send(PurchaseMessagingConfiguration.TOPIC, "1", request)).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("Stopping"));
        try {
            assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> publisher.publish(request)).getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear the flag so this test does not interrupt unrelated tests on the same runner thread.
            Thread.interrupted();
        }
    }
}
