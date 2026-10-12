package com.gabrielpacheco.pedidos.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gabrielpacheco.pedidos.common.config.MessagingProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Porta de entrada de toda mensagem. Nada é processado antes de passar, nesta ordem, por:
 * <ol>
 *   <li>tipo conhecido e esperado por este consumidor;</li>
 *   <li>produtor declarado igual ao produtor legítimo daquele tipo;</li>
 *   <li>assinatura Ed25519 válida com a chave pública desse produtor;</li>
 *   <li>mensagem dentro da idade máxima (reenvios mais recentes são barrados pelo inbox, que rejeita ids repetidos);</li>
 *   <li>JSON estrito no formato do evento e regras de validação.</li>
 * </ol>
 * A assinatura é verificada sobre os bytes brutos, antes de qualquer desserialização.
 */
public class IncomingMessageReader {

    private final MessageSigner signer;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Duration maxAge;
    private final Clock clock;

    public IncomingMessageReader(MessageSigner signer, ObjectMapper objectMapper, Validator validator,
                                 MessagingProperties properties, Clock clock) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.maxAge = properties.maxMessageAge();
        this.clock = clock;
    }

    public IncomingEvent read(Message message, Set<EventType> accepted) {
        MessageProperties props = message.getMessageProperties();

        EventType type = EventType.fromRoutingKey(nullToEmpty(props.getType()))
                .filter(accepted::contains)
                .orElseThrow(() -> new InvalidMessageException("Tipo de evento inesperado: " + props.getType()));

        String declaredProducer = header(props, Headers.PRODUCER);
        if (!type.producer().key().equals(declaredProducer)) {
            throw new InvalidMessageException("Produtor '" + declaredProducer + "' não pode publicar " + type.routingKey());
        }

        String messageId = props.getMessageId();
        UUID id = parseId(messageId);
        long sentAt = parseSentAt(header(props, Headers.SENT_AT));

        if (!signer.verify(type.producer(), messageId, type.routingKey(), sentAt, message.getBody(), header(props, Headers.SIGNATURE))) {
            throw new InvalidMessageException("Assinatura inválida na mensagem " + messageId);
        }

        Duration age = Duration.between(Instant.ofEpochMilli(sentAt), clock.instant());
        if (age.compareTo(maxAge) > 0 || age.compareTo(maxAge.negated()) < 0) {
            throw new InvalidMessageException("Mensagem " + messageId + " fora da janela de tempo aceita (" + age + ")");
        }

        Record payload = parse(message.getBody(), type);
        return new IncomingEvent(id, type, payload);
    }

    private Record parse(byte[] body, EventType type) {
        Record payload;
        try {
            payload = objectMapper.readValue(body, type.payloadType());
        } catch (IOException ex) {
            throw new InvalidMessageException("Corpo inválido para " + type.routingKey(), ex);
        }
        Set<ConstraintViolation<Record>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new InvalidMessageException("Evento " + type.routingKey() + " inválido: " + details);
        }
        return payload;
    }

    private static UUID parseId(String messageId) {
        try {
            return UUID.fromString(nullToEmpty(messageId));
        } catch (IllegalArgumentException ex) {
            throw new InvalidMessageException("messageId ausente ou inválido");
        }
    }

    private static long parseSentAt(String value) {
        try {
            return Long.parseLong(nullToEmpty(value));
        } catch (NumberFormatException ex) {
            throw new InvalidMessageException("Cabeçalho " + Headers.SENT_AT + " ausente ou inválido");
        }
    }

    private static String header(MessageProperties props, String name) {
        Object value = props.getHeaders().get(name);
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
