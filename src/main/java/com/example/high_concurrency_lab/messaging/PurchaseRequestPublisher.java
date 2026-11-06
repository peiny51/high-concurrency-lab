package com.example.high_concurrency_lab.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Publishes purchase intents without touching inventory or declaring business success. */
@Service
@RequiredArgsConstructor
public class PurchaseRequestPublisher {

    private final KafkaTemplate<String, PurchaseRequest> kafkaTemplate;

    /**
     * Sends a validated intent and waits for broker acknowledgement; does not wait for business processing.
     * The five-second future wait follows send(), which can itself block up to the configured max.block.ms.
     * @param request the validated client intent; this method performs no business-level deduplication
     * @throws ResponseStatusException if acknowledgement fails, times out, or is interrupted
     */
    public void publish(PurchaseRequest request) {
        try {
            // The product ID is a partitioning key, not an idempotency key or a stock reservation.
            // kafkaTemplate.send(topic, key, value)
            kafkaTemplate.send(PurchaseMessagingConfiguration.TOPIC, request.productId().toString(), request)
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Submission confirmation was interrupted. The message may have been received.", exception);
        } catch (ExecutionException | TimeoutException | KafkaException | org.apache.kafka.common.KafkaException exception) {
            // A lost acknowledgement does not prove that Kafka rejected the message; do not resend here.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Submission could not be confirmed. The message may have been received; purchase success is unknown.",
                    exception);
        }
    }
}
