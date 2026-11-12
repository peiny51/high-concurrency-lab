package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

class PurchaseRequestDeserializerTests {

    /** Verifies real JSON decoding preserves the original request ID, product ID and integer quantity. */
    @Test
    void decodesPurchaseIntent() {
        UUID requestId = UUID.randomUUID();
        String json = "{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":3}";
        try (PurchaseRequestDeserializer deserializer = new PurchaseRequestDeserializer()) {
            assertEquals(new PurchaseRequest(requestId, 1L, 3),
                    deserializer.deserialize(PurchaseMessagingConfiguration.TOPIC, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Verifies fractional IDs and quantities fail decoding instead of changing the purchase intent. */
    @Test
    void rejectsFractionalProductIdAndQuantity() {
        UUID requestId = UUID.randomUUID();
        String[] invalidBodies = {
                "{\"requestId\":\"" + requestId + "\",\"productId\":1.5,\"quantity\":3}",
                "{\"requestId\":\"" + requestId + "\",\"productId\":1,\"quantity\":2.5}"
        };
        try (PurchaseRequestDeserializer deserializer = new PurchaseRequestDeserializer()) {
            for (String body : invalidBodies) {
                // Each callback runs the real decoder with one invalid purchase intent.
                assertThrows(SerializationException.class, () -> deserializer.deserialize(
                        PurchaseMessagingConfiguration.TOPIC, body.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
