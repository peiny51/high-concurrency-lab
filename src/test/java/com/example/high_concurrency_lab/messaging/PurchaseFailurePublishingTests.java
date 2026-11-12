package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedRuntimeException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

class PurchaseFailurePublishingTests {

    /** Verifies a failed DLT acknowledgement escapes recovery and seeks the source record instead of committing it. */
    @Test
    @SuppressWarnings("unchecked")
    void doesNotRecoverWhenFailureTopicPublishingFails() {
        KafkaTemplate<String, PurchaseRequest> template = mock(KafkaTemplate.class);
        ProducerFactory<String, PurchaseRequest> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
        when(template.partitionsFor(PurchaseMessagingConfiguration.FAILURE_TOPIC)).thenReturn(List.of(
                new PartitionInfo(PurchaseMessagingConfiguration.FAILURE_TOPIC, 0, null, new Node[0], new Node[0])));
        when(template.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new KafkaException("Failure topic acknowledgement unavailable")));

        DefaultErrorHandler handler = new PurchaseMessagingConfiguration().purchaseConsumerErrorHandler(template, 1, 0);
        Consumer<String, PurchaseRequest> source = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ContainerProperties properties = new ContainerProperties(PurchaseMessagingConfiguration.TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        when(container.getContainerProperties()).thenReturn(properties);
        ConsumerRecord<String, PurchaseRequest> record = new ConsumerRecord<>(PurchaseMessagingConfiguration.TOPIC,
                0, 7, "1", new PurchaseRequest(UUID.randomUUID(), 1L, 3));

        // Exercise the actual configured handler; only the broker-acknowledgement boundary is simulated.
        assertThrows(NestedRuntimeException.class, () -> handler.handleRemaining(
                new IllegalStateException("Database unavailable"), List.of(record), source, container));

        verify(template).send(any(ProducerRecord.class));
        verify(source).seek(new TopicPartition(PurchaseMessagingConfiguration.TOPIC, 0), 7L);
        verify(source, never()).commitSync(anyMap());
        verify(source, never()).commitAsync(anyMap(), any());
    }
}
