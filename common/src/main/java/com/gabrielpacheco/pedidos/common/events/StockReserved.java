package com.gabrielpacheco.pedidos.common.events;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record StockReserved(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 100) String customerId,
        @NotNull @DecimalMin("0.01") BigDecimal amount) {
}
