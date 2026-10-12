package com.gabrielpacheco.pedidos.payment.messaging;

import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.IncomingMessageReader;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class PaymentEventsListener {

    private static final Set<EventType> ACCEPTED = EnumSet.of(EventType.STOCK_RESERVED);

    private final IncomingMessageReader reader;
    private final PaymentEventHandler handler;

    public PaymentEventsListener(IncomingMessageReader reader, PaymentEventHandler handler) {
        this.reader = reader;
        this.handler = handler;
    }

    @RabbitListener(queues = "payment.events")
    public void onMessage(Message message) {
        handler.handle(reader.read(message, ACCEPTED));
    }
}
