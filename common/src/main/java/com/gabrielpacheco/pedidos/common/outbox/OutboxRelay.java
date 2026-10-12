package com.gabrielpacheco.pedidos.common.outbox;

import com.gabrielpacheco.pedidos.common.config.MessagingProperties;
import com.gabrielpacheco.pedidos.common.messaging.Headers;
import com.gabrielpacheco.pedidos.common.messaging.MessageSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lê o outbox e publica no RabbitMQ, um evento por transação.
 *
 * <p>O evento só é marcado como publicado depois que o broker confirma o recebimento
 * (publisher confirm). Se a confirmação não vier, a transação é desfeita e o evento é
 * tentado de novo no próximo ciclo. A entrega é "pelo menos uma vez": uma mensagem pode
 * chegar duplicada, e por isso todo consumidor é idempotente.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_PER_RUN = 100;
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final MessageSigner signer;
    private final MessagingProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, RabbitTemplate rabbitTemplate, MessageSigner signer,
                       MessagingProperties properties, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.signer = signer;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.messaging.relay-interval}")
    public void publishPending() {
        for (int i = 0; i < MAX_PER_RUN; i++) {
            AtomicReference<Long> current = new AtomicReference<>();
            Boolean published;
            try {
                published = transactions.execute(status -> repository.lockNextPending()
                        .map(event -> {
                            current.set(event.getId());
                            return publish(event);
                        })
                        .orElse(false));
            } catch (RuntimeException ex) {
                // Broker fora do ar ou sem confirmação: a transação já foi desfeita e a trava
                // liberada. Só então registra a falha, numa transação nova, e para o ciclo.
                log.warn("Falha ao publicar evento do outbox: {}", ex.getMessage());
                if (current.get() != null) {
                    transactions.executeWithoutResult(s -> repository.recordFailure(current.get(), truncate(ex.getMessage())));
                }
                return;
            }
            if (!Boolean.TRUE.equals(published)) {
                return;
            }
        }
    }

    private boolean publish(OutboxEvent event) {
        rabbitTemplate.invoke(ops -> {
            ops.send(properties.exchange(), event.getRoutingKey(), toMessage(event));
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
        event.markPublished(clock.instant());
        return true;
    }

    private Message toMessage(OutboxEvent event) {
        byte[] body = event.getPayload().getBytes(StandardCharsets.UTF_8);
        long sentAt = clock.millis();
        String messageId = event.getMessageId().toString();

        return MessageBuilder.withBody(body)
                .setMessageId(messageId)
                .setType(event.getRoutingKey())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setTimestamp(new Date(sentAt))
                .setHeader(Headers.PRODUCER, properties.self().key())
                .setHeader(Headers.SENT_AT, Long.toString(sentAt))
                .setHeader(Headers.SIGNATURE, signer.sign(messageId, event.getRoutingKey(), sentAt, body))
                .build();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "erro desconhecido";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
