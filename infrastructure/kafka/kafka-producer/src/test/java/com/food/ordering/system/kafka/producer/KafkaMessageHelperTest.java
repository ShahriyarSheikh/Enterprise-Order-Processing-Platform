package com.food.ordering.system.kafka.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.food.ordering.system.outbox.OutboxStatus;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.SendResult;

import java.util.function.BiConsumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaMessageHelperTest {

    private final KafkaMessageHelper kafkaMessageHelper = new KafkaMessageHelper(new ObjectMapper());

    @Test
    void shouldMarkOutboxMessageCompletedWhenKafkaSendSucceeds() {
        Object outboxMessage = new Object();
        @SuppressWarnings("unchecked")
        BiConsumer<Object, OutboxStatus> outboxCallback = mock(BiConsumer.class);
        @SuppressWarnings("unchecked")
        SendResult<String, String> sendResult = mock(SendResult.class);
        RecordMetadata recordMetadata = mock(RecordMetadata.class);
        when(sendResult.getRecordMetadata()).thenReturn(recordMetadata);

        BiConsumer<SendResult<String, String>, Throwable> callback = kafkaMessageHelper.getKafkaCallback(
                "payment-response",
                "payload",
                outboxMessage,
                outboxCallback,
                "order-id",
                "PaymentResponse");

        callback.accept(sendResult, null);

        verify(outboxCallback).accept(outboxMessage, OutboxStatus.COMPLETED);
    }

    @Test
    void shouldMarkOutboxMessageFailedWhenKafkaSendFails() {
        Object outboxMessage = new Object();
        @SuppressWarnings("unchecked")
        BiConsumer<Object, OutboxStatus> outboxCallback = mock(BiConsumer.class);

        BiConsumer<SendResult<String, String>, Throwable> callback = kafkaMessageHelper.getKafkaCallback(
                "payment-response",
                "payload",
                outboxMessage,
                outboxCallback,
                "order-id",
                "PaymentResponse");

        callback.accept(null, new RuntimeException("broker unavailable"));

        verify(outboxCallback).accept(outboxMessage, OutboxStatus.FAILED);
    }
}
