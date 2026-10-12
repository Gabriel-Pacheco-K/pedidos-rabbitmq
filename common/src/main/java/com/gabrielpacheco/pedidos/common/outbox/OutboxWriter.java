package com.gabrielpacheco.pedidos.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gabrielpacheco.pedidos.common.config.MessagingProperties;
import com.gabrielpacheco.pedidos.common.messaging.EventType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper, MessagingProperties properties, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * MANDATORY: exige uma transação já aberta. Se alguém chamar este método fora de uma
     * transação de negócio, o erro aparece na hora, em vez de gravar um evento solto.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Record payload) {
        EventType type = EventType.of(payload);
        if (type.producer() != properties.self()) {
            throw new IllegalStateException("Este serviço (" + properties.self() + ") não publica " + type.routingKey());
        }
        try {
            repository.save(new OutboxEvent(type.routingKey(), objectMapper.writeValueAsString(payload), clock.instant()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Falha ao serializar " + type.routingKey(), ex);
        }
    }
}
