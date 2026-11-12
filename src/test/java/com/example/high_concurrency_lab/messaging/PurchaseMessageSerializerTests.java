package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class PurchaseMessageSerializerTests {

    /** Verifies normal intents remain compatible with the consumer's real JSON decoder. */
    @Test
    void serializesNormalIntentAsJson() {
        PurchaseRequest request = new PurchaseRequest(UUID.randomUUID(), 1L, 3);
        try (PurchaseMessageSerializer serializer = new PurchaseMessageSerializer();
                PurchaseRequestDeserializer decoder = new PurchaseRequestDeserializer()) {
            byte[] bytes = serializer.serialize(PurchaseMessagingConfiguration.TOPIC, new RecordHeaders(), request);
            assertEquals(request, decoder.deserialize(PurchaseMessagingConfiguration.TOPIC, bytes));
        }
    }

    /** Verifies undecodable message bytes are copied unchanged instead of being JSON/base64 encoded. */
    @Test
    void preservesOriginalBytesForFailureTopic() {
        byte[] original = "not valid JSON".getBytes(StandardCharsets.UTF_8);
        try (PurchaseMessageSerializer serializer = new PurchaseMessageSerializer()) {
            assertArrayEquals(original, serializer.serialize(
                    PurchaseMessagingConfiguration.FAILURE_TOPIC, new RecordHeaders(), original));
        }
    }
}
