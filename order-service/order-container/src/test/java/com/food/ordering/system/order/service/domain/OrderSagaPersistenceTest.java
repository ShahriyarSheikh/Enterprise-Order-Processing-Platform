package com.food.ordering.system.order.service.domain;

import com.food.ordering.system.domain.valueobject.CustomerId;
import com.food.ordering.system.domain.valueobject.Money;
import com.food.ordering.system.domain.valueobject.OrderId;
import com.food.ordering.system.domain.valueobject.OrderStatus;
import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.domain.valueobject.ProductId;
import com.food.ordering.system.domain.valueobject.RestaurantId;
import com.food.ordering.system.order.service.dataaccess.outbox.payment.entity.PaymentOutboxEntity;
import com.food.ordering.system.order.service.dataaccess.outbox.payment.repository.PaymentOutboxJpaRepository;
import com.food.ordering.system.order.service.dataaccess.outbox.restaurantapproval.entity.ApprovalOutboxEntity;
import com.food.ordering.system.order.service.dataaccess.outbox.restaurantapproval.repository.ApprovalOutboxJpaRepository;
import com.food.ordering.system.order.service.domain.dto.message.PaymentResponse;
import com.food.ordering.system.order.service.domain.entity.Order;
import com.food.ordering.system.order.service.domain.entity.OrderItem;
import com.food.ordering.system.order.service.domain.entity.Product;
import com.food.ordering.system.order.service.domain.outbox.model.payment.OrderPaymentOutboxMessage;
import com.food.ordering.system.order.service.domain.outbox.scheduler.payment.PaymentOutboxHelper;
import com.food.ordering.system.order.service.domain.ports.input.message.listener.payment.PaymentResponseMessageListener;
import com.food.ordering.system.order.service.domain.ports.output.repository.OrderRepository;
import com.food.ordering.system.order.service.domain.valueobject.OrderItemId;
import com.food.ordering.system.order.service.domain.valueobject.StreetAddress;
import com.food.ordering.system.order.service.domain.valueobject.TrackingId;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.saga.SagaStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.food.ordering.system.saga.order.SagaConstants.ORDER_SAGA_NAME;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = OrderServiceApplication.class,
        properties = {
                "kafka-consumer-config.auto-startup=false",
                "order-service.outbox-scheduler-initial-delay=3600000",
                "order-service.outbox-scheduler-fixed-rate=3600000"
        })
class OrderSagaPersistenceTest {

    private static final UUID CUSTOMER_ID =
            UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb41");
    private static final UUID RESTAURANT_ID =
            UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb45");
    private static final UUID PRODUCT_ID =
            UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb48");
    private static final ZonedDateTime CREATED_AT =
            ZonedDateTime.of(2026, 8, 24, 10, 0, 0, 0, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:14-alpine")
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("test-password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OrderSagaPersistenceTest::orderJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String orderJdbcUrl() {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=order&stringtype=unspecified";
    }

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentOutboxHelper paymentOutboxHelper;
    @Autowired
    private PaymentResponseMessageListener paymentResponseMessageListener;
    @Autowired
    private PaymentOutboxJpaRepository paymentOutboxJpaRepository;
    @Autowired
    private ApprovalOutboxJpaRepository approvalOutboxJpaRepository;

    @BeforeEach
    void resetOrderState() {
        jdbcTemplate.execute("TRUNCATE TABLE " +
                "\"order\".restaurant_approval_outbox, " +
                "\"order\".payment_outbox, " +
                "\"order\".order_items, " +
                "\"order\".order_address, " +
                "\"order\".orders CASCADE");
    }

    @Test
    void flywayCreatesOrderSchemaAndOutboxTables() {
        List<String> appliedVersions = Arrays.stream(flyway.info().applied())
                .filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion())
                .toList();
        Integer outboxTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'order' " +
                        "AND table_name IN ('payment_outbox', 'restaurant_approval_outbox')",
                Integer.class);

