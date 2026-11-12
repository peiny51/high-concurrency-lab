package com.example.high_concurrency_lab.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Connects Kafka purchase intents to the transactional MySQL purchase processor. */
@Component
@RequiredArgsConstructor
public class PurchaseRequestConsumer {

    public static final String LISTENER_ID = "purchase-request-consumer";

    private final PurchaseProcessingService processingService;

    /**
     * Processes one intent before offset commit; lets technical failures reach bounded retry and recovery handling.
     * @param request the purchase intent decoded from the Kafka record value
     */
    @KafkaListener(id = LISTENER_ID, topics = PurchaseMessagingConfiguration.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(PurchaseRequest request) {
        // The database transaction commits before this call returns; no HTTP purchase receipt is produced here.
        processingService.process(request);
    }
}
