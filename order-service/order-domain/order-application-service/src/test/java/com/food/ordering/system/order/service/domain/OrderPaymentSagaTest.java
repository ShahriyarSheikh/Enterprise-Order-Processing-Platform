package com.food.ordering.system.order.service.domain;

import com.food.ordering.system.domain.valueobject.CustomerId;
import com.food.ordering.system.domain.valueobject.Money;
import com.food.ordering.system.domain.valueobject.OrderId;
import com.food.ordering.system.domain.valueobject.OrderStatus;
import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.domain.valueobject.RestaurantId;
import com.food.ordering.system.order.service.domain.dto.message.PaymentResponse;
import com.food.ordering.system.order.service.domain.entity.Order;
import com.food.ordering.system.order.service.domain.mapper.OrderDataMapper;
import com.food.ordering.system.order.service.domain.outbox.model.approval.OrderApprovalEventPayload;
import com.food.ordering.system.order.service.domain.outbox.model.approval.OrderApprovalOutboxMessage;
import com.food.ordering.system.order.service.domain.outbox.model.payment.OrderPaymentOutboxMessage;
import com.food.ordering.system.order.service.domain.outbox.scheduler.approval.ApprovalOutboxHelper;
import com.food.ordering.system.order.service.domain.outbox.scheduler.payment.PaymentOutboxHelper;
import com.food.ordering.system.order.service.domain.ports.output.repository.OrderRepository;
import com.food.ordering.system.order.service.domain.service.impl.OrderDomainServiceImpl;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.saga.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.food.ordering.system.saga.order.SagaConstants.ORDER_SAGA_NAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentSagaTest {

    private static final UUID SAGA_ID = UUID.fromString("15a497c1-0f4b-4eff-b9f4-c402c8c07afa");
    private static final UUID ORDER_ID = UUID.fromString("15a497c1-0f4b-4eff-b9f4-c402c8c07afb");
    private static final UUID CUSTOMER_ID = UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb41");
    private static final UUID RESTAURANT_ID = UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb45");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentOutboxHelper paymentOutboxHelper;
    @Mock
    private ApprovalOutboxHelper approvalOutboxHelper;

    private OrderPaymentSaga orderPaymentSaga;

    @BeforeEach
    void setUp() {
        orderPaymentSaga = new OrderPaymentSaga(
                new OrderDomainServiceImpl(),
                orderRepository,
                paymentOutboxHelper,
                approvalOutboxHelper,
                new OrderSagaHelper(orderRepository),
                new OrderDataMapper());
    }

    @Test
    void completedPaymentAdvancesOrderAndStartsRestaurantApproval() {
        Order order = orderWithStatus(OrderStatus.PENDING);
        OrderPaymentOutboxMessage paymentOutboxMessage = paymentOutboxMessage(SagaStatus.STARTED);
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.STARTED))
                .thenReturn(Optional.of(paymentOutboxMessage));
        when(orderRepository.findById(new OrderId(ORDER_ID))).thenReturn(Optional.of(order));

        orderPaymentSaga.process(paymentResponse(PaymentStatus.COMPLETED, List.of()));

        assertAll(
                () -> assertEquals(OrderStatus.PAID, order.getOrderStatus()),
                () -> assertEquals(OrderStatus.PAID, paymentOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.PROCESSING, paymentOutboxMessage.getSagaStatus()),
                () -> assertEquals(OutboxStatus.COMPLETED, paymentOutboxMessage.getOutboxStatus()),
                () -> assertNotNull(paymentOutboxMessage.getProcessedAt()));
        verify(orderRepository).save(order);
        verify(paymentOutboxHelper).save(paymentOutboxMessage);

        ArgumentCaptor<OrderApprovalEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(OrderApprovalEventPayload.class);
        verify(approvalOutboxHelper).saveApprovalOutboxMessage(
                payloadCaptor.capture(),
                eq(OrderStatus.PAID),
                eq(SagaStatus.PROCESSING),
                eq(OutboxStatus.STARTED),
                eq(SAGA_ID));
        assertAll(
                () -> assertEquals(ORDER_ID.toString(), payloadCaptor.getValue().getOrderId()),
                () -> assertEquals(RESTAURANT_ID.toString(), payloadCaptor.getValue().getRestaurantId()),
                () -> assertEquals("PAID", payloadCaptor.getValue().getRestaurantOrderStatus()));
        assertSagaLookup(SagaStatus.STARTED);
    }

    @Test
    void failedPaymentCompensatesPendingOrder() {
        List<String> failures = List.of("Insufficient credit");
        Order order = orderWithStatus(OrderStatus.PENDING);
        OrderPaymentOutboxMessage paymentOutboxMessage = paymentOutboxMessage(SagaStatus.STARTED);
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.STARTED, SagaStatus.PROCESSING))
                .thenReturn(Optional.of(paymentOutboxMessage));
        when(orderRepository.findById(new OrderId(ORDER_ID))).thenReturn(Optional.of(order));

        orderPaymentSaga.rollback(paymentResponse(PaymentStatus.FAILED, failures));

        assertAll(
                () -> assertEquals(OrderStatus.CANCELLED, order.getOrderStatus()),
                () -> assertEquals(failures, order.getFailureMessages()),
                () -> assertEquals(OrderStatus.CANCELLED, paymentOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.COMPENSATED, paymentOutboxMessage.getSagaStatus()),
                () -> assertNotNull(paymentOutboxMessage.getProcessedAt()));
        verify(orderRepository).save(order);
        verify(paymentOutboxHelper).save(paymentOutboxMessage);
        verifyNoInteractions(approvalOutboxHelper);
        assertSagaLookup(SagaStatus.STARTED, SagaStatus.PROCESSING);
    }

    @Test
    void cancelledPaymentCompletesCompensationForPaymentAndApprovalOutboxes() {
        List<String> failures = List.of("Restaurant rejected order");
        Order order = orderWithStatus(OrderStatus.CANCELLING);
        OrderPaymentOutboxMessage paymentOutboxMessage = paymentOutboxMessage(SagaStatus.PROCESSING);
        OrderApprovalOutboxMessage approvalOutboxMessage = approvalOutboxMessage(SagaStatus.COMPENSATING);
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.of(paymentOutboxMessage));
        when(orderRepository.findById(new OrderId(ORDER_ID))).thenReturn(Optional.of(order));
        when(approvalOutboxHelper.getApprovalOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.COMPENSATING))
                .thenReturn(Optional.of(approvalOutboxMessage));

        orderPaymentSaga.rollback(paymentResponse(PaymentStatus.CANCELLED, failures));

        assertAll(
                () -> assertEquals(OrderStatus.CANCELLED, order.getOrderStatus()),
                () -> assertEquals(failures, order.getFailureMessages()),
                () -> assertEquals(OrderStatus.CANCELLED, paymentOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.COMPENSATED, paymentOutboxMessage.getSagaStatus()),
                () -> assertNotNull(paymentOutboxMessage.getProcessedAt()),
                () -> assertEquals(OrderStatus.CANCELLED, approvalOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.COMPENSATED, approvalOutboxMessage.getSagaStatus()),
                () -> assertNotNull(approvalOutboxMessage.getProcessedAt()));
        verify(orderRepository).save(order);
        verify(paymentOutboxHelper).save(paymentOutboxMessage);
        verify(approvalOutboxHelper).save(approvalOutboxMessage);
        assertSagaLookup(SagaStatus.PROCESSING);
    }

    @Test
    void duplicateCompletedPaymentResponseIsIgnored() {
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.STARTED))
                .thenReturn(Optional.empty());

        orderPaymentSaga.process(paymentResponse(PaymentStatus.COMPLETED, List.of()));

        verify(paymentOutboxHelper, never()).save(any(OrderPaymentOutboxMessage.class));
        verifyNoInteractions(orderRepository, approvalOutboxHelper);
        assertSagaLookup(SagaStatus.STARTED);
    }

    @Test
    void duplicateFailedPaymentResponseIsIgnored() {
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.STARTED, SagaStatus.PROCESSING))
                .thenReturn(Optional.empty());

        orderPaymentSaga.rollback(paymentResponse(PaymentStatus.FAILED, List.of("Insufficient credit")));

        verify(paymentOutboxHelper, never()).save(any(OrderPaymentOutboxMessage.class));
        verifyNoInteractions(orderRepository, approvalOutboxHelper);
        assertSagaLookup(SagaStatus.STARTED, SagaStatus.PROCESSING);
    }

    private void assertSagaLookup(SagaStatus... expectedStatuses) {
        verify(paymentOutboxHelper).getPaymentOutboxMessageBySagaIdAndSagaStatus(SAGA_ID, expectedStatuses);
    }

    private PaymentResponse paymentResponse(PaymentStatus paymentStatus, List<String> failureMessages) {
        return PaymentResponse.builder()
                .sagaId(SAGA_ID.toString())
                .orderId(ORDER_ID.toString())
                .paymentStatus(paymentStatus)
                .failureMessages(failureMessages)
                .build();
    }

    private Order orderWithStatus(OrderStatus orderStatus) {
        return Order.builder()
                .orderId(new OrderId(ORDER_ID))
                .customerId(new CustomerId(CUSTOMER_ID))
                .restaurantId(new RestaurantId(RESTAURANT_ID))
                .price(new Money(new BigDecimal("100.00")))
                .items(List.of())
                .orderStatus(orderStatus)
                .failureMessages(new ArrayList<>())
                .build();
    }

    private OrderPaymentOutboxMessage paymentOutboxMessage(SagaStatus sagaStatus) {
        return OrderPaymentOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(SAGA_ID)
                .createdAt(ZonedDateTime.now())
                .type(ORDER_SAGA_NAME)
                .payload("{}")
                .orderStatus(orderStatusFor(sagaStatus))
                .sagaStatus(sagaStatus)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
    }

    private OrderApprovalOutboxMessage approvalOutboxMessage(SagaStatus sagaStatus) {
        return OrderApprovalOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(SAGA_ID)
                .createdAt(ZonedDateTime.now())
                .type(ORDER_SAGA_NAME)
                .payload("{}")
                .orderStatus(orderStatusFor(sagaStatus))
                .sagaStatus(sagaStatus)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
    }

    private OrderStatus orderStatusFor(SagaStatus sagaStatus) {
        return switch (sagaStatus) {
            case STARTED -> OrderStatus.PENDING;
            case PROCESSING -> OrderStatus.PAID;
            case COMPENSATING -> OrderStatus.CANCELLING;
            case SUCCEEDED -> OrderStatus.APPROVED;
            case FAILED, COMPENSATED -> OrderStatus.CANCELLED;
        };
    }
}
