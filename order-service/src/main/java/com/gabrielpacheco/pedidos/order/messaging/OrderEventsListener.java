package com.gabrielpacheco.pedidos.order.messaging;

import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.IncomingMessageReader;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class OrderEventsListener {

    private static final Set<EventType> ACCEPTED = EnumSet.of(
            EventType.STOCK_REJECTED, EventType.PAYMENT_APPROVED, EventType.PAYMENT_DECLINED);

    private final IncomingMessageReader reader;
    private final OrderEventHandler handler;

    public OrderEventsListener(IncomingMessageReader reader, OrderEventHandler handler) {
        this.reader = reader;
        this.handler = handler;
    }

    /** Recebe a mensagem bruta: a assinatura é verificada sobre os bytes antes de qualquer conversão. */
    @RabbitListener(queues = "order.events")
    public void onMessage(Message message) {
        handler.handle(reader.read(message, ACCEPTED));
    }
}
