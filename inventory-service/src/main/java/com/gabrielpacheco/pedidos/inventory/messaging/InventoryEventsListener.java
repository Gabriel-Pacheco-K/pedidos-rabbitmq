package com.gabrielpacheco.pedidos.inventory.messaging;

import com.gabrielpacheco.pedidos.common.messaging.EventType;
import com.gabrielpacheco.pedidos.common.messaging.IncomingMessageReader;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class InventoryEventsListener {

    private static final Set<EventType> ACCEPTED = EnumSet.of(
            EventType.ORDER_CREATED, EventType.PAYMENT_APPROVED, EventType.PAYMENT_DECLINED);

    private final IncomingMessageReader reader;
    private final InventoryEventHandler handler;

    public InventoryEventsListener(IncomingMessageReader reader, InventoryEventHandler handler) {
        this.reader = reader;
        this.handler = handler;
    }

    @RabbitListener(queues = "inventory.events", concurrency = "${app.inventory.consumers:4}")
    public void onMessage(Message message) {
        handler.handle(reader.read(message, ACCEPTED));
    }
}
