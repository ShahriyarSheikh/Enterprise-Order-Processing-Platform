package com.food.ordering.system.order.service.domain;

import com.food.ordering.system.domain.valueobject.CustomerId;
import com.food.ordering.system.domain.valueobject.Money;
import com.food.ordering.system.domain.valueobject.OrderApprovalStatus;
import com.food.ordering.system.domain.valueobject.OrderId;
import com.food.ordering.system.domain.valueobject.OrderStatus;
import com.food.ordering.system.domain.valueobject.RestaurantId;
import com.food.ordering.system.order.service.domain.dto.message.RestaurantApprovalResponse;
import com.food.ordering.system.order.service.domain.entity.Order;
import com.food.ordering.system.order.service.domain.mapper.OrderDataMapper;
import com.food.ordering.system.order.service.domain.outbox.model.approval.OrderApprovalOutboxMessage;
import com.food.ordering.system.order.service.domain.outbox.model.payment.OrderPaymentEventPayload;
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
class OrderApprovalSagaTest {

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

    private OrderApprovalSaga orderApprovalSaga;

    @BeforeEach
    void setUp() {
        OrderSagaHelper orderSagaHelper = new OrderSagaHelper(orderRepository);
        orderApprovalSaga = new OrderApprovalSaga(
                new OrderDomainServiceImpl(),
                orderSagaHelper,
                paymentOutboxHelper,
                approvalOutboxHelper,
                new OrderDataMapper());
    }

    @Test
    void approvedRestaurantResponseCompletesSaga() {
        Order order = orderWithStatus(OrderStatus.PAID);
        OrderApprovalOutboxMessage approvalOutboxMessage = approvalOutboxMessage(SagaStatus.PROCESSING);
        OrderPaymentOutboxMessage paymentOutboxMessage = paymentOutboxMessage(SagaStatus.PROCESSING);
        when(approvalOutboxHelper.getApprovalOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.of(approvalOutboxMessage));
        when(orderRepository.findById(new OrderId(ORDER_ID))).thenReturn(Optional.of(order));
        when(paymentOutboxHelper.getPaymentOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.of(paymentOutboxMessage));

        orderApprovalSaga.process(approvalResponse(OrderApprovalStatus.APPROVED, List.of()));

        assertAll(
                () -> assertEquals(OrderStatus.APPROVED, order.getOrderStatus()),
                () -> assertEquals(OrderStatus.APPROVED, approvalOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.SUCCEEDED, approvalOutboxMessage.getSagaStatus()),
                () -> assertNotNull(approvalOutboxMessage.getProcessedAt()),
                () -> assertEquals(OrderStatus.APPROVED, paymentOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.SUCCEEDED, paymentOutboxMessage.getSagaStatus()),
                () -> assertNotNull(paymentOutboxMessage.getProcessedAt()));
        verify(orderRepository).save(order);
        verify(approvalOutboxHelper).save(approvalOutboxMessage);
        verify(paymentOutboxHelper).save(paymentOutboxMessage);
        assertApprovalSagaLookup(SagaStatus.PROCESSING);
        assertPaymentSagaLookup(SagaStatus.PROCESSING);
    }

