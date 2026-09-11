package com.food.ordering.system.restaurant.service.dataaccess.restaurant.outbox;

import com.food.ordering.system.domain.valueobject.OrderApprovalStatus;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.restaurant.service.dataaccess.restaurant.outbox.entity.OrderOutboxEntity;
import com.food.ordering.system.restaurant.service.dataaccess.restaurant.outbox.mapper.OrderOutboxDataAccessMapper;
import com.food.ordering.system.restaurant.service.domain.outbox.model.OrderOutboxMessage;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Version;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
                .approvalStatus(OrderApprovalStatus.APPROVED)
                .outboxStatus(OutboxStatus.COMPLETED)
                .version(2)
                .build();
        OrderOutboxDataAccessMapper mapper = new OrderOutboxDataAccessMapper();

        OrderOutboxMessage restored = mapper.orderOutboxEntityToOrderOutboxMessage(
                mapper.orderOutboxMessageToOutboxEntity(message));

        assertEquals(processedAt, restored.getProcessedAt());
    }

    @Test
    void outboxVersionParticipatesInJpaOptimisticLocking() throws NoSuchFieldException {
        assertNotNull(OrderOutboxEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    }
}
