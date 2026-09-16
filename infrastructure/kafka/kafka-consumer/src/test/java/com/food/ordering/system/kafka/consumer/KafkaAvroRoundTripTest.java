package com.food.ordering.system.kafka.consumer;

import com.food.ordering.system.kafka.config.data.KafkaConfigData;
import com.food.ordering.system.kafka.config.data.KafkaConsumerConfigData;
import com.food.ordering.system.kafka.config.data.KafkaProducerConfigData;
import com.food.ordering.system.kafka.consumer.config.KafkaConsumerConfig;
import com.food.ordering.system.kafka.order.avro.model.PaymentOrderStatus;
import com.food.ordering.system.kafka.order.avro.model.PaymentRequestAvroModel;
import com.food.ordering.system.kafka.producer.KafkaProducerConfig;
import com.food.ordering.system.kafka.producer.service.impl.KafkaProducerImpl;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("deprecation")
@Testcontainers(disabledWithoutDocker = true)
class KafkaAvroRoundTripTest {

    private static final String TOPIC = "payment-request-integration";
    private static final String SCHEMA_REGISTRY_URL_CONFIG = "schema.registry.url";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.0.1"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka")
                    .withListener(() -> "kafka:19092");

    @Container
    private static final GenericContainer<?> SCHEMA_REGISTRY =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:7.0.1"))
                    .withNetwork(NETWORK)
                    .withExposedPorts(8081)
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                    .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS",
                            "PLAINTEXT://kafka:19092")
                    .dependsOn(KAFKA)
                    .waitingFor(Wait.forHttp("/subjects")
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void publishesAndConsumesSpecificAvroRecordThroughSchemaRegistry() throws Exception {
        createTopic();
        KafkaConfigData kafkaConfigData = kafkaConfigData();
        KafkaProducerConfig<String, PaymentRequestAvroModel> producerConfig =
                new KafkaProducerConfig<>(kafkaConfigData, producerConfigData());
        KafkaConsumerConfig<String, PaymentRequestAvroModel> consumerConfig =
                new KafkaConsumerConfig<>(kafkaConfigData, consumerConfigData());
        ProducerFactory<String, PaymentRequestAvroModel> producerFactory =
                producerConfig.producerFactory();
        KafkaTemplate<String, PaymentRequestAvroModel> kafkaTemplate =
                new KafkaTemplate<>(producerFactory);
        KafkaProducerImpl<String, PaymentRequestAvroModel> producer =
                new KafkaProducerImpl<>(kafkaTemplate);
        ConsumerFactory<String, PaymentRequestAvroModel> consumerFactory =
                consumerConfig.consumerFactory();
        PaymentRequestAvroModel expected = paymentRequest();

        try (Consumer<String, PaymentRequestAvroModel> consumer =
                     consumerFactory.createConsumer("kafka-avro-integration", "round-trip")) {
            consumer.subscribe(List.of(TOPIC));
            CompletableFuture<SendResult<String, PaymentRequestAvroModel>> sendResult =
                    new CompletableFuture<>();

            producer.send(TOPIC, expected.getOrderId(), expected, (result, throwable) -> {
                if (throwable == null) {
                    sendResult.complete(result);
                } else {
                    sendResult.completeExceptionally(throwable);
                }
            });

            sendResult.get(30, TimeUnit.SECONDS);
            ConsumerRecord<String, PaymentRequestAvroModel> consumed =
                    awaitRecord(consumer, Duration.ofSeconds(30));
            String subjects = schemaRegistrySubjects();

            assertAll(
                    () -> assertEquals(expected.getOrderId(), consumed.key()),
                    () -> assertEquals(expected.getId(), consumed.value().getId()),
                    () -> assertEquals(expected.getSagaId(), consumed.value().getSagaId()),
                    () -> assertEquals(expected.getCustomerId(), consumed.value().getCustomerId()),
                    () -> assertEquals(expected.getOrderId(), consumed.value().getOrderId()),
                    () -> assertEquals(0, expected.getPrice().compareTo(consumed.value().getPrice())),
                    () -> assertEquals(expected.getCreatedAt(), consumed.value().getCreatedAt()),
                    () -> assertEquals(expected.getPaymentOrderStatus(),
                            consumed.value().getPaymentOrderStatus()),
                    () -> assertTrue(subjects.contains("\"" + TOPIC + "-value\"")));
        } finally {
            producer.close();
        }
    }

    private void createTopic() throws Exception {
        try (AdminClient adminClient = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            adminClient.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        }
    }

    private ConsumerRecord<String, PaymentRequestAvroModel> awaitRecord(
            Consumer<String, PaymentRequestAvroModel> consumer,
            Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, PaymentRequestAvroModel> records =
                    consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        return fail("Timed out waiting for the Avro record from Kafka");
    }

    private String schemaRegistrySubjects() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(schemaRegistryUrl() + "/subjects"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private KafkaConfigData kafkaConfigData() {
        KafkaConfigData config = new KafkaConfigData();
        config.setBootstrapServers(KAFKA.getBootstrapServers());
        config.setSchemaRegistryUrlKey(SCHEMA_REGISTRY_URL_CONFIG);
        config.setSchemaRegistryUrl(schemaRegistryUrl());
        return config;
    }

    private KafkaProducerConfigData producerConfigData() {
        KafkaProducerConfigData config = new KafkaProducerConfigData();
        config.setKeySerializerClass(StringSerializer.class.getName());
        config.setValueSerializerClass("io.confluent.kafka.serializers.KafkaAvroSerializer");
        config.setCompressionType("none");
        config.setAcks("all");
        config.setBatchSize(16384);
        config.setBatchSizeBoostFactor(1);
        config.setLingerMs(0);
        config.setRequestTimeoutMs(30000);
        config.setRetryCount(3);
        return config;
    }

    private KafkaConsumerConfigData consumerConfigData() {
        KafkaConsumerConfigData config = new KafkaConsumerConfigData();
        config.setKeyDeserializer(StringDeserializer.class.getName());
        config.setValueDeserializer("io.confluent.kafka.serializers.KafkaAvroDeserializer");
        config.setAutoOffsetReset("earliest");
        config.setSpecificAvroReaderKey("specific.avro.reader");
        config.setSpecificAvroReader("true");
        config.setBatchListener(false);
        config.setAutoStartup(false);
        config.setConcurrencyLevel(1);
        config.setSessionTimeoutMs(10000);
        config.setHeartbeatIntervalMs(3000);
        config.setMaxPollIntervalMs(300000);
        config.setPollTimeoutMs(1000L);
        config.setMaxPollRecords(10);
        config.setMaxPartitionFetchBytesDefault(1048576);
        config.setMaxPartitionFetchBytesBoostFactor(1);
        return config;
    }

    private PaymentRequestAvroModel paymentRequest() {
        return PaymentRequestAvroModel.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSagaId(UUID.randomUUID().toString())
                .setCustomerId(UUID.randomUUID().toString())
                .setOrderId(UUID.randomUUID().toString())
                .setPrice(new BigDecimal("125.50"))
                .setCreatedAt(Instant.parse("2026-09-16T10:15:30Z"))
                .setPaymentOrderStatus(PaymentOrderStatus.PENDING)
                .build();
    }

    private String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" +
                SCHEMA_REGISTRY.getMappedPort(8081);
    }
}