    @Test
    void rejectedRestaurantResponseStartsPaymentCompensation() {
        List<String> failures = List.of("Product is unavailable");
        Order order = orderWithStatus(OrderStatus.PAID);
        OrderApprovalOutboxMessage approvalOutboxMessage = approvalOutboxMessage(SagaStatus.PROCESSING);
        when(approvalOutboxHelper.getApprovalOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.of(approvalOutboxMessage));
        when(orderRepository.findById(new OrderId(ORDER_ID))).thenReturn(Optional.of(order));

        orderApprovalSaga.rollback(approvalResponse(OrderApprovalStatus.REJECTED, failures));

        assertAll(
                () -> assertEquals(OrderStatus.CANCELLING, order.getOrderStatus()),
                () -> assertEquals(failures, order.getFailureMessages()),
                () -> assertEquals(OrderStatus.CANCELLING, approvalOutboxMessage.getOrderStatus()),
                () -> assertEquals(SagaStatus.COMPENSATING, approvalOutboxMessage.getSagaStatus()),
                () -> assertNotNull(approvalOutboxMessage.getProcessedAt()));
        verify(orderRepository).save(order);
        verify(approvalOutboxHelper).save(approvalOutboxMessage);

        ArgumentCaptor<OrderPaymentEventPayload> payloadCaptor =
                ArgumentCaptor.forClass(OrderPaymentEventPayload.class);
        verify(paymentOutboxHelper).savePaymentOutboxMessage(
                payloadCaptor.capture(),
                eq(OrderStatus.CANCELLING),
                eq(SagaStatus.COMPENSATING),
                eq(OutboxStatus.STARTED),
                eq(SAGA_ID));
        assertAll(
                () -> assertEquals(ORDER_ID.toString(), payloadCaptor.getValue().getOrderId()),
                () -> assertEquals(CUSTOMER_ID.toString(), payloadCaptor.getValue().getCustomerId()),
                () -> assertEquals("CANCELLED", payloadCaptor.getValue().getPaymentOrderStatus()));
        verify(paymentOutboxHelper, never()).save(any(OrderPaymentOutboxMessage.class));
        assertApprovalSagaLookup(SagaStatus.PROCESSING);
    }

    @Test
    void duplicateApprovedRestaurantResponseIsIgnored() {
        when(approvalOutboxHelper.getApprovalOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.empty());

        orderApprovalSaga.process(approvalResponse(OrderApprovalStatus.APPROVED, List.of()));

        verify(approvalOutboxHelper, never()).save(any(OrderApprovalOutboxMessage.class));
        verifyNoInteractions(orderRepository, paymentOutboxHelper);
        assertApprovalSagaLookup(SagaStatus.PROCESSING);
    }

    @Test
    void duplicateRejectedRestaurantResponseIsIgnored() {
        when(approvalOutboxHelper.getApprovalOutboxMessageBySagaIdAndSagaStatus(
                SAGA_ID, SagaStatus.PROCESSING))
                .thenReturn(Optional.empty());

        orderApprovalSaga.rollback(approvalResponse(
                OrderApprovalStatus.REJECTED, List.of("Product is unavailable")));

        verify(approvalOutboxHelper, never()).save(any(OrderApprovalOutboxMessage.class));
        verifyNoInteractions(orderRepository, paymentOutboxHelper);
        assertApprovalSagaLookup(SagaStatus.PROCESSING);
    }

    private void assertApprovalSagaLookup(SagaStatus... expectedStatuses) {
        verify(approvalOutboxHelper).getApprovalOutboxMessageBySagaIdAndSagaStatus(SAGA_ID, expectedStatuses);
    }

    private void assertPaymentSagaLookup(SagaStatus... expectedStatuses) {
        verify(paymentOutboxHelper).getPaymentOutboxMessageBySagaIdAndSagaStatus(SAGA_ID, expectedStatuses);
    }

    private RestaurantApprovalResponse approvalResponse(OrderApprovalStatus approvalStatus,
                                                          List<String> failureMessages) {
        return RestaurantApprovalResponse.builder()
                .sagaId(SAGA_ID.toString())
                .orderId(ORDER_ID.toString())
                .restaurantId(RESTAURANT_ID.toString())
                .orderApprovalStatus(approvalStatus)
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

    private OrderApprovalOutboxMessage approvalOutboxMessage(SagaStatus sagaStatus) {
        return OrderApprovalOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(SAGA_ID)
                .createdAt(ZonedDateTime.now())
                .type(ORDER_SAGA_NAME)
                .payload("{}")
                .orderStatus(OrderStatus.PAID)
                .sagaStatus(sagaStatus)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
    }

    private OrderPaymentOutboxMessage paymentOutboxMessage(SagaStatus sagaStatus) {
        return OrderPaymentOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(SAGA_ID)
                .createdAt(ZonedDateTime.now())
                .type(ORDER_SAGA_NAME)
                .payload("{}")
                .orderStatus(OrderStatus.PAID)
                .sagaStatus(sagaStatus)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
    }
}
