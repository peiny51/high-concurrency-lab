package com.example.high_concurrency_lab.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.ContainerTestUtils;

import com.example.high_concurrency_lab.inventory.Product;
import com.example.high_concurrency_lab.inventory.ProductRepository;

@SpringBootTest
class PurchaseRequestConsumerIntegrationTests {

    @Autowired
    private PurchaseRequestPublisher publisher;

    @Autowired
    private PurchaseResultRepository resultRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private DefaultErrorHandler errorHandler;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    private final TopicPartition partition = new TopicPartition(PurchaseMessagingConfiguration.TOPIC, 0);
    private final TopicPartition failurePartition = new TopicPartition(PurchaseMessagingConfiguration.FAILURE_TOPIC, 0);
    private AdminClient admin;
    private MessageListenerContainer container;
    private Product product;
    private UUID requestId;
    private String failureConstraint;
    private KafkaConsumer<String, byte[]> failureReader;

    /** Starts the real listener only against the disposable CI broker and creates an isolated product. */
    @BeforeEach
    void setUp() {
        assertEquals("concurrency_ci", jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        assertEquals("127.0.0.1:19092", bootstrapServers);
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        product = productRepository.saveAndFlush(new Product("Kafka Consumer Integration Test", 10));
        requestId = UUID.randomUUID();
        container = registry.getListenerContainer(PurchaseRequestConsumer.LISTENER_ID);
        assertNotNull(container);
        assertFalse(container.isRunning(), "Automatic consumption must remain disabled by default");
        container.start();
        ContainerTestUtils.waitForAssignment(container, 1);
    }

    /** Stops processing before removing this test's database fixtures and closes its admin connection. */
    @AfterEach
    void tearDown() {
        try {
            if (container != null) {
                container.stop();
            }
            errorHandler.setRetryListeners();
        } finally {
            try {
                removeFailureConstraint();
                if (requestId != null) {
                    resultRepository.deleteById(requestId);
                }
                if (product != null) {
                    productRepository.deleteById(product.getId());
                }
            } finally {
                if (failureReader != null) {
                    failureReader.close(Duration.ofSeconds(5));
                }
                if (admin != null) {
                    admin.close(Duration.ofSeconds(5));
                }
            }
        }
    }

    /** Verifies a real published request is consumed, commits stock/result, and advances group progress. */
    @Test
    void consumesRequestAndCommitsPurchaseBeforeOffset() throws Exception {
        publisher.publish(new PurchaseRequest(requestId, product.getId(), 3));
        awaitCommitted(endOffset());

        PurchaseResult result = resultRepository.findById(requestId).orElseThrow();
        assertTrue(result.isSuccess());
        assertEquals(3, result.getQuantity());
        assertEquals(7, storedStock());
    }

    /** Verifies insufficient stock is a completed rejection, not a listener failure that blocks consumption. */
    @Test
    void consumesInsufficientStockAsCompletedRejection() throws Exception {
        publisher.publish(new PurchaseRequest(requestId, product.getId(), 11));
        awaitCommitted(endOffset());

        assertFalse(resultRepository.findById(requestId).orElseThrow().isSuccess());
        assertEquals(10, storedStock());
        assertTrue(container.isRunning());
    }

    /** Verifies two Kafka records for the same intent are both consumed but stock is deducted once. */
    @Test
    void consumesDuplicateRecordsWithoutAnotherDeduction() throws Exception {
        PurchaseRequest request = new PurchaseRequest(requestId, product.getId(), 3);
        publisher.publish(request);
        publisher.publish(request);
        awaitCommitted(endOffset());

        assertTrue(resultRepository.findById(requestId).orElseThrow().isSuccess());
        assertEquals(7, storedStock());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_results WHERE product_id = ?", Long.class, product.getId()));
    }

    /** Verifies persistent database failure gets three attempts and a confirmed DLT copy before source progress. */
    @Test
    void parksPersistentFailureAfterBoundedRetries() throws Exception {
        long failedOffset = endOffset();
        AtomicInteger failures = new AtomicInteger();
        observeFailures(failedOffset, failures, new CountDownLatch(1));
        addFailureConstraint();
        openFailureReader();

        PurchaseRequest request = new PurchaseRequest(requestId, product.getId(), 3);
        publisher.publish(request);
        ConsumerRecord<String, byte[]> parked = readFailedRecord(failedOffset);
        awaitCommitted(failedOffset + 1);

        assertEquals(3, failures.get());
        assertEquals(PurchaseMessagingConfiguration.TOPIC, new String(
                parked.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8));
        assertNotNull(parked.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN));
        try (PurchaseRequestDeserializer decoder = new PurchaseRequestDeserializer()) {
            assertEquals(request, decoder.deserialize(parked.topic(), parked.value()));
        }
        assertEquals(10, storedStock());
        assertFalse(resultRepository.existsById(requestId));
        assertTrue(container.isRunning());
    }

    /** Removes a real database fault after its first observed failure and verifies automatic retry succeeds. */
    @Test
    void automaticallyRecoversFromTransientDatabaseFailure() throws Exception {
        long failedOffset = endOffset();
        long failureEndBefore = endOffset(failurePartition);
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch firstFailure = new CountDownLatch(1);
        observeFailures(failedOffset, failures, firstFailure);
        addFailureConstraint();

        publisher.publish(new PurchaseRequest(requestId, product.getId(), 3));
        assertTrue(firstFailure.await(10, TimeUnit.SECONDS));
        removeFailureConstraint();
        awaitCommitted(failedOffset + 1);

        assertTrue(failures.get() >= 1 && failures.get() < 3);
        assertTrue(resultRepository.findById(requestId).orElseThrow().isSuccess());
        assertEquals(7, storedStock());
        assertEquals(failureEndBefore, endOffset(failurePartition));
    }

