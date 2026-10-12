package com.gabrielpacheco.pedidos.order.api;

import com.gabrielpacheco.pedidos.order.domain.Order;
import com.gabrielpacheco.pedidos.order.domain.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    /** O cliente informa só o que quer e quanto. Preço e total são calculados no servidor. */
    public record PlaceOrderRequest(
            @NotEmpty @Size(max = 20) List<@Valid @NotNull ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,30}$", message = "SKU inválido") String sku,
            @Min(1) @Max(100) int quantity) {
    }

    public record OrderView(
            UUID id,
            OrderStatus status,
            String statusReason,
            BigDecimal total,
            Instant createdAt,
            List<ItemView> items) {

        public static OrderView from(Order order) {
            return new OrderView(order.getId(), order.getStatus(), order.getStatusReason(), order.getTotal(), order.getCreatedAt(),
                    order.getItems().stream().map(i -> new ItemView(i.getSku(), i.getQuantity(), i.getUnitPrice())).toList());
        }
    }

    public record ItemView(String sku, int quantity, BigDecimal unitPrice) {
    }
}
