package com.example.high_concurrency_lab.messaging;

import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/** Writes normal intents as JSON and preserves undecodable message bytes when publishing to the failure topic. */
public class PurchaseMessageSerializer extends DelegatingByTypeSerializer {

    /** Selects a serializer by the actual value type without turning failed raw bytes into a JSON string. */
    public PurchaseMessageSerializer() {
        super(Map.of(PurchaseRequest.class, new JacksonJsonSerializer<>(), byte[].class, new ByteArraySerializer()));
    }
}
