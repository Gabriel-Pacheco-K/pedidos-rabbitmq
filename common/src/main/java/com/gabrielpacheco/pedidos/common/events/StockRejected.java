package com.gabrielpacheco.pedidos.common.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record StockRejected(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 255) String reason) {
}