        assertAll(
                () -> assertEquals(List.of("1", "2"), appliedVersions),
                () -> assertEquals(2, outboxTableCount));
    }

    @Test
    void completedPaymentPersistsSagaTransitionAndIgnoresDuplicateResponse() {
        UUID sagaId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        orderRepository.save(pendingOrder(orderId));
        paymentOutboxHelper.save(paymentOutbox(sagaId));
        PaymentResponse response = completedPaymentResponse(sagaId, orderId);

        paymentResponseMessageListener.paymentCompleted(response);

        PaymentOutboxEntity paymentOutboxAfterFirstDelivery = paymentOutboxJpaRepository
                .findByTypeAndSagaIdAndSagaStatusIn(
                        ORDER_SAGA_NAME, sagaId, List.of(SagaStatus.PROCESSING))
                .orElseThrow();
        ApprovalOutboxEntity approvalOutboxAfterFirstDelivery = approvalOutboxJpaRepository
                .findByTypeAndSagaIdAndSagaStatusIn(
                        ORDER_SAGA_NAME, sagaId, List.of(SagaStatus.PROCESSING))
                .orElseThrow();

        assertAll(
                () -> assertEquals(OrderStatus.PAID, persistedOrderStatus(orderId)),
                () -> assertEquals(OutboxStatus.COMPLETED,
                        paymentOutboxAfterFirstDelivery.getOutboxStatus()),
                () -> assertNotNull(paymentOutboxAfterFirstDelivery.getProcessedAt()),
                () -> assertEquals(OutboxStatus.STARTED,
                        approvalOutboxAfterFirstDelivery.getOutboxStatus()),
                () -> assertEquals(1, paymentOutboxJpaRepository.count()),
                () -> assertEquals(1, approvalOutboxJpaRepository.count()));

        int paymentVersion = paymentOutboxAfterFirstDelivery.getVersion();
        int approvalVersion = approvalOutboxAfterFirstDelivery.getVersion();

        paymentResponseMessageListener.paymentCompleted(response);

        assertAll(
                () -> assertEquals(OrderStatus.PAID, persistedOrderStatus(orderId)),
                () -> assertEquals(1, paymentOutboxJpaRepository.count()),
                () -> assertEquals(1, approvalOutboxJpaRepository.count()),
                () -> assertEquals(paymentVersion,
                        paymentOutboxJpaRepository.findById(paymentOutboxAfterFirstDelivery.getId())
                                .orElseThrow().getVersion()),
                () -> assertEquals(approvalVersion,
                        approvalOutboxJpaRepository.findById(approvalOutboxAfterFirstDelivery.getId())
                                .orElseThrow().getVersion()));
    }

    private OrderStatus persistedOrderStatus(UUID orderId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT order_status::text FROM \"order\".orders WHERE id = ?",
                String.class,
                orderId);
        return OrderStatus.valueOf(status);
    }

    private Order pendingOrder(UUID orderId) {
        Money unitPrice = new Money(new BigDecimal("50.00"));
        return Order.builder()
                .orderId(new OrderId(orderId))
                .customerId(new CustomerId(CUSTOMER_ID))
                .restaurantId(new RestaurantId(RESTAURANT_ID))
                .deliveryAddress(new StreetAddress(
                        UUID.randomUUID(), "Alexanderplatz 1", "10178", "Berlin"))
                .price(new Money(new BigDecimal("100.00")))
                .items(List.of(OrderItem.builder()
                        .orderItemId(new OrderItemId(1L))
                        .product(new Product(new ProductId(PRODUCT_ID), "Demo product", unitPrice))
                        .quantity(2)
                        .price(unitPrice)
                        .subTotal(new Money(new BigDecimal("100.00")))
                        .build()))
                .trackingId(new TrackingId(UUID.randomUUID()))
                .orderStatus(OrderStatus.PENDING)
                .failureMessages(new ArrayList<>())
                .build();
    }

    private OrderPaymentOutboxMessage paymentOutbox(UUID sagaId) {
        return OrderPaymentOutboxMessage.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .createdAt(CREATED_AT)
                .type(ORDER_SAGA_NAME)
                .payload("{}")
                .sagaStatus(SagaStatus.STARTED)
                .orderStatus(OrderStatus.PENDING)
                .outboxStatus(OutboxStatus.COMPLETED)
                .build();
    }

    private PaymentResponse completedPaymentResponse(UUID sagaId, UUID orderId) {
        return PaymentResponse.builder()
                .id(UUID.randomUUID().toString())
                .sagaId(sagaId.toString())
                .orderId(orderId.toString())
                .paymentId(UUID.randomUUID().toString())
                .customerId(CUSTOMER_ID.toString())
                .price(new BigDecimal("100.00"))
                .createdAt(Instant.parse("2026-08-24T10:00:05Z"))
                .paymentStatus(PaymentStatus.COMPLETED)
                .failureMessages(List.of())
                .build();
    }
}
