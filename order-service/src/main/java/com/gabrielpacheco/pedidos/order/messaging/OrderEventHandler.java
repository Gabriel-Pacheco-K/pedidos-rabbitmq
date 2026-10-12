package com.gabrielpacheco.pedidos.order.messaging;

import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockRejected;
import com.gabrielpacheco.pedidos.common.inbox.Inbox;
import com.gabrielpacheco.pedidos.common.messaging.IncomingEvent;
import com.gabrielpacheco.pedidos.common.messaging.InvalidMessageException;
import com.gabrielpacheco.pedidos.order.domain.Order;
import com.gabrielpacheco.pedidos.order.domain.OrderRepository;
import com.gabrielpacheco.pedidos.order.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Component
public class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    private final Inbox inbox;
    private final OrderRepository orders;
    private final Clock clock;

    public OrderEventHandler(Inbox inbox, OrderRepository orders, Clock clock) {
        this.inbox = inbox;
        this.orders = orders;
        this.clock = clock;
    }

    /** Registro no inbox e mudança no pedido na mesma transação: ou os dois acontecem, ou nenhum. */
    @Transactional
    public void handle(IncomingEvent event) {
        if (!inbox.firstDelivery(event)) {
            log.info("Mensagem {} já processada; ignorando a duplicata", event.messageId());
            return;
        }
        switch (event.type()) {
            case STOCK_REJECTED -> {
                StockRejected e = event.payload(StockRejected.class);
                transition(e.orderId(), OrderStatus.REJECTED, e.reason());
            }
            case PAYMENT_DECLINED -> {
                PaymentDeclined e = event.payload(PaymentDeclined.class);
                transition(e.orderId(), OrderStatus.CANCELLED, e.reason());
            }
            case PAYMENT_APPROVED -> {
                PaymentApproved e = event.payload(PaymentApproved.class);
                Order order = find(e.orderId());
                // Defesa extra: só confirma se o valor cobrado for exatamente o total do pedido.
                if (order.getTotal().compareTo(e.amount()) != 0) {
                    throw new InvalidMessageException("Valor aprovado (" + e.amount() + ") difere do total do pedido " + order.getId());
                }
                transition(order, OrderStatus.CONFIRMED, null);
            }
            default -> throw new InvalidMessageException("Evento não tratado: " + event.type());
        }
    }

    private void transition(UUID orderId, OrderStatus target, String reason) {
        transition(find(orderId), target, reason);
    }

    private void transition(Order order, OrderStatus target, String reason) {
        if (!order.changeStatus(target, reason, clock.instant())) {
            throw new InvalidMessageException("Pedido " + order.getId() + " em " + order.getStatus() + " não pode ir para " + target);
        }
    }

    private Order find(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new InvalidMessageException("Pedido " + orderId + " não existe"));
    }
}
