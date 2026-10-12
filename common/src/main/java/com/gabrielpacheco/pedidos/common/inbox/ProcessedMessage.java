package com.gabrielpacheco.pedidos.common.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de que uma mensagem já foi processada. A chave primária é o id da mensagem:
 * se a mesma mensagem chegar duas vezes, o banco impede o segundo registro.
 */
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "routing_key", nullable = false, length = 60)
    private String routingKey;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public ProcessedMessage(UUID messageId, String routingKey, Instant processedAt) {
        this.messageId = messageId;
        this.routingKey = routingKey;
        this.processedAt = processedAt;
    }
}
