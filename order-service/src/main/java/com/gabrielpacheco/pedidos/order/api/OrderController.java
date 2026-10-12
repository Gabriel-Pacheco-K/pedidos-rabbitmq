package com.gabrielpacheco.pedidos.order.api;

import com.gabrielpacheco.pedidos.order.api.OrderDtos.OrderView;
import com.gabrielpacheco.pedidos.order.api.OrderDtos.PlaceOrderRequest;
import com.gabrielpacheco.pedidos.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 202 Accepted: o pedido foi registrado, mas a confirmação depende do estoque e do
     * pagamento, que acontecem em segundo plano. O cliente acompanha pelo GET.
     * Um reenvio com a mesma chave devolve 200 com o pedido original.
     */
    @PostMapping
    public ResponseEntity<OrderView> place(@AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                           @Valid @RequestBody PlaceOrderRequest request) {
        OrderService.Placed placed = orderService.place(jwt.getSubject(), idempotencyKey, request);
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/orders/" + placed.order().id()))
                .body(placed.order());
    }

    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orderService.get(jwt.getSubject(), id);
    }
}
