package com.example.high_concurrency_lab.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the local lab topic; one replica on one broker is not a highly available deployment. */
@Configuration
public class PurchaseMessagingConfiguration {

    public static final String TOPIC = "lab.purchase-requests.v1";

    /** Creates the purchase-intent topic through KafkaAdmin when it does not already exist. */
    @Bean
    public NewTopic purchaseRequestsTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }
}
