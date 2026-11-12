package com.example.high_concurrency_lab.messaging;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Declares the local lab topic; one replica on one broker is not a highly available deployment. */
@Configuration
public class PurchaseMessagingConfiguration {

    public static final String TOPIC = "lab.purchase-requests.v1";
    public static final String FAILURE_TOPIC = "lab.purchase-requests.v1.DLT";

    /** Creates the purchase-intent topic through KafkaAdmin when it does not already exist. */
    @Bean
    public NewTopic purchaseRequestsTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    /** Creates the failure topic with matching partition count so failed records retain their partition number. */
    @Bean
    public NewTopic purchaseFailureTopic() {
        return TopicBuilder.name(FAILURE_TOPIC).partitions(1).replicas(1).build();
    }

    /**
     * Retries technical failures, then confirms a failure-topic copy before allowing source progress.
     * @param template the producer that supports both decoded intents and original undecodable bytes
     * @param intervalMillis the wait after each retryable failure, excluding business execution time
     * @param maxRetries additional attempts after the first delivery; zero disables retries
     * @return the container error policy; business rejections return normally and never reach it
     */
    @Bean
    public DefaultErrorHandler purchaseConsumerErrorHandler(KafkaTemplate<String, PurchaseRequest> template,
            @Value("${lab.purchase-consumer.retry-interval-millis}") long intervalMillis,
            @Value("${lab.purchase-consumer.max-retries}") long maxRetries) {
        if (intervalMillis < 1 || maxRetries < 0) {
            throw new IllegalArgumentException("Retry interval must be positive and retry count cannot be negative");
        }
        // Route failures to a separate topic without deleting the original record or changing its request ID.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(FAILURE_TOPIC, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(6));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMillis, maxRetries));
        // Invalid intent content cannot be repaired by repeating the same request.
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setResetStateOnExceptionChange(false);
        // If failure-topic publishing also fails, keep the source record pending instead of declaring recovery.
        handler.setResetStateOnRecoveryFailure(false);
        return handler;
    }
}
