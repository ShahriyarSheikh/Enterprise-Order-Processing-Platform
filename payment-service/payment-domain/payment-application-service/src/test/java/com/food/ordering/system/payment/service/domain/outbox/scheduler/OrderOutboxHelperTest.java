package com.food.ordering.system.payment.service.domain.outbox.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.payment.service.domain.outbox.model.OrderOutboxMessage;
import com.food.ordering.system.payment.service.domain.ports.output.repository.OrderOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderOutboxHelperTest {

    @Mock
    private OrderOutboxRepository orderOutboxRepository;
    @Mock
    private ObjectMapper objectMapper;

    private OrderOutboxHelper orderOutboxHelper;

    @BeforeEach
    void setUp() {
        orderOutboxHelper = new OrderOutboxHelper(orderOutboxRepository, objectMapper);
    }

    @ParameterizedTest
    @EnumSource(value = OutboxStatus.class, names = {"COMPLETED", "FAILED"})
    void callbackStatusTransitionIsPersisted(OutboxStatus callbackStatus) {
        OrderOutboxMessage message = OrderOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(UUID.randomUUID())
                .createdAt(ZonedDateTime.of(2026, 8, 24, 10, 0, 0, 0, ZoneOffset.UTC))
                .type("OrderProcessingSaga")
                .payload("{}")
                .paymentStatus(PaymentStatus.COMPLETED)
                .outboxStatus(OutboxStatus.STARTED)
                .build();
        when(orderOutboxRepository.save(message)).thenReturn(message);

        orderOutboxHelper.updateOutboxMessage(message, callbackStatus);

        assertEquals(callbackStatus, message.getOutboxStatus());
        verify(orderOutboxRepository).save(message);
    }
}
