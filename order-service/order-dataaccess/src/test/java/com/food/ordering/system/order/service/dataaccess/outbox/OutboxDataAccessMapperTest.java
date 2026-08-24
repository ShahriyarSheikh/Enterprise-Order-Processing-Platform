package com.food.ordering.system.order.service.dataaccess.outbox;

import com.food.ordering.system.domain.valueobject.OrderStatus;
import com.food.ordering.system.order.service.dataaccess.outbox.payment.mapper.PaymentOutboxDataAccessMapper;
import com.food.ordering.system.order.service.dataaccess.outbox.restaurantapproval.mapper.ApprovalOutboxDataAccessMapper;
import com.food.ordering.system.order.service.domain.outbox.model.approval.OrderApprovalOutboxMessage;
import com.food.ordering.system.order.service.domain.outbox.model.payment.OrderPaymentOutboxMessage;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxDataAccessMapperTest {

    private static final ZonedDateTime CREATED_AT =
            ZonedDateTime.of(2026, 8, 24, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime PROCESSED_AT = CREATED_AT.plusSeconds(5);

    @Test
    void paymentMapperPreservesProcessingTimestampInBothDirections() {
        PaymentOutboxDataAccessMapper mapper = new PaymentOutboxDataAccessMapper();
        OrderPaymentOutboxMessage message = OrderPaymentOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(UUID.randomUUID())
                .createdAt(CREATED_AT)
                .processedAt(PROCESSED_AT)
                .type("OrderProcessingSaga")
                .payload("{}")
                .orderStatus(OrderStatus.PAID)
                .sagaStatus(SagaStatus.PROCESSING)
                .outboxStatus(OutboxStatus.COMPLETED)
                .version(3)
                .build();

        OrderPaymentOutboxMessage restored = mapper.paymentOutboxEntityToOrderPaymentOutboxMessage(
                mapper.orderPaymentOutboxMessageToOutboxEntity(message));

        assertEquals(PROCESSED_AT, restored.getProcessedAt());
    }

    @Test
    void approvalMapperPreservesProcessingTimestampInBothDirections() {
        ApprovalOutboxDataAccessMapper mapper = new ApprovalOutboxDataAccessMapper();
        OrderApprovalOutboxMessage message = OrderApprovalOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(UUID.randomUUID())
                .createdAt(CREATED_AT)
                .processedAt(PROCESSED_AT)
                .type("OrderProcessingSaga")
                .payload("{}")
                .orderStatus(OrderStatus.APPROVED)
                .sagaStatus(SagaStatus.SUCCEEDED)
                .outboxStatus(OutboxStatus.COMPLETED)
                .version(4)
                .build();

        OrderApprovalOutboxMessage restored = mapper.approvalOutboxEntityToOrderApprovalOutboxMessage(
                mapper.orderCreatedOutboxMessageToOutboxEntity(message));

        assertEquals(PROCESSED_AT, restored.getProcessedAt());
    }
}
