package com.gabrielpacheco.pedidos.order.service;

import com.gabrielpacheco.pedidos.common.events.OrderCreated;
import com.gabrielpacheco.pedidos.common.outbox.OutboxWriter;
import com.gabrielpacheco.pedidos.order.api.OrderDtos.ItemRequest;
import com.gabrielpacheco.pedidos.order.api.OrderDtos.OrderView;
import com.gabrielpacheco.pedidos.order.api.OrderDtos.PlaceOrderRequest;
import com.gabrielpacheco.pedidos.order.domain.Order;
import com.gabrielpacheco.pedidos.order.domain.OrderRepository;
import com.gabrielpacheco.pedidos.order.domain.Product;
import com.gabrielpacheco.pedidos.order.domain.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OrderService(OrderRepository orders, ProductRepository products, OutboxWriter outbox,
                        TransactionTemplate transactions, Clock clock) {
        this.orders = orders;
        this.products = products;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record Placed(OrderView order, boolean replayed) {
    }

    /**
     * Cria o pedido de forma idempotente. A mesma chave com o mesmo conteúdo devolve o pedido
     * já criado (útil quando a rede cai e o cliente reenvia); a mesma chave com outro conteúdo
     * é recusada. Se duas requisições iguais chegarem juntas, a restrição única do banco deixa
     * só uma passar, e a outra devolve o pedido vencedor.
     */
    public Placed place(String customerId, UUID idempotencyKey, PlaceOrderRequest request) {
        String hash = requestHash(request);
        try {
            return transactions.execute(status -> orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .map(existing -> replay(existing, hash))
                    .orElseGet(() -> create(customerId, idempotencyKey, hash, request)));
        } catch (DataIntegrityViolationException race) {
            return transactions.execute(status -> orders.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .map(existing -> replay(existing, hash))
                    .orElseThrow(() -> race));
        }
    }

    public OrderView get(String customerId, UUID orderId) {
        // Pedido de outro cliente responde 404, e não 403: não revela que o id existe.
        return transactions.execute(status -> orders.findByIdAndCustomerId(orderId, customerId)
                .map(OrderView::from)
                .orElseThrow(OrderExceptions.NotFound::new));
    }

    private Placed replay(Order existing, String hash) {
        if (!MessageDigest.isEqual(existing.getRequestHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            throw new OrderExceptions.IdempotencyConflict();
        }
        return new Placed(OrderView.from(existing), true);
    }

    private Placed create(String customerId, UUID idempotencyKey, String hash, PlaceOrderRequest request) {
        Set<String> skus = request.items().stream().map(ItemRequest::sku).collect(Collectors.toSet());
        if (skus.size() != request.items().size()) {
            throw new OrderExceptions.InvalidOrder("Cada produto deve aparecer uma única vez no pedido.");
        }

        Map<String, Product> catalog = products.findBySkuInAndActiveTrue(skus).stream()
                .collect(Collectors.toMap(Product::getSku, Function.identity()));
        List<String> unknown = skus.stream().filter(sku -> !catalog.containsKey(sku)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new OrderExceptions.InvalidOrder("Produtos indisponíveis: " + String.join(", ", unknown));
        }

        Order order = new Order(customerId, idempotencyKey, hash, clock.instant());
        request.items().stream()
                .sorted(Comparator.comparing(ItemRequest::sku))
                .forEach(item -> order.addItem(catalog.get(item.sku()), item.quantity()));
        orders.saveAndFlush(order);

        outbox.append(new OrderCreated(order.getId(), customerId,
                order.getItems().stream().map(i -> new OrderCreated.Item(i.getSku(), i.getQuantity())).toList(),
                order.getTotal()));
        return new Placed(OrderView.from(order), false);
    }

    /** Hash do conteúdo em forma canônica: a ordem dos itens no JSON não muda o resultado. */
    static String requestHash(PlaceOrderRequest request) {
        String canonical = request.items().stream()
                .sorted(Comparator.comparing(ItemRequest::sku).thenComparingInt(ItemRequest::quantity))
                .map(item -> item.sku() + ":" + item.quantity())
                .collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
