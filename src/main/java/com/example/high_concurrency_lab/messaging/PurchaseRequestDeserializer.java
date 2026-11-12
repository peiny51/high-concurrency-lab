package com.example.high_concurrency_lab.messaging;

import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Decodes only purchase intents and rejects fractional numeric IDs or quantities instead of truncating them. */
public class PurchaseRequestDeserializer extends JacksonJsonDeserializer<PurchaseRequest> {

    /** Fixes the target type, ignores producer-supplied type headers, and requires integer numeric input. */
    public PurchaseRequestDeserializer() {
        super(PurchaseRequest.class, JsonMapper.builder()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build(), false);
    }
}
