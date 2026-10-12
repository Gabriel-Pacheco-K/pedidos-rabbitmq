package com.gabrielpacheco.pedidos.common.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gabrielpacheco.pedidos.common.config.MessagingProperties;
import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.test.TestKeys;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncomingMessageReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final Set<EventType> ACCEPTS_PAYMENT = Set.of(EventType.PAYMENT_APPROVED);

    private final ObjectMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final IncomingMessageReader reader = new IncomingMessageReader(
            TestKeys.verifier(Producer.ORDER),
            mapper,
            Validation.buildDefaultValidatorFactory().getValidator(),
            new MessagingProperties("pedidos.events", Producer.ORDER, TestKeys.privateKey(Producer.ORDER), TestKeys.publicKeys(), Duration.ofDays(7), Duration.ofSeconds(1)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID orderId = UUID.randomUUID();

    @Test
    void acceptsCorrectlySignedMessage() {
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":150.00}";
        IncomingEvent event = reader.read(message("payment.approved", Producer.PAYMENT, Producer.PAYMENT, body, body, NOW), ACCEPTS_PAYMENT);

        assertThat(event.payload(PaymentApproved.class).amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void rejectsTamperedBody() {
        String signed = "{\"orderId\":\"" + orderId + "\",\"amount\":150.00}";
        String tampered = "{\"orderId\":\"" + orderId + "\",\"amount\":1.00}";

        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.PAYMENT, Producer.PAYMENT, signed, tampered, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("Assinatura inválida");
    }

    @Test
    void rejectsEventSignedByAnotherService() {
        // O serviço de estoque foi invadido e tenta aprovar um pagamento com a própria chave.
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":150.00}";

        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.PAYMENT, Producer.INVENTORY, body, body, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("Assinatura inválida");
        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.INVENTORY, Producer.INVENTORY, body, body, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("não pode publicar");
    }

    @Test
    void rejectsUnexpectedOrUnknownTypes() {
        String body = "{\"orderId\":\"" + orderId + "\",\"customerId\":\"c1\",\"amount\":10}";

        assertThatThrownBy(() -> reader.read(message("stock.reserved", Producer.INVENTORY, Producer.INVENTORY, body, body, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("inesperado");
        assertThatThrownBy(() -> reader.read(rawMessage("admin.delete-all", body), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void rejectsMessagesOutsideTheTimeWindow() {
        String body = "{\"orderId\":\"" + orderId + "\",\"amount\":150.00}";

        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.PAYMENT, Producer.PAYMENT, body, body, NOW.minus(Duration.ofDays(8))), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("janela de tempo");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidValuesEvenWhenSigned() {
        String extraField = "{\"orderId\":\"" + orderId + "\",\"amount\":150.00,\"admin\":true}";
        String negative = "{\"orderId\":\"" + orderId + "\",\"amount\":-5}";

        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.PAYMENT, Producer.PAYMENT, extraField, extraField, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("Corpo inválido");
        assertThatThrownBy(() -> reader.read(message("payment.approved", Producer.PAYMENT, Producer.PAYMENT, negative, negative, NOW), ACCEPTS_PAYMENT))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void validatesNestedFields() {
        IncomingMessageReader stockReader = new IncomingMessageReader(TestKeys.verifier(Producer.PAYMENT), mapper,
                Validation.buildDefaultValidatorFactory().getValidator(),
                new MessagingProperties("pedidos.events", Producer.PAYMENT, TestKeys.privateKey(Producer.PAYMENT), TestKeys.publicKeys(), Duration.ofDays(7), Duration.ofSeconds(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        String blankCustomer = "{\"orderId\":\"" + orderId + "\",\"customerId\":\"\",\"amount\":10}";

        assertThatThrownBy(() -> stockReader.read(message("stock.reserved", Producer.INVENTORY, Producer.INVENTORY, blankCustomer, blankCustomer, NOW), Set.of(EventType.STOCK_RESERVED)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    void publicKeyAloneCannotSign() {
        // Um consumidor só tem chaves públicas: mesmo invadido, não consegue assinar como outro serviço.
        assertThatThrownBy(() -> TestKeys.verifier(Producer.ORDER).sign("id", "payment.approved", 1L, new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chave privada");
    }

    @Test
    void rejectsGarbageKeys() {
        assertThatThrownBy(() -> new MessageSigner(Producer.ORDER, "nao-e-uma-chave", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private Message message(String type, Producer declared, Producer signedWith, String signedBody, String sentBody, Instant sentAt) {
        String id = UUID.randomUUID().toString();
        long millis = sentAt.toEpochMilli();
        return MessageBuilder.withBody(sentBody.getBytes(StandardCharsets.UTF_8))
                .setMessageId(id)
                .setType(type)
                .setHeader(Headers.PRODUCER, declared.key())
                .setHeader(Headers.SENT_AT, Long.toString(millis))
                .setHeader(Headers.SIGNATURE, TestKeys.signer(signedWith).sign(id, type, millis, signedBody.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private Message rawMessage(String type, String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).setMessageId(UUID.randomUUID().toString()).setType(type).build();
    }
}
