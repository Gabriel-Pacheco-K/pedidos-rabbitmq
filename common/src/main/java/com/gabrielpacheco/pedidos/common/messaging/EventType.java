package com.gabrielpacheco.pedidos.common.messaging;

import com.gabrielpacheco.pedidos.common.events.OrderCreated;
import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockRejected;
import com.gabrielpacheco.pedidos.common.events.StockReserved;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catálogo fechado de eventos. Cada tipo tem um único produtor legítimo: o consumidor
 * só aceita a mensagem se ela estiver assinada com a chave desse produtor. Assim, um
 * serviço comprometido não consegue forjar eventos que não são dele.
 */
public enum EventType {
    ORDER_CREATED("order.created", Producer.ORDER, OrderCreated.class),
    STOCK_RESERVED("stock.reserved", Producer.INVENTORY, StockReserved.class),
    STOCK_REJECTED("stock.rejected", Producer.INVENTORY, StockRejected.class),
    PAYMENT_APPROVED("payment.approved", Producer.PAYMENT, PaymentApproved.class),
    PAYMENT_DECLINED("payment.declined", Producer.PAYMENT, PaymentDeclined.class);

    private final String routingKey;
    private final Producer producer;
    private final Class<? extends Record> payloadType;

    EventType(String routingKey, Producer producer, Class<? extends Record> payloadType) {
        this.routingKey = routingKey;
        this.producer = producer;
        this.payloadType = payloadType;
    }

    public static Optional<EventType> fromRoutingKey(String routingKey) {
        return Arrays.stream(values()).filter(type -> type.routingKey.equals(routingKey)).findFirst();
    }

    public static EventType of(Record payload) {
        return Arrays.stream(values())
                .filter(type -> type.payloadType == payload.getClass())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Payload sem tipo de evento: " + payload.getClass()));
    }

    public String routingKey() {
        return routingKey;
    }

    public Producer producer() {
        return producer;
    }

    public Class<? extends Record> payloadType() {
        return payloadType;
    }
}
