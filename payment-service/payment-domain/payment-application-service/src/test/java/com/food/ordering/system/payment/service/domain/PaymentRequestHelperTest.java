package com.food.ordering.system.payment.service.domain;

import com.food.ordering.system.domain.valueobject.PaymentOrderStatus;
import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.payment.service.domain.dto.PaymentRequest;
import com.food.ordering.system.payment.service.domain.mapper.PaymentDataMapper;
import com.food.ordering.system.payment.service.domain.outbox.model.OrderOutboxMessage;
import com.food.ordering.system.payment.service.domain.outbox.scheduler.OrderOutboxHelper;
import com.food.ordering.system.payment.service.domain.ports.output.message.publisher.PaymentResponseMessagePublisher;
import com.food.ordering.system.payment.service.domain.ports.output.repository.CreditEntryRepository;
import com.food.ordering.system.payment.service.domain.ports.output.repository.CreditHistoryRepository;
import com.food.ordering.system.payment.service.domain.ports.output.repository.PaymentRepository;
import com.food.ordering.system.payment.service.domain.service.PaymentDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRequestHelperTest {

    @Mock
    private PaymentDomainService paymentDomainService;
    @Mock
    private PaymentDataMapper paymentDataMapper;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CreditEntryRepository creditEntryRepository;
    @Mock
    private CreditHistoryRepository creditHistoryRepository;
    @Mock
    private OrderOutboxHelper orderOutboxHelper;
    @Mock
    private PaymentResponseMessagePublisher paymentResponseMessagePublisher;

    private PaymentRequestHelper paymentRequestHelper;

    @BeforeEach
    void setUp() {
        paymentRequestHelper = new PaymentRequestHelper(
                paymentDomainService,
                paymentDataMapper,
                paymentRepository,
                creditEntryRepository,
                creditHistoryRepository,
                orderOutboxHelper,
                paymentResponseMessagePublisher);
    }

    @Test
    void completedDuplicateRepublishesStoredResponseWithoutRepeatingBusinessMutation() {
        UUID sagaId = UUID.randomUUID();
        PaymentRequest request = request(sagaId);
        OrderOutboxMessage storedResponse = OrderOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .createdAt(ZonedDateTime.ofInstant(request.getCreatedAt(), ZoneOffset.UTC))
                .processedAt(ZonedDateTime.ofInstant(request.getCreatedAt(), ZoneOffset.UTC).plusSeconds(1))
                .type("OrderProcessingSaga")
                .payload("{}")
                .paymentStatus(PaymentStatus.COMPLETED)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
        when(orderOutboxHelper.getCompletedOrderOutboxMessageBySagaIdAndPaymentStatus(
                sagaId, PaymentStatus.COMPLETED)).thenReturn(Optional.of(storedResponse));

        paymentRequestHelper.persistPayment(request);

        verify(paymentResponseMessagePublisher).publish(any(OrderOutboxMessage.class), any());
        verifyNoInteractions(
                paymentDomainService,
                paymentDataMapper,
                paymentRepository,
                creditEntryRepository,
                creditHistoryRepository);
    }

    private PaymentRequest request(UUID sagaId) {
        return PaymentRequest.builder()
                .id(UUID.randomUUID().toString())
                .sagaId(sagaId.toString())
                .orderId(UUID.randomUUID().toString())
                .paymentOrderStatus(PaymentOrderStatus.PENDING)
                .customerId(UUID.randomUUID().toString())
                .price(new BigDecimal("50.00"))
                .createdAt(Instant.parse("2026-08-24T10:00:00Z"))
                .build();
    }
}
