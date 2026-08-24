package com.food.ordering.system.payment.service.dataaccess.outbox;

import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.payment.service.dataaccess.outbox.mapper.OrderOutboxDataAccessMapper;
import com.food.ordering.system.payment.service.domain.outbox.model.OrderOutboxMessage;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderOutboxDataAccessMapperTest {

    @Test
    void mapperPreservesProcessingTimestampInBothDirections() {
        ZonedDateTime createdAt = ZonedDateTime.of(2026, 8, 24, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime processedAt = createdAt.plusSeconds(5);
        OrderOutboxMessage message = OrderOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(UUID.randomUUID())
                .createdAt(createdAt)
                .processedAt(processedAt)
                .type("OrderProcessingSaga")
                .payload("{}")
                .paymentStatus(PaymentStatus.COMPLETED)
                .outboxStatus(OutboxStatus.COMPLETED)
                .version(2)
                .build();
        OrderOutboxDataAccessMapper mapper = new OrderOutboxDataAccessMapper();

        OrderOutboxMessage restored = mapper.orderOutboxEntityToOrderOutboxMessage(
                mapper.orderOutboxMessageToOutboxEntity(message));

        assertEquals(processedAt, restored.getProcessedAt());
    }
}
