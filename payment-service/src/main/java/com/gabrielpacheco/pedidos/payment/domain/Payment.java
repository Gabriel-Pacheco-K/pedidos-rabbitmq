package com.gabrielpacheco.pedidos.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Um pagamento por pedido: a chave primária é o próprio id do pedido, então não existe cobrança dupla. */
@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { APPROVED, DECLINED }

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    public Payment(UUID orderId, String customerId, BigDecimal amount, Status status, String reason, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Status getStatus() {
        return status;
    }
}