    /** Verifies a fractional raw message is not retried and reaches DLT with byte-for-byte original content. */
    @Test
    void preservesUndecodableBytesWithoutRetrying() throws Exception {
        long failedOffset = endOffset();
        AtomicInteger failures = new AtomicInteger();
        observeFailures(failedOffset, failures, new CountDownLatch(1));
        openFailureReader();
        byte[] original = ("{\"requestId\":\"" + requestId + "\",\"productId\":" + product.getId()
                + ",\"quantity\":2.5}").getBytes(StandardCharsets.UTF_8);
        try (KafkaProducer<String, byte[]> rawProducer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            rawProducer.send(new ProducerRecord<>(PurchaseMessagingConfiguration.TOPIC,
                    product.getId().toString(), original)).get(5, TimeUnit.SECONDS);
        }
        ConsumerRecord<String, byte[]> parked = readFailedRecord(failedOffset);
        awaitCommitted(failedOffset + 1);

        assertArrayEquals(original, parked.value());
        assertEquals(1, failures.get());
        assertEquals(10, storedStock());
        assertFalse(resultRepository.existsById(requestId));
    }

    /** Verifies an invalid quantity bypasses retries even when JSON decoding succeeds. */
    @Test
    void parksInvalidIntentWithoutRetrying() throws Exception {
        long failedOffset = endOffset();
        AtomicInteger failures = new AtomicInteger();
        observeFailures(failedOffset, failures, new CountDownLatch(1));
        openFailureReader();
        publisher.publish(new PurchaseRequest(requestId, product.getId(), 0));

        readFailedRecord(failedOffset);
        awaitCommitted(failedOffset + 1);

        assertEquals(1, failures.get());
        assertEquals(10, storedStock());
        assertFalse(resultRepository.existsById(requestId));
    }

    /** Returns the next offset after the last record currently retained in the test partition. */
    private long endOffset() throws Exception {
        return endOffset(partition);
    }

    /** Reads the next offset of either the source or failure partition without consuming its records. */
    private long endOffset(TopicPartition requestedPartition) throws Exception {
        return admin.listOffsets(Map.of(requestedPartition, OffsetSpec.latest())).all()
                .get(5, TimeUnit.SECONDS).get(requestedPartition).offset();
    }

    /** Watches only this test's actual delivery attempts; the latch signals after transaction rollback. */
    private void observeFailures(long sourceOffset, AtomicInteger failures, CountDownLatch firstFailure) {
        errorHandler.setRetryListeners(new RetryListener() {
            /** Records the attempt number reported by the framework for the captured source record. */
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
                if (record.topic().equals(partition.topic()) && record.offset() == sourceOffset) {
                    failures.set(deliveryAttempt);
                    firstFailure.countDown();
                }
            }
        });
    }

    /** Starts an independent byte reader at the DLT end so existing failures cannot satisfy this test. */
    private void openFailureReader() {
        failureReader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-verification-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class));
        failureReader.assign(List.of(failurePartition));
        failureReader.seekToEnd(List.of(failurePartition));
        failureReader.position(failurePartition);
    }

    /** Finds the DLT copy by original offset and preserves its raw bytes and diagnostic headers. */
    private ConsumerRecord<String, byte[]> readFailedRecord(long sourceOffset) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : failureReader.poll(Duration.ofMillis(200))) {
                Header originalOffset = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET);
                if (originalOffset != null && ByteBuffer.wrap(originalOffset.value()).getLong() == sourceOffset) {
                    return record;
                }
            }
        }
        throw new AssertionError("No failure-topic copy was received for source offset " + sourceOffset);
    }

    /** Rejects result inserts only for this generated product, forcing genuine transactional rollback. */
    private void addFailureConstraint() {
        String name = "test_consumer_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("ALTER TABLE purchase_results ADD CONSTRAINT " + name
                + " CHECK (product_id <> " + product.getId() + ")");
        failureConstraint = name;
    }

    /** Reads the group's next committed position; a new group with no commit starts at zero in this fresh topic. */
    private long committedOffset() {
        try {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition);
            return committed == null ? 0 : committed.offset();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking consumer progress", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Could not inspect consumer progress", exception);
        }
    }

    /** Waits for a specific committed position so assertions run after real processing and acknowledgement. */
    private void awaitCommitted(long expectedOffset) throws InterruptedException {
        // The callback queries actual Kafka group progress on every bounded poll.
        awaitCondition(() -> committedOffset() >= expectedOffset, "The group must commit the processed records");
    }

    /** Polls an observable state with a deadline; never relies on a long fixed sleep for correctness. */
    private void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /** Reads the actual committed MySQL stock outside the listener's transaction. */
    private int storedStock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product.getId());
    }

    /** Restores the normal result schema after the test-owned failure injection. */
    private void removeFailureConstraint() {
        if (failureConstraint != null) {
            jdbcTemplate.execute("ALTER TABLE purchase_results DROP CHECK " + failureConstraint);
            failureConstraint = null;
        }
    }
}
