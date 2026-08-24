package com.food.ordering.system.payment.service.domain;

import com.food.ordering.system.domain.valueobject.PaymentOrderStatus;
import com.food.ordering.system.domain.valueobject.PaymentStatus;
import com.food.ordering.system.outbox.OutboxStatus;
import com.food.ordering.system.payment.service.dataaccess.creditentry.repository.CreditEntryJpaRepository;
import com.food.ordering.system.payment.service.dataaccess.credithistory.repository.CreditHistoryJpaRepository;
import com.food.ordering.system.payment.service.dataaccess.outbox.repository.OrderOutboxJpaRepository;
import com.food.ordering.system.payment.service.dataaccess.payment.repository.PaymentJpaRepository;
import com.food.ordering.system.payment.service.domain.dto.PaymentRequest;
import com.food.ordering.system.payment.service.domain.ports.input.message.listener.PaymentRequestMessageListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.food.ordering.system.saga.order.SagaConstants.ORDER_SAGA_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        properties = {
                "kafka-consumer-config.auto-startup=false",
                "payment-service.outbox-scheduler-initial-delay=3600000",
                "payment-service.outbox-scheduler-fixed-rate=3600000"
        })
class PaymentRequestMessageListenerTest {

    private static final UUID CUSTOMER_ID =
            UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb41");
    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:14-alpine")
                    .withDatabaseName("postgres")
                    .withUsername("postgres")
                    .withPassword("test-password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentRequestMessageListenerTest::paymentJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String paymentJdbcUrl() {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=payment&stringtype=unspecified";
    }

    @Autowired
    private PaymentRequestMessageListener paymentRequestMessageListener;
    @Autowired
    private OrderOutboxJpaRepository orderOutboxJpaRepository;
    @Autowired
    private PaymentJpaRepository paymentJpaRepository;
    @Autowired
    private CreditEntryJpaRepository creditEntryJpaRepository;
    @Autowired
    private CreditHistoryJpaRepository creditHistoryJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetMutablePaymentState() {
        jdbcTemplate.update("DELETE FROM payment.order_outbox");
        jdbcTemplate.update("DELETE FROM payment.payments");
        jdbcTemplate.update("DELETE FROM payment.credit_history WHERE id NOT IN (?, ?, ?, ?)",
                UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb23"),
                UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb24"),
                UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb25"),
                UUID.fromString("d215b5f8-0249-4dc5-89a3-51fd148cfb26"));
        jdbcTemplate.update(
                "UPDATE payment.credit_entry SET total_credit_amount = 500.00 WHERE customer_id = ?",
                CUSTOMER_ID);
    }

    @Test
    void duplicatePaymentIsRejectedByOutboxConstraintWithoutRepeatingBusinessEffects() {
        UUID sagaId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentRequest request = paymentRequest(sagaId, orderId);

        paymentRequestMessageListener.completePayment(request);

        assertThrows(DataAccessException.class,
                () -> paymentRequestMessageListener.completePayment(request));

        assertEquals(1, paymentJpaRepository.count());
        assertEquals(1, orderOutboxJpaRepository.count());
        assertTrue(paymentJpaRepository.findByOrderId(orderId).isPresent());
        assertEquals(new BigDecimal("400.00"), creditEntryJpaRepository.findByCustomerId(CUSTOMER_ID)
                .orElseThrow()
                .getTotalCreditAmount());
        assertEquals(4, creditHistoryJpaRepository.findByCustomerId(CUSTOMER_ID)
                .orElseThrow()
                .size());
        assertTrue(orderOutboxJpaRepository
                .findByTypeAndSagaIdAndPaymentStatusAndOutboxStatus(
                        ORDER_SAGA_NAME, sagaId, PaymentStatus.COMPLETED, OutboxStatus.STARTED)
                .isPresent());
    }

    private PaymentRequest paymentRequest(UUID sagaId, UUID orderId) {
        return PaymentRequest.builder()
                .id(UUID.randomUUID().toString())
                .sagaId(sagaId.toString())
                .orderId(orderId.toString())
                .paymentOrderStatus(PaymentOrderStatus.PENDING)
                .customerId(CUSTOMER_ID.toString())
                .price(PRICE)
                .createdAt(Instant.parse("2026-08-24T10:00:00Z"))
                .build();
    }
}
