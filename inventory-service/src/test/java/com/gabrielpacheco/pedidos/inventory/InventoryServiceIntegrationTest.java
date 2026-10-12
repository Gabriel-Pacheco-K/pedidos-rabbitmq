package com.gabrielpacheco.pedidos.inventory;

import com.gabrielpacheco.pedidos.common.events.OrderCreated;
import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockRejected;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class InventoryServiceIntegrationTest {

    private static final String DLQ = "inventory.events.dlq";

    private static BrokerClient broker;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry, Producer.INVENTORY, "inventory-svc", "inventory-dev-only");
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
    void reservesStockAndPublishesStockReserved() {
        setStock("TECLADO-MEC", 50);
        UUID orderId = UUID.randomUUID();

        broker.publish(order(orderId, "349.00", new OrderCreated.Item("TECLADO-MEC", 3)));

        StockReserved reserved = broker.awaitEvent(EventType.STOCK_RESERVED, StockReserved.class, e -> e.orderId().equals(orderId)).payload();
        assertThat(reserved.amount()).isEqualByComparingTo("349.00");
        assertThat(stock("TECLADO-MEC")).containsExactly(47, 3);
    }

    @Test
    void rejectsWholeOrderWhenAnyItemIsMissing() {
        setStock("NOTEBOOK-15", 10);
        setStock("MOUSE-USB", 200);
        UUID orderId = UUID.randomUUID();

        broker.publish(order(orderId, "1000.00", new OrderCreated.Item("MOUSE-USB", 1), new OrderCreated.Item("NOTEBOOK-15", 11)));

        StockRejected rejected = broker.awaitEvent(EventType.STOCK_REJECTED, StockRejected.class, e -> e.orderId().equals(orderId)).payload();
        assertThat(rejected.reason()).contains("NOTEBOOK-15");
        // Tudo ou nada: o mouse, que tinha estoque, também não foi separado.
        assertThat(stock("MOUSE-USB")).containsExactly(200, 0);
        assertThat(stock("NOTEBOOK-15")).containsExactly(10, 0);
    }

    @Test
    void paymentDeclinedReturnsItemsAndApprovedCommitsThem() {
        setStock("MONITOR-27", 20);
        UUID declined = UUID.randomUUID();
        UUID approved = UUID.randomUUID();

        broker.publish(order(declined, "1899.00", new OrderCreated.Item("MONITOR-27", 1)));
        broker.publish(order(approved, "1899.00", new OrderCreated.Item("MONITOR-27", 1)));
        broker.awaitEvent(EventType.STOCK_RESERVED, StockReserved.class, e -> e.orderId().equals(declined));
        broker.awaitEvent(EventType.STOCK_RESERVED, StockReserved.class, e -> e.orderId().equals(approved));
        assertThat(stock("MONITOR-27")).containsExactly(18, 2);

        broker.publish(new PaymentDeclined(declined, "Limite excedido"));
        broker.publish(new PaymentApproved(approved, new BigDecimal("1899.00")));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stock("MONITOR-27")).containsExactly(19, 0));
    }

    @Test
    void concurrentOrdersNeverOversell() throws Exception {
        setStock("HEADSET-PRO", 3);
        List<UUID> orders = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 10; i++) {
            UUID orderId = UUID.randomUUID();
            orders.add(orderId);
            pool.submit(() -> broker.publish(order(orderId, "459.90", new OrderCreated.Item("HEADSET-PRO", 1))));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        Set<UUID> ids = new HashSet<>(orders);
        Set<UUID> reserved = new HashSet<>();
        Set<UUID> rejected = new HashSet<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            broker.collect(EventType.STOCK_RESERVED, StockReserved.class, e -> ids.contains(e.orderId()), Duration.ofMillis(300))
                    .forEach(e -> reserved.add(e.orderId()));
            broker.collect(EventType.STOCK_REJECTED, StockRejected.class, e -> ids.contains(e.orderId()), Duration.ofMillis(300))
                    .forEach(e -> rejected.add(e.orderId()));
            return reserved.size() + rejected.size() == 10;
        });

        assertThat(reserved).hasSize(3);
        assertThat(rejected).hasSize(7);
        assertThat(stock("HEADSET-PRO")).containsExactly(0, 3);
    }

    @Test
    void forgedOrderGoesToDlqWithoutTouchingStock() {
        setStock("MOUSE-USB", 200);
        // Pagamento invadido tenta criar um pedido assinando com a própria chave.
        broker.publish(order(UUID.randomUUID(), "89.90", new OrderCreated.Item("MOUSE-USB", 100)),
                Producer.ORDER, Producer.PAYMENT, UUID.randomUUID(), Instant.now(), null);
        // Mensagem com assinatura válida, mas com quantidade fora do permitido.
        broker.publish(order(UUID.randomUUID(), "89.90", new OrderCreated.Item("MOUSE-USB", 1000)));

        broker.awaitMessageCount(DLQ, 2);
        assertThat(stock("MOUSE-USB")).containsExactly(200, 0);
    }

    @Test
    void inventoryUserCannotPublishOrders() {
        try (BrokerClient asInventory = new BrokerClient("inventory-svc", "inventory-dev-only")) {
            assertThatThrownBy(() -> asInventory.sendConfirmed("order.created",
                    MessageBuilder.withBody("{}".getBytes()).setMessageId(UUID.randomUUID().toString()).build()))
                    .hasStackTraceContaining("ACCESS_REFUSED");
        }
    }

    private static OrderCreated order(UUID id, String total, OrderCreated.Item... items) {
        return new OrderCreated(id, "cliente-teste", List.of(items), new BigDecimal(total));
    }

    private void setStock(String sku, int available) {
        jdbc.update("update stock set available = ?, reserved = 0 where sku = ?", available, sku);
    }

    private List<Integer> stock(String sku) {
        return jdbc.queryForObject("select available, reserved from stock where sku = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2)), sku);
    }
}
