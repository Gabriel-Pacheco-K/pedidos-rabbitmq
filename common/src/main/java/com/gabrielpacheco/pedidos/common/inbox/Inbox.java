package com.gabrielpacheco.pedidos.common.inbox;

import com.gabrielpacheco.pedidos.common.messaging.IncomingEvent;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

public class Inbox {

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public Inbox(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Retorna true só na primeira vez que a mensagem é vista. Roda na mesma transação do
     * processamento: se o processamento falhar, o registro também é desfeito, e a mensagem
     * poderá ser processada de novo na retentativa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(IncomingEvent event) {
        return repository.insertIfAbsent(event.messageId(), event.type().routingKey(), clock.instant()) == 1;
    }
}
