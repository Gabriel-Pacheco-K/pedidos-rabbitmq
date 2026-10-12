package com.gabrielpacheco.pedidos.payment;

import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockReserved;
import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.Producer;
import com.gabrielpacheco.pedidos.common.test.BrokerClient;
import com.gabrielpacheco.pedidos.common.test.Infrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentServiceIntegrationTest {

    private static final String DLQ = "payment.events.dlq";

    private static BrokerClient broker;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry, Producer.PAYMENT, "payment-svc", "payment-dev-only");
        registry.add("app.messaging.relay-interval", () -> "100");
    }

    @BeforeAll
    static void connect() {
        broker = new BrokerClient();
    }

    @AfterAll
    static void disconnect() {
        broker.close();
    }

    @BeforeEach
    void reset() {
        broker.purge(DLQ);
    }

    @Test
    void approvesWithinLimitAndDeclinesAbove() {
        UUID small = UUID.randomUUID();
        UUID big = UUID.randomUUID();

        broker.publish(new StockReserved(small, "cliente", new BigDecimal("4999.99")));
        broker.publish(new StockReserved(big, "cliente", new BigDecimal("5000.01")));

        PaymentApproved approved = broker.awaitEvent(EventType.PAYMENT_APPROVED, PaymentApproved.class, e -> e.orderId().equals(small)).payload();
        PaymentDeclined declined = broker.awaitEvent(EventType.PAYMENT_DECLINED, PaymentDeclined.class, e -> e.orderId().equals(big)).payload();
        assertThat(approved.amount()).isEqualByComparingTo("4999.99");
        assertThat(declined.reason()).contains("limite");
    }

    @Test
    void neverChargesTwiceForTheSameOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        StockReserved event = new StockReserved(orderId, "cliente", new BigDecimal("100.00"));
        UUID messageId = UUID.randomUUID();

        // A mesma mensagem entregue duas vezes, e ainda uma terceira com outro id para o mesmo pedido.
        broker.publish(event, Producer.INVENTORY, Producer.INVENTORY, messageId, Instant.now(), null);
        broker.publish(event, Producer.INVENTORY, Producer.INVENTORY, messageId, Instant.now(), null);
        broker.publish(event);

        assertThat(broker.collect(EventType.PAYMENT_APPROVED, PaymentApproved.class, e -> e.orderId().equals(orderId), Duration.ofSeconds(4)))
                .hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId)).isEqualTo(1);
        assertThat(broker.messageCount(DLQ)).isZero();
    }

    @Test
    void forgedStockReservationIsRejected() {
        UUID orderId = UUID.randomUUID();
        // O serviço de pedidos foi invadido e tenta pular a etapa de estoque assinando ele mesmo.
        broker.publish(new StockReserved(orderId, "cliente", new BigDecimal("10.00")),
                Producer.INVENTORY, Producer.ORDER, UUID.randomUUID(), Instant.now(), null);

        broker.awaitMessageCount(DLQ, 1);
        assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId)).isZero();
    }

    @Test
    void paymentUserCannotPublishStockEvents() {
        try (BrokerClient asPayment = new BrokerClient("payment-svc", "payment-dev-only")) {
            assertThatThrownBy(() -> asPayment.sendConfirmed("stock.reserved",
                    MessageBuilder.withBody("{}".getBytes()).setMessageId(UUID.randomUUID().toString()).build()))
                    .hasStackTraceContaining("ACCESS_REFUSED");
        }
    }
}
