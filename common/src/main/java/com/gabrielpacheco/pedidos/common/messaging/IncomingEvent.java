package com.gabrielpacheco.pedidos.common.messaging;

import java.util.UUID;

/**
 * Evento que já passou por todas as verificações: assinatura, produtor, idade, formato e validação.
 */
public record IncomingEvent(UUID messageId, EventType type, Record payload) {

    public <T extends Record> T payload(Class<T> type) {
        return type.cast(payload);
    }
}
