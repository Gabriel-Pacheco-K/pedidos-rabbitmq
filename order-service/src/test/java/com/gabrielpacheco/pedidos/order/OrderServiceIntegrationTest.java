package com.gabrielpacheco.pedidos.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gabrielpacheco.pedidos.common.events.OrderCreated;
import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockRejected;
import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.Headers;
import com.gabrielpacheco.pedidos.common.messaging.Producer;
import com.gabrielpacheco.pedidos.common.test.BrokerClient;
import com.gabrielpacheco.pedidos.common.test.Infrastructure;
import com.gabrielpacheco.pedidos.common.test.TestKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OrderServiceIntegrationTest {

    private static final String JWT_SECRET = "segredo-jwt-de-teste-com-mais-de-32-bytes!!";
    private static final String DLQ = "order.events.dlq";

    private static BrokerClient broker;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry, Producer.ORDER, "order-svc", "order-dev-only");
        registry.add("app.security.jwt.secret", () -> JWT_SECRET);
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
    void cleanDlq() {
        broker.purge(DLQ);
    }

    // ─── Criação de pedidos ─────────────────────────────────────

    @Test
    void placesOrderWithServerSidePricesAndPublishesSignedEvent() throws Exception {
        // O cliente tenta mandar o próprio total: o campo é ignorado e o preço vem do catálogo.
        String body = """
                {"total": 0.01, "items": [{"sku": "TECLADO-MEC", "quantity": 1}, {"sku": "MOUSE-USB", "quantity": 2}]}""";

        JsonNode order = read(placeOrder("cliente-a", UUID.randomUUID(), body).andExpect(status().isAccepted()));

        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(new BigDecimal(order.get("total").asText())).isEqualByComparingTo("528.80");

        UUID orderId = UUID.fromString(order.get("id").asText());
        var event = broker.awaitEvent(EventType.ORDER_CREATED, OrderCreated.class, e -> e.orderId().equals(orderId));
        assertThat(event.payload().total()).isEqualByComparingTo("528.80");
        assertThat(event.payload().customerId()).isEqualTo("cliente-a");

        // A mensagem publicada tem assinatura válida, conferida só com a chave pública.
        var props = event.message().getMessageProperties();
        assertThat(TestKeys.verifier(Producer.PAYMENT).verify(Producer.ORDER, props.getMessageId(), props.getType(),
                Long.parseLong(props.getHeader(Headers.SENT_AT)), event.message().getBody(), props.getHeader(Headers.SIGNATURE))).isTrue();
    }

    @Test
    void sameIdempotencyKeyReturnsSameOrderAndRejectsDifferentContent() throws Exception {
        UUID key = UUID.randomUUID();
        String body = items("MOUSE-USB", 1);

        String first = read(placeOrder("cliente-b", key, body).andExpect(status().isAccepted())).get("id").asText();
        String replay = read(placeOrder("cliente-b", key, body).andExpect(status().isOk())).get("id").asText();
        assertThat(replay).isEqualTo(first);

        placeOrder("cliente-b", key, items("MOUSE-USB", 5)).andExpect(status().isConflict());
        // A mesma chave usada por outro cliente é independente.
        placeOrder("cliente-c", key, body).andExpect(status().isAccepted());
    }

    @Test
    void rejectsInvalidOrders() throws Exception {
        placeOrder("cliente-d", UUID.randomUUID(), items("PRODUTO-FALSO", 1)).andExpect(status().isUnprocessableEntity());
        placeOrder("cliente-d", UUID.randomUUID(), items("MOUSE-USB", 0)).andExpect(status().isBadRequest());
        placeOrder("cliente-d", UUID.randomUUID(), items("MOUSE-USB", 101)).andExpect(status().isBadRequest());
        placeOrder("cliente-d", UUID.randomUUID(), items("mouse; drop table", 1)).andExpect(status().isBadRequest());
        placeOrder("cliente-d", UUID.randomUUID(), """
                {"items": [{"sku": "MOUSE-USB", "quantity": 1}, {"sku": "MOUSE-USB", "quantity": 2}]}""")
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token("cliente-d", "orders:write", 60, JWT_SECRET, "pedidos-api"))
                        .contentType(MediaType.APPLICATION_JSON).content(items("MOUSE-USB", 1)))
                .andExpect(status().isBadRequest()); // sem Idempotency-Key
    }

    // ─── Autenticação e autorização ─────────────────────────────

    @Test
    void rejectsMissingForgedExpiredOrUnscopedTokens() throws Exception {
        String body = items("MOUSE-USB", 1);
        mvc.perform(post("/api/orders").header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        String forged = token("cliente-e", "orders:write", 60, "outro-segredo-qualquer-com-mais-de-32-bytes", "pedidos-api");
        String expired = token("cliente-e", "orders:write", -60, JWT_SECRET, "pedidos-api");
        String wrongAudience = token("cliente-e", "orders:write", 60, JWT_SECRET, "outra-api");
        String readOnly = token("cliente-e", "orders:read", 60, JWT_SECRET, "pedidos-api");

        for (String bad : List.of(forged, expired, wrongAudience)) {
            mvc.perform(post("/api/orders").header("Authorization", "Bearer " + bad).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + readOnly).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void customersCannotSeeEachOthersOrders() throws Exception {
        String id = read(placeOrder("cliente-f", UUID.randomUUID(), items("MOUSE-USB", 1))).get("id").asText();

        getOrder("cliente-f", id).andExpect(status().isOk());
        getOrder("intruso", id).andExpect(status().isNotFound());
    }

    // ─── Eventos recebidos ──────────────────────────────────────

    @Test
    void paymentApprovedConfirmsTheOrder() throws Exception {
        JsonNode order = read(placeOrder("cliente-g", UUID.randomUUID(), items("MONITOR-27", 1)));
        UUID id = UUID.fromString(order.get("id").asText());

        broker.publish(new PaymentApproved(id, new BigDecimal(order.get("total").asText())));

        awaitStatus("cliente-g", id, "CONFIRMED");
    }

    @Test
    void stockRejectionAndPaymentDeclineFinishTheOrder() throws Exception {
        UUID rejected = UUID.fromString(read(placeOrder("cliente-h", UUID.randomUUID(), items("MOUSE-USB", 1))).get("id").asText());
        UUID declined = UUID.fromString(read(placeOrder("cliente-h", UUID.randomUUID(), items("MOUSE-USB", 1))).get("id").asText());

        broker.publish(new StockRejected(rejected, "Estoque insuficiente para MOUSE-USB"));
        broker.publish(new PaymentDeclined(declined, "Limite excedido"));

        awaitStatus("cliente-h", rejected, "REJECTED");
        awaitStatus("cliente-h", declined, "CANCELLED");
    }

    @Test
    void forgedEventsGoToTheDlqAndDoNotChangeTheOrder() throws Exception {
        JsonNode order = read(placeOrder("cliente-i", UUID.randomUUID(), items("NOTEBOOK-15", 1)));
        UUID id = UUID.fromString(order.get("id").asText());
        BigDecimal total = new BigDecimal(order.get("total").asText());

        // 1. O serviço de estoque foi invadido e assina um "pagamento aprovado" com a chave dele.
        broker.publish(new PaymentApproved(id, total), Producer.PAYMENT, Producer.INVENTORY, UUID.randomUUID(), Instant.now(), null);
        // 2. Assinatura correta, mas o corpo foi trocado no caminho.
        broker.publish(new PaymentApproved(id, total), Producer.PAYMENT, Producer.PAYMENT, UUID.randomUUID(), Instant.now(),
                "{\"orderId\":\"" + id + "\",\"amount\":1.00}");
        // 3. Mensagem legítima, porém antiga demais.
        broker.publish(new PaymentApproved(id, total), Producer.PAYMENT, Producer.PAYMENT, UUID.randomUUID(), Instant.now().minus(Duration.ofDays(30)), null);
        // 4. Assinada corretamente, mas aprovando um valor diferente do total do pedido.
        broker.publish(new PaymentApproved(id, new BigDecimal("1.00")));
        // 5. Sem assinatura nenhuma.
        broker.sendConfirmed("payment.approved", MessageBuilder.withBody(("{\"orderId\":\"" + id + "\",\"amount\":" + total + "}").getBytes())
                .setMessageId(UUID.randomUUID().toString()).setType("payment.approved").setHeader(Headers.PRODUCER, "payment").build());

        broker.awaitMessageCount(DLQ, 5);
        getOrder("cliente-i", id.toString()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void duplicateDeliveryIsProcessedOnlyOnce() throws Exception {
        UUID id = UUID.fromString(read(placeOrder("cliente-j", UUID.randomUUID(), items("MOUSE-USB", 1))).get("id").asText());
        UUID messageId = UUID.randomUUID();
        StockRejected event = new StockRejected(id, "Sem estoque");

        broker.publish(event, Producer.INVENTORY, Producer.INVENTORY, messageId, Instant.now(), null);
        awaitStatus("cliente-j", id, "REJECTED");

        // Reentrega da mesma mensagem: se fosse processada de novo, a transição inválida mandaria para a DLQ.
        broker.publish(event, Producer.INVENTORY, Producer.INVENTORY, messageId, Instant.now(), null);
        Thread.sleep(1_500);
        assertThat(broker.messageCount(DLQ)).isZero();
    }

    // ─── Permissões no broker ───────────────────────────────────

    @Test
    void orderServiceUserCannotPublishOtherServicesEvents() {
        // Mesmo com as credenciais do serviço de pedidos, o broker recusa publicar "payment.*".
        try (BrokerClient asOrderService = new BrokerClient("order-svc", "order-dev-only")) {
            assertThatThrownBy(() -> asOrderService.sendConfirmed("payment.approved",
                    MessageBuilder.withBody("{}".getBytes()).setMessageId(UUID.randomUUID().toString()).build()))
                    .hasStackTraceContaining("ACCESS_REFUSED");
        }
    }

    // ─── Auxiliares ─────────────────────────────────────────────

    private ResultActions placeOrder(String customer, UUID key, String body) throws Exception {
        return mvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + token(customer, "orders:write orders:read", 300, JWT_SECRET, "pedidos-api"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getOrder(String customer, String id) throws Exception {
        return mvc.perform(get("/api/orders/" + id)
                .header("Authorization", "Bearer " + token(customer, "orders:read", 300, JWT_SECRET, "pedidos-api")));
    }

    private void awaitStatus(String customer, UUID id, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                getOrder(customer, id.toString()).andExpect(jsonPath("$.status").value(expected)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String items(String sku, int quantity) {
        return "{\"items\": [{\"sku\": \"" + sku + "\", \"quantity\": " + quantity + "}]}";
    }

    static String token(String subject, String scope, long secondsValid, String secret, String audience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("pedidos-auth")
                .audience(audience)
                .claim("scope", scope)
                .issueTime(Date.from(now.minusSeconds(120)))
                .expirationTime(Date.from(now.plusSeconds(secondsValid)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes()));
        return jwt.serialize();
    }
}
