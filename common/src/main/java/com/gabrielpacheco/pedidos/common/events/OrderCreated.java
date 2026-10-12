package com.gabrielpacheco.pedidos.common.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreated(
        @NotNull UUID orderId,
        @NotBlank @Size(max = 100) String customerId,
        @NotEmpty @Size(max = 20) List<@Valid Item> items,
        @NotNull @DecimalMin("0.01") BigDecimal total) {

    public record Item(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{3,30}$") String sku,
            @Min(1) @Max(100) int quantity) {
    }
}
