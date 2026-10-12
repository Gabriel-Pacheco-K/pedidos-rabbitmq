package com.gabrielpacheco.pedidos.common.events;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentApproved(
        @NotNull UUID orderId,
        @NotNull @DecimalMin("0.01") BigDecimal amount) {
}
