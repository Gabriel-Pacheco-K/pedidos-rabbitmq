package com.gabrielpacheco.pedidos.inventory.messaging;

import com.gabrielpacheco.pedidos.common.events.OrderCreated;
import com.gabrielpacheco.pedidos.common.events.PaymentApproved;
import com.gabrielpacheco.pedidos.common.events.PaymentDeclined;
import com.gabrielpacheco.pedidos.common.events.StockRejected;
import com.gabrielpacheco.pedidos.common.events.StockReserved;
import com.gabrielpacheco.pedidos.common.inbox.Inbox;
import com.gabrielpacheco.pedidos.common.messaging.IncomingEvent;
import com.gabrielpacheco.pedidos.common.messaging.InvalidMessageException;
import com.gabrielpacheco.pedidos.common.outbox.OutboxWriter;
import com.gabrielpacheco.pedidos.inventory.domain.Reservation;
import com.gabrielpacheco.pedidos.inventory.domain.ReservationRepository;
import com.gabrielpacheco.pedidos.inventory.domain.ReservationStatus;
import com.gabrielpacheco.pedidos.inventory.domain.Stock;
import com.gabrielpacheco.pedidos.inventory.domain.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class InventoryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventHandler.class);

    private final Inbox inbox;
    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final OutboxWriter outbox;
    private final Clock clock;

    public InventoryEventHandler(Inbox inbox, StockRepository stock, ReservationRepository reservations,
                                 OutboxWriter outbox, Clock clock) {
        this.inbox = inbox;
        this.stock = stock;
        this.reservations = reservations;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Inbox, mudança no estoque e evento de saída (outbox) na mesma transação. Se qualquer
     * parte falhar, nada é gravado e a mensagem é tentada de novo.
     */
    @Transactional
    public void handle(IncomingEvent event) {
        if (!inbox.firstDelivery(event)) {
            log.info("Mensagem {} já processada; ignorando a duplicata", event.messageId());
            return;
        }
        switch (event.type()) {
            case ORDER_CREATED -> reserve(event.payload(OrderCreated.class));
            case PAYMENT_APPROVED -> commit(event.payload(PaymentApproved.class).orderId());
            case PAYMENT_DECLINED -> release(event.payload(PaymentDeclined.class).orderId());
            default -> throw new InvalidMessageException("Evento não tratado: " + event.type());
        }
    }

    /** Reserva tudo ou nada: se faltar qualquer item, nenhum é separado. */
    private void reserve(OrderCreated order) {
        if (reservations.existsById(order.orderId())) {
            log.warn("Pedido {} já tem reserva; ignorando novo pedido de reserva", order.orderId());
            return;
        }
        // TreeMap: soma itens repetidos e mantém a ordem por SKU, a mesma usada nas travas.
        Map<String, Integer> wanted = order.items().stream()
                .collect(Collectors.toMap(OrderCreated.Item::sku, OrderCreated.Item::quantity, Integer::sum, TreeMap::new));

        Map<String, Stock> locked = stock.lockAll(wanted.keySet()).stream()
                .collect(Collectors.toMap(Stock::getSku, Function.identity()));

        List<String> missing = wanted.entrySet().stream()
                .filter(item -> !locked.containsKey(item.getKey()) || !locked.get(item.getKey()).canReserve(item.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (!missing.isEmpty()) {
            reservations.save(new Reservation(order.orderId(), ReservationStatus.REJECTED, order.total(), wanted, clock.instant()));
            outbox.append(new StockRejected(order.orderId(), "Estoque insuficiente: " + String.join(", ", missing)));
            return;
        }

        wanted.forEach((sku, quantity) -> locked.get(sku).reserve(quantity));
        reservations.save(new Reservation(order.orderId(), ReservationStatus.RESERVED, order.total(), wanted, clock.instant()));
        outbox.append(new StockReserved(order.orderId(), order.customerId(), order.total()));
    }

    private void commit(UUID orderId) {
        Reservation reservation = lockReserved(orderId);
        lockLines(reservation).forEach((stockRow, quantity) -> stockRow.commit(quantity));
        reservation.moveTo(ReservationStatus.COMMITTED, clock.instant());
    }

    /** Compensação da saga: o pagamento falhou, então os itens voltam para o estoque. */
    private void release(UUID orderId) {
        Reservation reservation = lockReserved(orderId);
        lockLines(reservation).forEach((stockRow, quantity) -> stockRow.release(quantity));
        reservation.moveTo(ReservationStatus.RELEASED, clock.instant());
    }

    private Reservation lockReserved(UUID orderId) {
        Reservation reservation = reservations.lockById(orderId)
                .orElseThrow(() -> new InvalidMessageException("Sem reserva para o pedido " + orderId));
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new InvalidMessageException("Reserva do pedido " + orderId + " está em " + reservation.getStatus());
        }
        return reservation;
    }

    private Map<Stock, Integer> lockLines(Reservation reservation) {
        Map<String, Integer> quantities = new TreeMap<>(reservation.quantities());
        return stock.lockAll(quantities.keySet()).stream()
                .collect(Collectors.toMap(Function.identity(), s -> quantities.get(s.getSku())));
    }
}
